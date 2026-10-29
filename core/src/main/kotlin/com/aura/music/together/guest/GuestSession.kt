package com.aura.music.together.guest

import com.aura.music.together.protocol.Command
import com.aura.music.together.protocol.Invite
import com.aura.music.together.protocol.Message
import com.aura.music.together.protocol.RejectReason
import com.aura.music.together.protocol.SecureChannel
import com.aura.music.together.protocol.SessionState
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.protocol.Wire
import com.aura.music.together.sync.ClockSync
import com.aura.music.together.sync.Correction
import com.aura.music.together.sync.DriftCorrector
import com.aura.music.together.sync.DriftStats
import com.aura.music.together.transfer.SongSource
import com.aura.music.together.transfer.StreamCache
import com.aura.music.together.transfer.TrackReceiver
import com.aura.music.together.transfer.TransferQueue
import com.aura.music.together.transport.Connection
import com.aura.music.together.transport.RelayEndpoint
import com.aura.music.together.transport.SecureLink
import com.aura.music.together.transport.TcpConnection
import com.aura.music.together.transport.WsConnection
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The guest's own player, driven by the session. As a [SongSource] it hands the host the songs this
 * guest adds to the queue when the host doesn't have them.
 */
interface GuestPlayer : SongSource {
    /** Loads the local copy of [ref]; returns false when this device doesn't have it. */
    suspend fun load(ref: TrackRef, positionMs: Long, play: Boolean): Boolean

    /** Loads a temporary copy of [ref] received from the host. */
    suspend fun loadFile(ref: TrackRef, file: File, positionMs: Long, play: Boolean): Boolean

    /** Whether this device has its own copy of [ref]. */
    suspend fun has(ref: TrackRef): Boolean

    suspend fun setPlaying(playing: Boolean)
    suspend fun seekTo(positionMs: Long)
    suspend fun setSpeed(speed: Float)

    /** Current position, or null when nothing is loaded. */
    suspend fun positionMs(): Long?

    suspend fun isPlaying(): Boolean
}

enum class GuestStatus { Connecting, WaitingApproval, Connected, Reconnecting, Ended }

data class GuestUiState(
    val status: GuestStatus = GuestStatus.Connecting,
    val state: SessionState? = null,
    val participantId: String? = null,
    /** True when the current track can't play here yet (not on this device, still being received). */
    val missingTrack: Boolean = false,
    /** Progress (0..1) of receiving the current track from the host, or null when not receiving it. */
    val receiving: Float? = null,
    /** The host couldn't send the current track either. */
    val streamFailed: Boolean = false,
    val rttMs: Long = 0,
    val averageDriftMs: Long = 0,
    val endReason: RejectReason? = null,
    val lastDenied: Long = 0,
)

/**
 * A participant's connection to a host. Playback state only ever flows host → guest; the guest's
 * own actions go to the host as commands and come back as state, so devices can't feed each
 * other's changes back in a loop. Position is kept aligned with [ClockSync] + [DriftCorrector].
 */
class GuestSession(
    private val invite: Invite,
    private val name: String,
    private val player: GuestPlayer,
    private val clock: () -> Long,
    /** Optional diagnostics sink (debug builds); receives one line per sync decision. */
    private val log: ((String) -> Unit)? = null,
    /** Internet relay to fall back to when the host isn't reachable on the local network. */
    private val relay: RelayEndpoint? = null,
    /** Where songs this device doesn't have are received from the host (null = don't stream). */
    private val cache: StreamCache? = null,
    /** How drift is corrected; players that can't change speed pass one that only seeks. */
    private val corrector: DriftCorrector = DriftCorrector(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = SecureChannel(invite.key, invite.sessionId)
    private val clockSync = ClockSync()
    private val stats = DriftStats()
    private val requestIds = AtomicLong(1)
    @Volatile private var link: SecureLink? = null
    private var connection: Job? = null
    private var participantId: String? = null
    private var resumeToken: String? = null
    private var loaded: TrackRef? = null
    private var loadedIndex = -1
    private var appliedPlaying: Boolean? = null
    private var currentSpeed = 1f
    private var seekLeadMs = INITIAL_SEEK_LEAD_MS
    private var justSeeked = false
    private var lastConnectedAt = 0L

    // Receiving a missing song; only touched from the connection's receive loop.
    private val receiver = cache?.let(::TrackReceiver)

    /** The current song, when it plays from a copy that is still arriving. */
    private var streaming: TrackRef? = null
    private val unavailable = HashSet<TrackRef>()

    /** Songs this guest added to the queue: the only ones the host may fetch from it. */
    private val offered = ConcurrentHashMap.newKeySet<TrackRef>()

    private val _reactions = MutableSharedFlow<Message.Reaction>(extraBufferCapacity = 16)

    /** Reactions from everyone in the jam (this guest's own come back through the host). */
    val reactions: SharedFlow<Message.Reaction> = _reactions

    private val _ui = MutableStateFlow(GuestUiState())
    val ui: StateFlow<GuestUiState> = _ui.asStateFlow()

    fun start() {
        connection = scope.launch { connectLoop() }
        scope.launch { syncLoop() }
    }

    /** Sends [command] to the host; the result arrives as a new state. */
    fun send(command: Command) {
        val l = link ?: return
        when (command) {
            is Command.AddToQueue -> offered += command.tracks
            is Command.PlayNext -> offered += command.tracks
            is Command.PlayNow -> offered += command.track
            else -> Unit
        }
        scope.launch { runCatching { l.send(Message.Cmd(requestIds.getAndIncrement(), command)) } }
    }

    fun react(emoji: String) {
        val l = link ?: return
        scope.launch { runCatching { l.send(Message.React(emoji)) } }
    }

    /** Simulates a network drop (tests only): the session must reconnect on its own. */
    internal fun dropConnectionForTest() {
        link?.connection?.close()
    }

    fun leave() {
        val l = link
        // Off the UI thread: Android forbids network I/O there.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { l?.send(Message.Leave) }
            close(GuestStatus.Ended, null)
        }
    }

    private suspend fun connectLoop() {
        var backoff = INITIAL_BACKOFF_MS
        lastConnectedAt = clock()
        while (scope.isActive) {
            val reason = runCatching { session() }.exceptionOrNull()
            if (_ui.value.status == GuestStatus.Ended) return
            // Network errors are retried for a while after the last good connection; anything else ends.
            if (reason !is IOException || clock() - lastConnectedAt > RECONNECT_WINDOW_MS) return close(GuestStatus.Ended, null)
            if (_ui.value.status == GuestStatus.Connected) backoff = INITIAL_BACKOFF_MS
            _ui.update { it.copy(status = GuestStatus.Reconnecting) }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** The local network first (fast, works offline), then the relay when the invite allows it. */
    private suspend fun connect(): Connection {
        val relay = relay?.takeIf { invite.relay }
        val host = invite.host
        if (host != null) {
            try {
                return TcpConnection.connect(host, invite.port, if (relay != null) LAN_TRY_TIMEOUT_MS else CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                if (relay == null) throw e
            }
        }
        if (relay == null) throw IOException("No way to reach the host")
        return WsConnection(relay.connector.open(relay.guestUrl(invite.sessionId), emptyMap()))
    }

    /** One connection lifetime; returns or throws when it ends. */
    private suspend fun session() = withContext(Dispatchers.IO) {
        val l = SecureLink.open(connect(), channel, asHost = false)
        link = l
        var pinger: Job? = null
        // One upload to the host at a time, in order, beside the message loop so state keeps flowing.
        val uploads = TransferQueue(
            scope,
            l,
            song = { ref -> if (ref in offered) player.open(ref) else null },
            cover = { ref -> if (ref in offered) player.artwork(ref) else null },
        )
        try {
            l.send(Message.Hello(Wire.PROTOCOL, name, participantId, resumeToken, invite.inviteId))
            pinger = scope.launch { pingLoop(l) }
            while (isActive) {
                when (val m = l.receive()) {
                    Message.WaitingApproval -> _ui.update { it.copy(status = GuestStatus.WaitingApproval) }
                    is Message.Welcome -> {
                        participantId = m.participantId
                        resumeToken = m.resumeToken.ifEmpty { null }
                        lastConnectedAt = clock()
                        _ui.update { it.copy(status = GuestStatus.Connected, participantId = m.participantId) }
                        // Requests sent on a previous connection are lost: ask again from what arrived.
                        receiver?.connectionLost()
                        onState(m.state)
                        pumpDownload(l)
                    }
                    is Message.State -> {
                        onState(m.state)
                        pumpDownload(l)
                    }
                    is Message.Chunk -> onChunk(m, l)
                    is Message.Art -> receiver?.onArt(m)
                    is Message.Unavailable -> if (receiver?.onUnavailable(m) == true) {
                        unavailable += m.track
                        if (m.track == _ui.value.state?.current) _ui.update { it.copy(receiving = null, streamFailed = true) }
                        pumpDownload(l)
                    }
                    is Message.Fetch, is Message.FetchArt -> uploads.offer(m)
                    is Message.Reaction -> _reactions.tryEmit(m)
                    is Message.Pong -> {
                        clockSync.add(m.guestSentMs, m.hostMs, clock())
                        _ui.update { it.copy(rttMs = clockSync.rttMs) }
                    }
                    is Message.Denied -> _ui.update { it.copy(lastDenied = m.requestId) }
                    is Message.Rejected -> {
                        close(GuestStatus.Ended, m.reason)
                        return@withContext
                    }
                    else -> Unit
                }
            }
        } finally {
            uploads.close()
            pinger?.cancel()
            link = null
            l.connection.close()
        }
    }

    /**
     * Picks the song to receive (the current one if it's missing here, else the next one, so the
     * change of track doesn't stall) and keeps requests in flight for it.
     */
    private suspend fun pumpDownload(l: SecureLink) {
        val cache = cache ?: return
        val receiver = receiver ?: return
        val state = _ui.value.state ?: return
        val current = state.current
        val wanted = listOfNotNull(current, state.queue.getOrNull(state.index + 1)).firstOrNull { ref ->
            ref !in unavailable && cache.complete(ref) == null &&
                if (ref == current) _ui.value.missingTrack || streaming == ref else !player.has(ref)
        }
        receiver.receive(wanted)
        receiver.pump(l::send)
    }

    private suspend fun onChunk(chunk: Message.Chunk, l: SecureLink) {
        val isCurrent = chunk.track == _ui.value.state?.current
        when (val event = receiver?.onChunk(chunk) ?: return) {
            is TrackReceiver.Event.Started -> {
                // Play from the first chunk on: the rest keeps arriving while it plays.
                if (isCurrent && _ui.value.missingTrack) playReceived(event.ref, event.file)
                receiver.pump(l::send)
            }
            is TrackReceiver.Event.Progress -> {
                if (isCurrent && _ui.value.missingTrack) _ui.update { it.copy(receiving = event.fraction) }
                receiver.pump(l::send)
            }
            is TrackReceiver.Event.Complete -> {
                if (streaming == event.ref) streaming = null
                if (isCurrent && _ui.value.missingTrack) playReceived(event.ref, event.file)
                pumpDownload(l)
            }
            is TrackReceiver.Event.Failed -> {
                unavailable += event.ref
                pumpDownload(l)
            }
        }
    }

    /** The current song just finished arriving: join the timeline where it is now. */
    private suspend fun playReceived(ref: TrackRef, file: File) {
        val state = _ui.value.state ?: return
        val expected = state.expectedPositionAt(clockSync.hostNow(clock()))
        val ok = player.loadFile(ref, file, expected + if (state.isPlaying) seekLeadMs else 0, state.isPlaying)
        stats.reset()
        appliedPlaying = state.isPlaying && ok
        if (ok && cache?.complete(ref) == null) streaming = ref
        _ui.update { it.copy(missingTrack = !ok, receiving = null) }
    }

    private suspend fun pingLoop(l: SecureLink) {
        var sent = 0
        while (scope.isActive) {
            runCatching { l.send(Message.Ping(clock())) }
            sent++
            // A quick burst first for a good offset estimate, then a relaxed heartbeat.
            delay(if (sent < 6) 150 else PING_INTERVAL_MS)
        }
    }

    private suspend fun onState(state: SessionState) {
        val previous = _ui.value.state
        if (previous != null && state.version <= previous.version) return
        _ui.update { it.copy(state = state) }
        val ref = state.current
        if (ref == null) {
            player.setPlaying(false)
            return
        }
        val expected = state.expectedPositionAt(clockSync.hostNow(clock()))
        if (ref != loaded || state.index != loadedIndex) {
            val position = expected + if (state.isPlaying) seekLeadMs else 0
            val ok = player.load(ref, position, state.isPlaying) ||
                cache?.playable(ref)?.let { player.loadFile(ref, it, position, state.isPlaying) } == true
            streaming = ref.takeIf { ok && cache?.playable(ref) != null && cache.complete(ref) == null }
            stats.reset()
            loaded = ref
            loadedIndex = state.index
            appliedPlaying = state.isPlaying && ok
            _ui.update { it.copy(missingTrack = !ok, streamFailed = !ok && ref in unavailable, receiving = null) }
        } else if (appliedPlaying != state.isPlaying && !_ui.value.missingTrack) {
            player.setPlaying(state.isPlaying)
            appliedPlaying = state.isPlaying
            if (state.isPlaying || previous?.isPlaying == true) player.seekTo(expected)
        }
    }

    /** Keeps the local position on the host's timeline and turns local play/pause into commands. */
    private suspend fun syncLoop() {
        while (scope.isActive) {
            delay(SYNC_INTERVAL_MS)
            val state = _ui.value.state ?: continue
            if (_ui.value.status != GuestStatus.Connected || _ui.value.missingTrack || !clockSync.hasEstimate) continue
            val local = player.positionMs() ?: continue

            // Play/pause from this device's notification or headset: ask the host instead of diverging.
            val localPlaying = player.isPlaying()
            val applied = appliedPlaying
            if (applied != null && localPlaying != applied) {
                appliedPlaying = localPlaying
                send(if (localPlaying) Command.Play else Command.Pause)
                continue
            }

            val now = clock()
            val expected = state.expectedPositionAt(clockSync.hostNow(now))
            stats.add(local - expected)
            _ui.update { it.copy(averageDriftMs = stats.averageMs) }
            val decision = corrector.decide(local, expected, state.isPlaying, now)
            log?.invoke("local=$local expected=$expected drift=${local - expected} lead=$seekLeadMs offset=${clockSync.offsetMs} rtt=${clockSync.rttMs} v=${state.version} -> $decision")

            // Learn how long this device takes to resume after a seek, from where the last one landed.
            if (justSeeked) {
                seekLeadMs = ((seekLeadMs + (expected - local)) * 0.6 + seekLeadMs * 0.4).toLong().coerceIn(0, MAX_SEEK_LEAD_MS)
                justSeeked = false
            }
            when (val c = decision) {
                Correction.None -> applySpeed(1f)
                is Correction.Seek -> {
                    applySpeed(1f)
                    player.seekTo(c.toMs + if (state.isPlaying) seekLeadMs else 0)
                    justSeeked = state.isPlaying
                    stats.reset()
                }
                is Correction.Nudge -> applySpeed(c.speed)
            }
        }
    }

    /** Changing speed reconfigures the audio pipeline, so it is only touched when it really changes. */
    private suspend fun applySpeed(speed: Float) {
        if (speed == currentSpeed) return
        player.setSpeed(speed)
        currentSpeed = speed
    }

    private fun close(status: GuestStatus, reason: RejectReason?) {
        _ui.update { it.copy(status = status, endReason = reason ?: it.endReason) }
        val l = link
        CoroutineScope(Dispatchers.IO).launch { l?.connection?.close() }
        scope.launch {
            runCatching { player.setSpeed(1f) }
            // The session is over: stop following it instead of playing on unsynchronized.
            if (status == GuestStatus.Ended) {
                runCatching { player.setPlaying(false) }
                // Stop receiving first: a chunk written after the clear would bring the folder back.
                connection?.cancelAndJoin()
                cache?.clear()
            }
            scope.cancel()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6_000
        const val LAN_TRY_TIMEOUT_MS = 2_500
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 8_000L
        const val RECONNECT_WINDOW_MS = 120_000L
        const val PING_INTERVAL_MS = 3_000L
        const val SYNC_INTERVAL_MS = 500L
        const val INITIAL_SEEK_LEAD_MS = 80L
        const val MAX_SEEK_LEAD_MS = 1_000L
    }
}

