package com.aura.desktop.together

import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.library.ArtworkStore
import com.aura.desktop.library.LibraryMapper
import com.aura.desktop.library.ScannedFile
import com.aura.desktop.playback.PlayerController
import com.aura.desktop.playback.Repeat
import com.aura.music.core.model.Track
import com.aura.music.playback.stream.GrowingFiles
import com.aura.music.together.guest.GuestPlayer
import com.aura.music.together.guest.GuestSession
import com.aura.music.together.guest.GuestStatus
import com.aura.music.together.guest.GuestUiState
import com.aura.music.together.host.HostConfig
import com.aura.music.together.host.HostPlayback
import com.aura.music.together.host.HostPlayer
import com.aura.music.together.host.HostSession
import com.aura.music.together.host.Placement
import com.aura.music.together.host.RelayConfig
import com.aura.music.together.protocol.Command
import com.aura.music.together.protocol.ControlMode
import com.aura.music.together.protocol.Invite
import com.aura.music.together.protocol.RepeatSetting
import com.aura.music.together.protocol.SecureChannel
import com.aura.music.together.protocol.SessionState
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.sync.DriftCorrector
import com.aura.music.together.sync.TrackMatcher
import com.aura.music.together.transfer.StreamCache
import com.aura.music.together.transfer.TrackFile
import com.aura.music.together.transfer.Transfers
import com.aura.music.together.transport.OkHttpWsConnector
import com.aura.music.together.transport.RelayEndpoint
import com.aura.music.together.transport.RelayStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

sealed interface JamRole {
    data object Idle : JamRole

    data class Hosting(
        val link: String,
        val address: String?,
        val state: SessionState?,
        val relay: RelayStatus = RelayStatus.Connecting,
    ) : JamRole

    data class Guest(val invite: Invite, val ui: GuestUiState) : JamRole
}

/** A newcomer waiting for the host's answer. */
data class JoinRequest(val name: String, val answer: kotlinx.coroutines.CompletableDeferred<Boolean>)

/**
 * AURA Together on the computer, with the same protocol (and code) as Android: host a jam that
 * phones and computers join, or join someone else's.
 *
 * As a guest, each song plays from this computer's own copy (Local Sync). A song missing here is
 * shown as missing; it's received as a temporary copy only if the user turned that on.
 * Desktop playback can't change speed, so drift is corrected with precise seeks only.
 */
class DesktopTogether(
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val player: PlayerController,
    private val library: LibraryRepository,
    private val artwork: ArtworkStore,
    private val cacheDir: Path,
    private val inviteBaseUrl: String = "https://aura-convites.johnmanoelaraujo.workers.dev/aura/j/",
    relayUrl: String? = "wss://aura-convites.johnmanoelaraujo.workers.dev/relay/v1",
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** The local-network address put in invites (tests pass the loopback). */
    private val localAddress: () -> String? = { com.aura.desktop.link.LinkService.localAddresses().firstOrNull() },
) {
    private val relay = relayUrl?.let { RelayEndpoint(it, OkHttpWsConnector()) }

    /** Whether jams can also be reached over the internet relay. */
    val relayEnabled: Boolean get() = relay != null
    private val _role = MutableStateFlow<JamRole>(JamRole.Idle)
    val role: StateFlow<JamRole> = _role.asStateFlow()

    private val _requests = MutableStateFlow<List<JoinRequest>>(emptyList())
    val requests: StateFlow<List<JoinRequest>> = _requests.asStateFlow()

    /** Ambiguous matches: the song and this library's candidates, for the user to pick. */
    private val _ambiguous = MutableStateFlow<Pair<TrackRef, List<Track>>?>(null)
    val ambiguous: StateFlow<Pair<TrackRef, List<Track>>?> = _ambiguous.asStateFlow()
    private val choices = ConcurrentHashMap<TrackRef, Long>()

    private var host: HostSession? = null
    private var guest: GuestSession? = null
    private var hostInvite: Invite? = null
    private var observer: Job? = null
    private val received = ConcurrentHashMap<String, TrackRef>()

    // ---------------------------------------------------------------- hosting

    data class HostOptions(val name: String, val hostName: String, val democratic: Boolean = true, val approve: Boolean = true, val maxParticipants: Int = 8)

    fun startHosting(options: HostOptions): Boolean {
        if (_role.value !is JamRole.Idle) return false
        val key = SecureChannel.newKey()
        val sessionId = randomId(16)
        val inviteId = randomId(16)
        val cache = StreamCache(cacheDir.resolve("host").toFile().apply { mkdirs() }).also { it.clear() }
        val session = HostSession(
            HostConfig(
                sessionId = sessionId, key = key, sessionName = options.name, hostName = options.hostName,
                mode = if (options.democratic) ControlMode.Democratic else ControlMode.HostControl,
                maxParticipants = options.maxParticipants, requireApproval = options.approve, port = 0,
                relay = relay?.let { RelayConfig(it, randomId(32)) }, inviteId = inviteId,
            ),
            HostAdapter(), clock, ::askHost, cache,
        )
        val port = runCatching { session.start() }.getOrElse { return false }
        host = session
        val address = localAddress()
        val invite = Invite(sessionId, address?.takeIf { port > 0 }, port, key, options.hostName, options.name, relay = relay != null, inviteId = inviteId)
        hostInvite = invite
        _role.value = JamRole.Hosting(invite.toLink(inviteBaseUrl), address?.let { "$it:$port" }, null)
        observer = scope.launch {
            launch { session.state.collect { st -> _role.update { (it as? JamRole.Hosting)?.copy(state = st) ?: it } } }
            session.relayStatus?.collect { st -> _role.update { (it as? JamRole.Hosting)?.copy(relay = st) ?: it } }
        }
        return true
    }

    private suspend fun askHost(name: String): Boolean {
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val request = JoinRequest(name, answer)
        _requests.update { it + request }
        return try {
            answer.await()
        } finally {
            _requests.update { it - request }
        }
    }

    fun answer(request: JoinRequest, accept: Boolean) {
        request.answer.complete(accept)
    }

    fun revokeInvites() {
        val session = host ?: return
        val fresh = hostInvite?.copy(inviteId = session.revokeInvites()) ?: return
        hostInvite = fresh
        _role.update { (it as? JamRole.Hosting)?.copy(link = fresh.toLink(inviteBaseUrl)) ?: it }
    }

    fun kick(participantId: String) = host?.kick(participantId)

    fun setCanControl(participantId: String, allowed: Boolean) = host?.setCanControl(participantId, allowed)

    // ---------------------------------------------------------------- joining

    /** Joins the jam behind an invite link. [receiveMissing]: accept temporary copies of songs missing here. */
    fun join(link: String, name: String, receiveMissing: Boolean): Boolean {
        if (_role.value !is JamRole.Idle) return false
        val invite = Invite.parse(link.trim()) ?: return false
        val cache = if (receiveMissing) StreamCache(cacheDir.resolve("guest").toFile().apply { mkdirs() }).also { it.clear() } else null
        val session = GuestSession(
            invite, name, GuestAdapter(), clock, relay = relay, cache = cache,
            // No speed changes here: correct only with precise seeks, never more than one per 3 s.
            corrector = DriftCorrector(startNudgeMs = Long.MAX_VALUE, stopNudgeMs = Long.MAX_VALUE, seekThresholdMs = 150, seekCooldownMs = 3_000),
        )
        guest = session
        _role.value = JamRole.Guest(invite, session.ui.value)
        player.redirect = ::redirect
        session.start()
        observer = scope.launch {
            session.ui.collect { ui ->
                _role.update { (it as? JamRole.Guest)?.copy(ui = ui) ?: it }
                if (ui.status == GuestStatus.Ended) withContext(main) { player.redirect = null }
            }
        }
        return true
    }

    /** User actions while a guest become commands for the host. */
    private fun redirect(action: PlayerController.JamAction): Boolean {
        val g = guest ?: return false
        if (g.ui.value.status == GuestStatus.Ended) return false
        val allowed = g.ui.value.state?.participants?.firstOrNull { it.id == g.ui.value.participantId }?.canControl == true
        if (!allowed && action !is PlayerController.JamAction.LocalOnly) {
            player.notify("Nesta jam, só o anfitrião controla a música")
            return true
        }
        val state = g.ui.value.state
        when (action) {
            PlayerController.JamAction.TogglePlayPause -> g.send(if (state?.isPlaying == true) Command.Pause else Command.Play)
            PlayerController.JamAction.Next -> g.send(Command.Next)
            PlayerController.JamAction.Previous -> g.send(Command.Previous)
            is PlayerController.JamAction.Seek -> g.send(Command.Seek(action.positionMs))
            is PlayerController.JamAction.Play -> action.tracks.getOrNull(action.start)?.let { g.send(Command.PlayNow(TrackMatcher.ref(it))) }
            is PlayerController.JamAction.PlayNext -> g.send(Command.PlayNext(action.tracks.map(TrackMatcher::ref)))
            is PlayerController.JamAction.AddToQueue -> g.send(Command.AddToQueue(action.tracks.map(TrackMatcher::ref)))
            PlayerController.JamAction.LocalOnly -> player.notify("Na jam, a fila é do anfitrião")
        }
        return true
    }

    fun chooseMatch(ref: TrackRef, track: Track) {
        choices[ref] = track.id
        _ambiguous.value = null
        guest?.let { g -> scope.launch { g.ui.value.state?.let { /* next state applies the choice */ } } }
    }

    fun react(emoji: String) {
        host?.hostReact(emoji) ?: guest?.react(emoji)
    }

    /** Leaves (guest) or ends (host) the jam; the local player stays usable. */
    fun leave() {
        observer?.cancel()
        host?.stop()
        guest?.leave()
        host = null
        guest = null
        hostInvite = null
        player.redirect = null
        _ambiguous.value = null
        received.clear()
        _requests.value.forEach { it.answer.complete(false) }
        _role.value = JamRole.Idle
    }

    // ---------------------------------------------------------------- adapters

    private fun localTrack(ref: TrackRef): Track? {
        val index = library.state.value.index
        choices[ref]?.let(index::track)?.let { return it }
        return TrackMatcher.match(ref, index.tracks + index.audios).track
    }

    private fun fileOf(track: Track): TrackFile? {
        val file = File(track.uri)
        if (!file.isFile) return null
        return TrackFile(file.length()) { offset, length ->
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(length)
                val n = raf.read(buffer)
                if (n <= 0) ByteArray(0) else buffer.copyOf(n)
            }
        }
    }

    private fun coverJpeg(track: Track): ByteArray? {
        val file = artwork.fileFor(track) ?: return null
        val bytes = Files.readAllBytes(file)
        for (edge in listOf(512, 400, 300, 200)) {
            ArtworkStore.shrink(bytes, edge)?.takeIf { it.size <= Transfers.MAX_ART_BYTES }?.let { return it }
        }
        return null
    }

    /** A track for a received (possibly still arriving) file. */
    private fun receivedTrack(ref: TrackRef, file: File): Track = LibraryMapper.toTrack(
        ScannedFile(path = file.path, sizeBytes = file.length(), modifiedMs = 0, title = ref.title, artist = ref.artist, album = ref.album, durationMs = ref.durationMs),
    )

    /** The desktop decoder can't read a file that's still growing: wait until it's complete. */
    /** Waits for a received song to finish arriving; false if it never will (failed, jam ended). */
    private suspend fun awaitComplete(file: File): Boolean {
        while (!GrowingFiles.isComplete(file)) {
            if (!GrowingFiles.isPlayable(file)) return false
            delay(150)
        }
        return true
    }

    private inner class HostAdapter : HostPlayer {
        override val changes: Flow<Unit> = player.state
            .map { listOf(it.queue.items.map { q -> q.uid }, it.queue.index, it.isPlaying, it.queue.shuffle, it.queue.repeat) }
            .distinctUntilChanged()
            .map { }

        override suspend fun read(): HostPlayback {
            val s = player.state.value
            return HostPlayback(
                queue = s.queue.items.map { received[it.track.uri] ?: TrackMatcher.ref(it.track) },
                index = s.queue.index.coerceAtLeast(0),
                isPlaying = s.isPlaying,
                positionMs = player.exactPositionMs(),
                shuffle = s.queue.shuffle,
                repeat = when (s.queue.repeat) { Repeat.Off -> RepeatSetting.Off; Repeat.All -> RepeatSetting.All; Repeat.One -> RepeatSetting.One },
            )
        }

        override suspend fun apply(command: Command): Unit = withContext(main) {
            val s = player.state.value
            when (command) {
                Command.Play -> if (!s.isPlaying) player.togglePlayPause()
                Command.Pause -> if (s.isPlaying) player.togglePlayPause()
                is Command.Seek -> player.seekTo(command.positionMs)
                Command.Next -> player.next()
                Command.Previous -> player.previous()
                is Command.SkipTo -> player.jumpTo(command.index)
                is Command.MoveInQueue -> player.moveInQueue(command.from, command.to)
                is Command.RemoveFromQueue -> if (command.index != s.queue.index) player.removeFromQueue(command.index)
                is Command.SetShuffle -> if (s.queue.shuffle != command.enabled) player.toggleShuffle()
                is Command.SetRepeat -> player.setRepeat(when (command.mode) { RepeatSetting.Off -> Repeat.Off; RepeatSetting.All -> Repeat.All; RepeatSetting.One -> Repeat.One })
                is Command.AddToQueue -> insert(command.tracks.mapNotNull(::itemFor), Placement.End)
                is Command.PlayNext -> insert(command.tracks.mapNotNull(::itemFor), Placement.Next)
                is Command.PlayNow -> itemFor(command.track)?.let { insert(listOf(it), Placement.Now) }
            }
        }

        private fun itemFor(ref: TrackRef): Track? {
            received.entries.firstOrNull { it.value == ref }?.let { (path, r) -> return receivedTrack(r, File(path)) }
            return localTrack(ref)
        }

        private fun insert(tracks: List<Track>, placement: Placement) {
            if (tracks.isEmpty()) return
            val s = player.state.value
            when {
                s.queue.isEmpty -> player.playTracks(tracks, 0)
                placement == Placement.End -> player.addToQueue(tracks)
                placement == Placement.Next -> player.playNext(tracks)
                else -> {
                    player.playNext(tracks)
                    player.jumpTo(player.state.value.queue.index + 1)
                }
            }
        }

        override suspend fun open(ref: TrackRef): TrackFile? {
            received.entries.firstOrNull { it.value == ref }?.let { return TrackFile.of(File(it.key)) }
            // Only songs in the queue are served.
            val track = player.state.value.queue.items.map { it.track }.firstOrNull { TrackMatcher.ref(it) == ref } ?: return null
            return fileOf(track)
        }

        override suspend fun artwork(ref: TrackRef): ByteArray? {
            received.entries.firstOrNull { it.value == ref }?.let { return StreamCache.artOf(File(it.key)).takeIf(File::exists)?.readBytes() }
            val track = player.state.value.queue.items.map { it.track }.firstOrNull { TrackMatcher.ref(it) == ref } ?: return null
            return withContext(Dispatchers.IO) { coverJpeg(track) }
        }

        override suspend fun has(ref: TrackRef) = localTrack(ref) != null

        override suspend fun addReceived(ref: TrackRef, file: File, placement: Placement) {
            received[file.path] = ref
            scope.launch {
                if (awaitComplete(file)) withContext(main) { insert(listOf(receivedTrack(ref, file)), placement) }
            }
        }
    }

    private inner class GuestAdapter : GuestPlayer {
        override suspend fun load(ref: TrackRef, positionMs: Long, play: Boolean): Boolean {
            val index = library.state.value.index
            val chosen = choices[ref]?.let(index::track)
            val result = TrackMatcher.match(ref, index.tracks + index.audios)
            val track = chosen ?: result.track
            if (track == null) {
                // Not on this computer: stop instead of playing something else out of sync.
                withContext(main) { player.jamStop() }
                return false
            }
            _ambiguous.value = if (chosen == null && result.ambiguous.size > 1) ref to result.ambiguous else null
            withContext(main) { player.jamLoad(track, positionMs, play) }
            return true
        }

        override suspend fun loadFile(ref: TrackRef, file: File, positionMs: Long, play: Boolean): Boolean {
            // The desktop decoder needs the whole file. Waiting here would hold up the very
            // connection that brings the rest of it, so say "not yet": the session asks again
            // when the copy is complete.
            if (!GrowingFiles.isComplete(file)) return false
            _ambiguous.value = null
            withContext(main) { player.jamLoad(receivedTrack(ref, file), positionMs, play) }
            return true
        }

        override suspend fun has(ref: TrackRef) = localTrack(ref) != null

        override suspend fun open(ref: TrackRef): TrackFile? = localTrack(ref)?.let(::fileOf)

        override suspend fun artwork(ref: TrackRef): ByteArray? = localTrack(ref)?.let { withContext(Dispatchers.IO) { coverJpeg(it) } }

        override suspend fun setPlaying(playing: Boolean) = withContext(main) { player.jamSetPlaying(playing) }

        override suspend fun seekTo(positionMs: Long) = withContext(main) { player.jamSeek(positionMs) }

        override suspend fun setSpeed(speed: Float) = Unit // not supported by the desktop engine (seek-only correction)

        override suspend fun positionMs(): Long? = if (player.state.value.current == null) null else player.exactPositionMs()

        override suspend fun isPlaying(): Boolean = player.state.value.isPlaying
    }

    private fun randomId(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))
}
