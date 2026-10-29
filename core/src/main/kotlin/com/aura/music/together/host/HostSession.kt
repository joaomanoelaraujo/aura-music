package com.aura.music.together.host

import com.aura.music.together.protocol.Command
import com.aura.music.together.protocol.ControlMode
import com.aura.music.together.protocol.IncomingTrack
import com.aura.music.together.protocol.Message
import com.aura.music.together.protocol.Participant
import com.aura.music.together.protocol.Reactions
import com.aura.music.together.protocol.RejectReason
import com.aura.music.together.protocol.RepeatSetting
import com.aura.music.together.protocol.SecureChannel
import com.aura.music.together.protocol.SessionState
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.protocol.Wire
import com.aura.music.together.transfer.SongSource
import com.aura.music.together.transfer.StreamCache
import com.aura.music.together.transfer.TrackReceiver
import com.aura.music.together.transfer.TransferQueue
import com.aura.music.together.transport.Connection
import com.aura.music.together.transport.ConnectionTimeout
import com.aura.music.together.transport.RelayEndpoint
import com.aura.music.together.transport.RelayHostLink
import com.aura.music.together.transport.RelayStatus
import com.aura.music.together.transport.SecureLink
import com.aura.music.together.transport.TcpConnection
import kotlinx.coroutines.async
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the host's own player looks like to the session. */
data class HostPlayback(
    val queue: List<TrackRef>,
    val index: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: RepeatSetting,
)

/**
 * The host's real player. As a [SongSource] it hands guests the queued songs they don't have
 * (including ones that other guests sent to the host).
 */
interface HostPlayer : SongSource {
    suspend fun read(): HostPlayback

    /** Applies a validated command to the real player. */
    suspend fun apply(command: Command)

    /** Emits whenever the track, play state, queue or modes change. */
    val changes: Flow<Unit>

    /** Whether this phone has its own copy of [ref]. */
    suspend fun has(ref: TrackRef): Boolean

    /** Puts a song a guest sent (a temporary copy of it) in the queue, where the guest asked. */
    suspend fun addReceived(ref: TrackRef, file: File, placement: Placement)
}

/** Where a song a guest asked for goes once it is available. */
enum class Placement { End, Next, Now }

data class HostConfig(
    val sessionId: String,
    val key: ByteArray,
    val sessionName: String,
    val hostName: String,
    val mode: ControlMode = ControlMode.Democratic,
    val maxParticipants: Int = 8,
    val requireApproval: Boolean = true,
    /** Listen for direct connections on the local network (null = don't). */
    val port: Int? = 0,
    /** Also accept guests through the internet relay. */
    val relay: RelayConfig? = null,
    /** The invite newcomers must use (null: any holder of the key; set to enable revoking). */
    val inviteId: String? = null,
)

/** The relay to join; [token] proves ownership of the session's room. */
data class RelayConfig(val endpoint: RelayEndpoint, val token: String)

/**
 * The session server running on the host's phone. It owns the authoritative [SessionState]:
 * every command from every participant goes through one queue, is checked against permissions,
 * applied to the host player and broadcast with a new version — so two people pressing "next" at
 * the same moment produce two ordered steps, never divergent states.
 */
class HostSession(
    private val config: HostConfig,
    private val player: HostPlayer,
    private val clock: () -> Long,
    /** Asks the host whether [name] may join. */
    private val approver: suspend (name: String) -> Boolean,
    /** Where songs guests add are received (null = guests can only add songs the host has). */
    private val cache: StreamCache? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = SecureChannel(config.key, config.sessionId)
    private val clients = ConcurrentHashMap<String, Client>()
    private val known = ConcurrentHashMap<String, Participant>()

    /** Secret per participant, given only to them, required to rejoin as them. */
    private val resumeTokens = ConcurrentHashMap<String, String>()

    /** Invites newcomers may still use; [revokeInvites] replaces them. */
    @Volatile private var validInvites: Set<String> = setOfNotNull(config.inviteId)

    /**
     * Stops every invite shared so far and returns a fresh invite id for a new link. People already
     * in the jam stay; nobody new can join with an old link.
     */
    fun revokeInvites(): String {
        val fresh = java.util.UUID.randomUUID().toString().replace("-", "")
        validInvites = setOf(fresh)
        return fresh
    }
    private val commands = Channel<Pair<String, Message.Cmd>>(Channel.UNLIMITED)
    private val publishLock = Mutex()
    private var server: ServerSocket? = null
    private var relayLink: RelayHostLink? = null

    /** Relay connection state, or null when the session is local-network only. */
    val relayStatus: StateFlow<RelayStatus>? get() = relayLink?.status
    private var version = 0L
    private var lastPublished: SessionState? = null

    private val _reactions = MutableSharedFlow<Message.Reaction>(extraBufferCapacity = 16)

    /** Reactions from everyone (the host's own included), for this phone's screen. */
    val reactions: SharedFlow<Message.Reaction> = _reactions

    private val lastReaction = ConcurrentHashMap<String, Long>()

    private val _state = MutableStateFlow<SessionState?>(null)
    val state: StateFlow<SessionState?> = _state.asStateFlow()

    private val hostParticipant = Participant(HOST_ID, config.hostName, isHost = true, canControl = true)
    private val _mode = MutableStateFlow(config.mode)

    val port: Int get() = server?.localPort ?: -1

    private class Client(val participant: Participant, val link: SecureLink)

    /** Songs one guest added that this phone doesn't have, received from that guest in order. */
    private class Upload(val from: Participant, cache: StreamCache) {
        val lock = Mutex()
        /** Songs not yet in the queue; one leaves as soon as it starts arriving (it plays as it comes). */
        val pending = ArrayDeque<TrackRef>()
        val placements = HashMap<TrackRef, Placement>()
        val receiver = TrackReceiver(cache)
        var progress = 0f

        /** Copy for snapshots, which run outside [lock]. */
        @Volatile var view: List<IncomingTrack> = emptyList()

        fun refreshView() {
            view = pending.mapIndexed { i, ref -> IncomingTrack(ref, from.name, if (i == 0) progress else 0f) }
        }
    }

    private val uploads = ConcurrentHashMap<String, Upload>()

    /** Starts listening; returns the local port, or -1 when only the relay is used. */
    fun start(): Int {
        config.port?.let { port ->
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            server = socket
            scope.launch { acceptLoop(socket) }
        }
        config.relay?.let { relay ->
            relayLink = RelayHostLink(relay.endpoint.connector, relay.endpoint.hostUrl(config.sessionId), relay.token, scope, ::handle).also { it.start() }
        }
        scope.launch { processCommands() }
        scope.launch { player.changes.collect { publish() } }
        scope.launch { watchPosition() }
        scope.launch { publish() }
        return server?.localPort ?: -1
    }

    fun setMode(mode: ControlMode) {
        _mode.value = mode
        scope.launch { publish(force = true) }
    }

    /**
     * Explicit per-participant permission. Without one, everyone may control in [ControlMode.Democratic]
     * and only the host in [ControlMode.HostControl].
     */
    fun setCanControl(participantId: String, allowed: Boolean) {
        permissions[participantId] = allowed
        scope.launch { publish(force = true) }
    }

    private val permissions = ConcurrentHashMap<String, Boolean>()

    private fun canControl(id: String): Boolean = id == HOST_ID || permissions[id] ?: (_mode.value == ControlMode.Democratic)

    /** Removes a participant; their id is forgotten so the same invite can't silently rejoin. */
    fun kick(participantId: String) {
        known.remove(participantId)
        resumeTokens.remove(participantId)
        permissions.remove(participantId)
        uploads.remove(participantId)
        clients.remove(participantId)?.let { c -> farewell(c, RejectReason.Kicked) }
        scope.launch { publish(force = true) }
    }

    /** Ends the session for everyone immediately. */
    fun stop() {
        clients.values.forEach { c -> farewell(c, RejectReason.Ended) }
        clients.clear()
        CoroutineScope(Dispatchers.IO).launch { runCatching { server?.close() } }
        // Let the farewells reach the guests before tearing everything down.
        CoroutineScope(Dispatchers.IO).launch {
            delay(FAREWELL_MS)
            relayLink?.stop()
            scope.cancel()
        }
    }

    /** Tells a guest why it is leaving, then closes once the message has had time to arrive. */
    private fun farewell(client: Client, reason: RejectReason) {
        // Called from the UI thread, where Android forbids network I/O: send from an I/O thread.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { client.link.send(Message.Rejected(reason)) }
            runCatching { client.link.connection.closeGracefully() }
        }
    }

    /** The host reacts: shown here and on every guest. */
    fun hostReact(emoji: String) = react(HOST_ID, config.hostName, emoji)

    /** Passes a reaction on to everyone, if it's an allowed emoji and the person isn't flooding. */
    private fun react(id: String, name: String, emoji: String) {
        if (emoji !in Reactions.allowed) return
        val now = clock()
        val last = lastReaction[id]
        if (last != null && now - last < Reactions.MIN_INTERVAL_MS) return
        lastReaction[id] = now
        val reaction = Message.Reaction(name, emoji)
        _reactions.tryEmit(reaction)
        scope.launch { clients.values.forEach { c -> runCatching { c.link.send(reaction) } } }
    }

    /** Called by the host's own UI: same queue and rules as everyone else. */
    fun hostCommand(command: Command) {
        commands.trySend(HOST_ID to Message.Cmd(0, command))
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive) {
            val client = try {
                withContext(Dispatchers.IO) { socket.accept() }
            } catch (_: IOException) {
                return
            }
            scope.launch { handle(TcpConnection(client)) }
        }
    }

    /** One guest's lifetime, whatever carries it (local TCP or the relay). */
    private suspend fun handle(connection: Connection) {
        val link = try {
            SecureLink.open(connection, channel, asHost = true, timeoutMs = HELLO_TIMEOUT_MS)
        } catch (e: IOException) {
            return connection.close()
        }
        var participantId: String? = null
        var transfers: TransferQueue? = null
        try {
            val hello = link.receive(HELLO_TIMEOUT_MS) as? Message.Hello ?: return connection.close()
            if (hello.protocol != Wire.PROTOCOL) {
                link.send(Message.Rejected(RejectReason.BadVersion))
                return connection.closeGracefully()
            }
            // Rejoining needs the participant's own secret, not just its id (which everyone sees).
            val returning = hello.participantId?.let(known::get)?.takeIf { p ->
                val expected = resumeTokens[p.id]
                expected != null && hello.resumeToken != null &&
                    java.security.MessageDigest.isEqual(expected.toByteArray(), hello.resumeToken.toByteArray())
            }
            if (returning == null && validInvites.isNotEmpty() && hello.inviteId !in validInvites) {
                link.send(Message.Rejected(RejectReason.InviteRevoked))
                return connection.closeGracefully()
            }
            if (returning == null) {
                if (clients.size + 1 >= config.maxParticipants) {
                    link.send(Message.Rejected(RejectReason.Full))
                    return connection.closeGracefully()
                }
                if (config.requireApproval) {
                    link.send(Message.WaitingApproval)
                    // Keep reading while the host decides, so pings don't pile up.
                    val approval = scope.async { withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { approver(hello.name.take(40)) } ?: false }
                    while (!approval.isCompleted) {
                        val pending = runCatching { link.receive(APPROVAL_POLL_MS) }
                        val failure = pending.exceptionOrNull()
                        if (failure != null && failure !is ConnectionTimeout) {
                            approval.cancel()
                            throw failure
                        }
                        (pending.getOrNull() as? Message.Ping)?.let { link.send(Message.Pong(it.guestSentMs, clock())) }
                    }
                    if (!approval.await()) {
                        link.send(Message.Rejected(RejectReason.Declined))
                        return connection.closeGracefully()
                    }
                }
            }
            val participant = returning ?: Participant(UUID.randomUUID().toString(), hello.name.take(40).ifBlank { "Convidado" })
            participantId = participant.id
            known[participant.id] = participant
            clients.remove(participant.id)?.let { runCatching { it.link.connection.close() } }
            clients[participant.id] = Client(participant, link)
            publish(force = true)
            val token = resumeTokens.getOrPut(participant.id) {
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(java.security.SecureRandom()::nextBytes))
            }
            link.send(Message.Welcome(participant.id, _state.value ?: snapshot(), token))
            // Songs this guest was sending before a drop: ask again from what arrived.
            uploads[participant.id]?.let { u -> u.lock.withLock { u.receiver.connectionLost(); pumpUpload(participant.id, u) } }

            // One transfer at a time per guest, in order, beside the message loop so pings keep flowing.
            transfers = TransferQueue(
                scope,
                link,
                song = { ref -> if (isQueued(ref)) player.open(ref) else null },
                cover = { ref -> if (isQueued(ref)) player.artwork(ref) else null },
            )
            while (scope.isActive) {
                when (val message = link.receive(HEARTBEAT_TIMEOUT_MS)) {
                    is Message.Ping -> link.send(Message.Pong(message.guestSentMs, clock()))
                    is Message.Cmd -> commands.send(participant.id to message)
                    is Message.Fetch, is Message.FetchArt -> transfers.offer(message)
                    is Message.React -> react(participant.id, participant.name, message.emoji)
                    is Message.Chunk -> onUploadChunk(participant.id, message)
                    is Message.Art -> uploads[participant.id]?.let { u -> u.lock.withLock { u.receiver.onArt(message) } }
                    is Message.Unavailable -> onUploadUnavailable(participant.id, message)
                    Message.Leave -> {
                        uploads.remove(participant.id)
                        known.remove(participant.id)
                        break
                    }
                    else -> Unit
                }
            }
        } catch (_: IOException) {
            // Closed, or silent for too long (ConnectionTimeout): may come back with the same id.
        } finally {
            transfers?.close()
            participantId?.let { id -> clients[id]?.takeIf { it.link === link }?.let { clients.remove(id) } }
            connection.close()
            if (scope.isActive) scope.launch { publish(force = true) }
        }
    }

    private fun isQueued(ref: TrackRef) = _state.value?.queue?.contains(ref) == true

    /** A guest added songs this phone doesn't have: receive them from that guest, then queue them. */
    private suspend fun requestUpload(participantId: String, refs: List<TrackRef>, placement: Placement) {
        val cache = cache ?: return
        val from = known[participantId] ?: return
        val upload = uploads.getOrPut(participantId) { Upload(from, cache) }
        upload.lock.withLock {
            refs.filterNot { it in upload.pending }.forEach(upload.pending::addLast)
            refs.forEach { upload.placements[it] = placement }
            pumpUpload(participantId, upload)
        }
        publish(force = true)
    }

    /** Under [Upload.lock]: queues songs already received, and keeps the next one coming. */
    private suspend fun pumpUpload(participantId: String, upload: Upload) {
        while (true) {
            val next = upload.pending.firstOrNull() ?: break
            val done = cache?.complete(next) ?: break
            upload.pending.removeFirst()
            runCatching { player.addReceived(next, done, upload.placements.remove(next) ?: Placement.End) }
        }
        upload.refreshView()
        val link = clients[participantId]?.link ?: return
        // Keep receiving a song that already went into the queue until it is complete.
        if (upload.receiver.target == null) upload.receiver.receive(upload.pending.firstOrNull())
        runCatching { upload.receiver.pump(link::send) }
    }

    private suspend fun onUploadChunk(participantId: String, chunk: Message.Chunk) {
        val upload = uploads[participantId] ?: return
        var changed = false
        upload.lock.withLock {
            when (val event = upload.receiver.onChunk(chunk)) {
                is TrackReceiver.Event.Started -> {
                    // Into the queue right away: it plays (and reaches the others) while it arrives.
                    if (upload.pending.remove(event.ref)) {
                        upload.progress = 0f
                        runCatching { player.addReceived(event.ref, event.file, upload.placements.remove(event.ref) ?: Placement.End) }
                        changed = true
                    }
                }
                is TrackReceiver.Event.Progress -> if (event.ref in upload.pending) {
                    // Everyone sees the progress, in steps rather than on every chunk.
                    changed = (event.fraction * 10).toInt() != (upload.progress * 10).toInt()
                    upload.progress = event.fraction
                }
                is TrackReceiver.Event.Complete -> {
                    // Small enough to arrive in one go: it never "started".
                    if (upload.pending.remove(event.ref)) {
                        runCatching { player.addReceived(event.ref, event.file, upload.placements.remove(event.ref) ?: Placement.End) }
                    }
                    upload.progress = 0f
                    changed = true
                }
                is TrackReceiver.Event.Failed -> {
                    upload.pending.remove(event.ref)
                    upload.progress = 0f
                    changed = true
                }
                null -> Unit
            }
            pumpUpload(participantId, upload)
        }
        if (changed) publish(force = true)
    }

    private suspend fun onUploadUnavailable(participantId: String, message: Message.Unavailable) {
        val upload = uploads[participantId] ?: return
        upload.lock.withLock {
            if (!upload.receiver.onUnavailable(message)) return
            upload.pending.remove(message.track)
            upload.progress = 0f
            pumpUpload(participantId, upload)
        }
        publish(force = true)
    }

    private suspend fun processCommands() {
        for ((from, cmd) in commands) {
            if (!canControl(from)) {
                clients[from]?.let { runCatching { it.link.send(Message.Denied(cmd.requestId, RejectReason.NotAllowed)) } }
                continue
            }
            val command = cmd.command
            val (refs, placement) = when (command) {
                is Command.AddToQueue -> command.tracks to Placement.End
                is Command.PlayNext -> command.tracks to Placement.Next
                is Command.PlayNow -> listOf(command.track) to Placement.Now
                else -> null to Placement.End
            }
            if (refs != null && from != HOST_ID && cache != null) {
                // Songs this phone lacks come from the guest who asked, then go where they asked.
                val missing = refs.filterNot { runCatching { player.has(it) }.getOrDefault(false) }
                val present = refs - missing.toSet()
                if (present.isNotEmpty()) {
                    val partial = when (placement) {
                        Placement.End -> Command.AddToQueue(present)
                        Placement.Next -> Command.PlayNext(present)
                        Placement.Now -> Command.PlayNow(present.first())
                    }
                    runCatching { player.apply(partial) }
                }
                if (missing.isNotEmpty()) requestUpload(from, missing, placement)
            } else {
                runCatching { player.apply(command) }
            }
            // The player applies asynchronously; give it a moment so the broadcast reflects the result.
            delay(SETTLE_MS)
            publish(force = true)
        }
    }

    /** Republishes when the host player drifts from the last published timeline (e.g. a seek from the notification). */
    private suspend fun watchPosition() {
        while (scope.isActive) {
            delay(WATCH_INTERVAL_MS)
            val last = lastPublished ?: continue
            val now = player.read()
            if (abs(now.positionMs - last.expectedPositionAt(clock())) > REPUBLISH_DRIFT_MS) publish(force = true)
        }
    }

    private suspend fun snapshot(): SessionState {
        val p = player.read()
        val participants = listOf(hostParticipant) + clients.values
            .map { it.participant.copy(canControl = canControl(it.participant.id)) }
            .sortedBy { it.name }
        return SessionState(
            version = version,
            sessionName = config.sessionName,
            mode = _mode.value,
            queue = p.queue,
            index = p.index,
            isPlaying = p.isPlaying,
            positionMs = p.positionMs,
            atHostMs = clock(),
            shuffle = p.shuffle,
            repeat = p.repeat,
            participants = participants,
            maxParticipants = config.maxParticipants,
            incoming = uploads.values.flatMap { it.view },
        )
    }

    private suspend fun publish(force: Boolean = false) = publishLock.withLock {
        val next = snapshot()
        val previous = lastPublished
        if (!force && previous != null && previous.sameContentAs(next)) return@withLock
        version++
        val state = next.copy(version = version)
        lastPublished = state
        _state.value = state
        val message = Message.State(state)
        clients.values.forEach { c -> runCatching { c.link.send(message) } }
    }

    private fun SessionState.sameContentAs(o: SessionState) = queue == o.queue && index == o.index && isPlaying == o.isPlaying &&
        shuffle == o.shuffle && repeat == o.repeat && participants == o.participants && mode == o.mode &&
        incoming == o.incoming

    companion object {
        const val HOST_ID = "host"
        private const val HELLO_TIMEOUT_MS = 10_000L
        private const val HEARTBEAT_TIMEOUT_MS = 15_000L
        private const val APPROVAL_POLL_MS = 1_000L
        private const val APPROVAL_TIMEOUT_MS = 60_000L
        private const val SETTLE_MS = 120L
        private const val WATCH_INTERVAL_MS = 1_000L
        private const val REPUBLISH_DRIFT_MS = 400L
        private const val FAREWELL_MS = 1_000L
    }
}
