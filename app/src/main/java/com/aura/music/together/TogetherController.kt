package com.aura.music.together

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aura.music.BuildConfig
import com.aura.music.core.common.ApplicationScope
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.aura.music.core.model.ArtworkSource
import com.aura.music.core.model.Track
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.playback.controller.PlayerConnection
import com.aura.music.playback.controller.RepeatMode
import com.aura.music.playback.controller.ActionRedirect
import com.aura.music.playback.controller.UserAction
import com.aura.music.playback.queue.MediaItemMapper
import com.aura.music.playback.stream.GrowingFiles
import com.aura.music.together.guest.GuestPlayer
import com.aura.music.together.guest.GuestSession
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
import com.aura.music.together.protocol.Message
import com.aura.music.together.protocol.RepeatSetting
import com.aura.music.together.protocol.SecureChannel
import com.aura.music.together.protocol.SessionState
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.sync.TrackMatcher
import com.aura.music.together.transfer.StreamCache
import com.aura.music.together.transfer.TrackFile
import com.aura.music.together.transfer.Transfers
import com.aura.music.together.transport.OkHttpWsConnector
import com.aura.music.together.transport.RelayEndpoint
import com.aura.music.together.transport.RelayStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.net.Inet4Address
import java.nio.ByteBuffer
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JoinRequest(val id: Long, val name: String)

/** Feedback for what a guest did elsewhere in the app while following a jam. */
sealed interface JamNotice {
    /** The song went to the jam (it may first travel to the host's phone). */
    data class Sent(val title: String, val now: Boolean) : JamNotice

    /** This guest isn't allowed to control the jam. */
    data object ViewOnly : JamNotice
}

/** The app's current role in AURA Together. */
sealed interface TogetherRole {
    data object Idle : TogetherRole
    /** [address] is the local-network address, or null when guests can only come through the relay. */
    data class Hosting(
        val link: String,
        val address: String?,
        val state: SessionState?,
        val relay: RelayStatus = RelayStatus.Connecting,
    ) : TogetherRole
    data class Guest(val invite: Invite, val ui: GuestUiState) : TogetherRole
}

sealed interface StartResult {
    data object Started : StartResult
    data object Failed : StartResult
}

/**
 * Glue between the session engines and the real app. Hosting runs a [HostSession] on this phone's
 * local network address (when it has one) and in a room on the internet relay, so guests on mobile
 * data can join too; joining runs a [GuestSession]. Exactly one role at a time.
 */
@Singleton
class TogetherController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
    private val player: PlayerConnection,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _role = MutableStateFlow<TogetherRole>(TogetherRole.Idle)
    val role: StateFlow<TogetherRole> = _role.asStateFlow()

    private val _requests = MutableStateFlow<List<JoinRequest>>(emptyList())

    /** People waiting for the host to accept them. */
    val requests: StateFlow<List<JoinRequest>> = _requests.asStateFlow()
    private val pending = HashMap<Long, CompletableDeferred<Boolean>>()
    private var nextRequestId = 1L

    /** User choices for songs that exist more than once on this device (ref → track id). */
    private val matchChoices = MutableStateFlow<Map<TrackRef, Long>>(emptyMap())
    private val _ambiguous = MutableStateFlow<Pair<TrackRef, List<Track>>?>(null)

    /** When the current song matches several local files, the candidates for the user to choose. */
    val ambiguous: StateFlow<Pair<TrackRef, List<Track>>?> = _ambiguous.asStateFlow()

    private val _reactions = MutableSharedFlow<Message.Reaction>(extraBufferCapacity = 16)

    /** Emoji reactions from everyone in the jam, this phone's own included. */
    val reactions: SharedFlow<Message.Reaction> = _reactions.asSharedFlow()

    private val _notices = MutableSharedFlow<JamNotice>(extraBufferCapacity = 4)
    val notices: SharedFlow<JamNotice> = _notices.asSharedFlow()

    private var host: HostSession? = null
    private var guest: GuestSession? = null
    private var observer: Job? = null

    private val relay by lazy { RelayEndpoint(BuildConfig.RELAY_URL, OkHttpWsConnector()) }

    /** Songs received from other phones because this one doesn't have them; emptied on every session. */
    private val streamCache by lazy { StreamCache(File(context.cacheDir, MediaItemMapper.STREAM_DIR)) }

    private fun clock() = SystemClock.elapsedRealtime()

    suspend fun startHosting(sessionName: String): StartResult {
        if (_role.value !is TogetherRole.Idle) return StartResult.Started
        val address = localAddress()
        val prefs = settings.settings.first()
        val key = SecureChannel.newKey()
        val sessionId = randomId()
        val hostName = displayName(prefs.displayName)
        val name = sessionName.ifBlank { "Jam de $hostName" }
        // Proves to the relay that this phone owns the room; never leaves the device otherwise.
        val relayToken = randomId(32)
        streamCache.clear()
        received.clear()
        fun create(port: Int?) = HostSession(
            HostConfig(
                sessionId = sessionId,
                key = key,
                sessionName = name,
                hostName = hostName,
                mode = if (prefs.togetherDemocratic) ControlMode.Democratic else ControlMode.HostControl,
                maxParticipants = prefs.togetherMaxParticipants,
                requireApproval = prefs.approveJoins,
                port = port,
                relay = RelayConfig(relay, relayToken),
            ),
            HostAdapter(),
            ::clock,
            ::askHost,
            streamCache,
        )
        // The usual port may be taken; any free one works since the port travels in the link.
        val session = if (address == null) {
            runCatching { create(null).also { it.start() } }.getOrNull()
        } else {
            runCatching { create(DEFAULT_PORT).also { it.start() } }
                .recoverCatching { create(0).also { it.start() } }
                .getOrNull()
        } ?: return StartResult.Failed
        host = session
        val port = session.port
        val invite = Invite(sessionId, address, port, key, hostName, name, relay = true)
        val link = invite.toLink(BuildConfig.INVITE_BASE_URL)
        // Debug builds only, for automated two-device tests; release builds never log the key.
        if (BuildConfig.DEBUG) android.util.Log.d("AuraTogether", "invite $link")
        _role.value = TogetherRole.Hosting(link, address?.let { "$it:$port" }, null)
        observer = scope.launch {
            launch { session.reactions.collect(_reactions::emit) }
            launch { session.state.collect { state -> _role.update { (it as? TogetherRole.Hosting)?.copy(state = state) ?: it } } }
            session.relayStatus?.collect { status -> _role.update { (it as? TogetherRole.Hosting)?.copy(relay = status) ?: it } }
        }
        return StartResult.Started
    }

    suspend fun join(invite: Invite) {
        leave()
        val name = displayName(settings.settings.first().displayName)
        streamCache.clear()
        val session = GuestSession(
            invite,
            name,
            GuestAdapter(),
            ::clock,
            log = if (BuildConfig.DEBUG) { line -> android.util.Log.d("AuraSync", line) } else null,
            relay = relay,
            cache = streamCache,
        )
        guest = session
        _role.value = TogetherRole.Guest(invite, GuestUiState())
        // From now on, playing anything in the app means playing it for the jam.
        player.redirect = ActionRedirect(::redirect)
        session.start()
        observer = scope.launch {
            launch { session.reactions.collect(_reactions::emit) }
            session.ui.collect { ui -> _role.update { (it as? TogetherRole.Guest)?.copy(ui = ui) ?: it } }
        }
    }

    /** Ends hosting for everyone, or leaves as a guest. */
    fun leave() {
        player.redirect = null
        observer?.cancel()
        host?.stop()
        guest?.leave()
        host = null
        guest = null
        pending.values.forEach { it.complete(false) }
        pending.clear()
        _requests.value = emptyList()
        _ambiguous.value = null
        player.setSpeed(1f)
        _role.value = TogetherRole.Idle
    }

    /** Playback actions from the Together screen, routed through the session's single command queue. */
    fun command(command: Command) {
        host?.hostCommand(command) ?: guest?.send(command)
    }

    /**
     * A guest's action from anywhere in the app (library, search, mini player…). It becomes a jam
     * command: picking a song replaces what everyone hears, the controls drive the jam. Nothing
     * plays only on this phone, which would leave it out of sync with everyone else.
     */
    private fun redirect(action: UserAction): Boolean {
        val session = guest ?: return false
        val ui = session.ui.value
        val command = when (action) {
            is UserAction.Play -> {
                val track = if (action.shuffle) action.tracks.randomOrNull() else action.tracks.getOrNull(action.startIndex)
                track?.let { Command.PlayNow(refOf(it)) }
            }
            is UserAction.PlayNext -> Command.PlayNext(action.tracks.map(::refOf))
            is UserAction.AddToQueue -> Command.AddToQueue(action.tracks.map(::refOf))
            UserAction.TogglePlayPause -> if (ui.state?.isPlaying == true) Command.Pause else Command.Play
            UserAction.Next -> Command.Next
            UserAction.Previous -> Command.Previous
            is UserAction.Seek -> Command.Seek(action.positionMs)
            UserAction.LocalOnly -> null
        } ?: return true
        val allowed = ui.state?.participants?.firstOrNull { it.id == ui.participantId }?.canControl == true
        if (!allowed) {
            _notices.tryEmit(JamNotice.ViewOnly)
            return true
        }
        session.send(command)
        when (command) {
            is Command.PlayNow -> _notices.tryEmit(JamNotice.Sent(command.track.title, now = true))
            is Command.PlayNext -> command.tracks.singleOrNull()?.let { _notices.tryEmit(JamNotice.Sent(it.title, now = false)) }
            is Command.AddToQueue -> command.tracks.singleOrNull()?.let { _notices.tryEmit(JamNotice.Sent(it.title, now = false)) }
            else -> Unit
        }
        return true
    }

    fun respond(request: JoinRequest, accept: Boolean) {
        pending.remove(request.id)?.complete(accept)
        _requests.update { list -> list.filterNot { it.id == request.id } }
    }

    fun kick(participantId: String) = host?.kick(participantId)

    fun react(emoji: String) {
        host?.hostReact(emoji) ?: guest?.react(emoji)
    }

    fun setCanControl(participantId: String, allowed: Boolean) = host?.setCanControl(participantId, allowed)

    fun setMode(mode: ControlMode) = host?.setMode(mode)

    fun chooseMatch(ref: TrackRef, track: Track) {
        matchChoices.update { it + (ref to track.id) }
        _ambiguous.value = null
    }

    /** Library tracks the guest may add to the shared queue, as references. */
    fun refOf(track: Track) = TrackMatcher.ref(track)

    private suspend fun askHost(name: String): Boolean {
        val request = JoinRequest(nextRequestId++, name)
        val answer = CompletableDeferred<Boolean>()
        pending[request.id] = answer
        _requests.update { it + request }
        return answer.await()
    }

    /** Songs guests sent while hosting, by the file URI they play from → the guest's reference. */
    private val received = ConcurrentHashMap<String, TrackRef>()

    /** This phone's own copy of [ref], honoring the user's choice among duplicates. */
    private suspend fun localTrack(ref: TrackRef): Track? {
        val index = library.awaitIndex() ?: return null
        return matchChoices.value[ref]?.let(index::track) ?: TrackMatcher.match(ref, index.tracks + index.audios).track
    }

    /** Random access to a library song through MediaStore. */
    private fun fileOf(track: Track): TrackFile? {
        val uri = track.uri.toUri()
        val resolver = context.contentResolver
        val size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()?.takeIf { it > 0 } ?: track.sizeBytes
        if (size <= 0) return null
        return TrackFile(size) { offset, length ->
            val pfd = resolver.openFileDescriptor(uri, "r") ?: return@TrackFile ByteArray(0)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { channel ->
                channel.position(offset)
                val buffer = ByteBuffer.allocate(length)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                buffer.array().copyOf(buffer.position())
            }
        }
    }

    /** A song's cover as a JPEG small enough for one message, shrinking until it fits. */
    private suspend fun coverJpeg(source: ArtworkSource): ByteArray? {
        val request = ImageRequest.Builder(context).data(source).size(COVER_EDGE).allowHardware(false).build()
        val bitmap = (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        for ((edge, quality) in listOf(512 to 85, 400 to 80, 300 to 75, 200 to 70)) {
            val scale = edge.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            if (bytes.size <= Transfers.MAX_ART_BYTES) return bytes
        }
        return null
    }

    private fun displayName(saved: String) = saved.ifBlank { Build.MODEL.take(30) }

    private fun randomId(bytes: Int = 12): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))

    /** This phone's address on Wi-Fi or on its own hotspot; mobile-data addresses aren't reachable. */
    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { iface -> LAN_PREFIXES.indexOfFirst { iface.name.startsWith(it) }.let { if (it < 0) 99 else it } }
            .flatMap { iface ->
                if (LAN_PREFIXES.none { iface.name.startsWith(it) }) emptyList() else iface.inetAddresses.toList()
            }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** The host's real player, seen by the session. */
    private inner class HostAdapter : HostPlayer {
        override val changes: Flow<Unit> = player.state
            .map { listOf(it.queue.map { q -> q.key }, it.currentIndex, it.isPlaying, it.shuffle, it.repeatMode) }
            .distinctUntilChanged()
            .map { }

        override suspend fun read(): HostPlayback {
            val ui = player.state.value
            val position = player.withController { currentPosition } ?: 0L
            return HostPlayback(
                // Songs guests sent keep the guest's reference, so the sender plays its own copy.
                queue = ui.queue.map { received[it.track.uri] ?: TrackMatcher.ref(it.track) },
                index = ui.currentIndex.coerceAtLeast(0),
                isPlaying = ui.isPlaying,
                positionMs = position,
                shuffle = ui.shuffle,
                repeat = when (ui.repeatMode) {
                    RepeatMode.Off -> RepeatSetting.Off
                    RepeatMode.All -> RepeatSetting.All
                    RepeatMode.One -> RepeatSetting.One
                },
            )
        }

        override suspend fun apply(command: Command) {
            player.withController {
                when (command) {
                    Command.Play -> play()
                    Command.Pause -> pause()
                    is Command.Seek -> seekTo(command.positionMs)
                    Command.Next -> seekToNextMediaItem()
                    Command.Previous -> seekToPrevious()
                    is Command.SkipTo -> if (command.index in 0 until mediaItemCount) seekToDefaultPosition(command.index)
                    is Command.MoveInQueue -> if (command.from in 0 until mediaItemCount && command.to in 0 until mediaItemCount) moveMediaItem(command.from, command.to)
                    is Command.RemoveFromQueue -> if (command.index in 0 until mediaItemCount && command.index != currentMediaItemIndex) removeMediaItem(command.index)
                    is Command.SetShuffle -> shuffleModeEnabled = command.enabled
                    is Command.SetRepeat -> repeatMode = when (command.mode) {
                        RepeatSetting.Off -> Player.REPEAT_MODE_OFF
                        RepeatSetting.All -> Player.REPEAT_MODE_ALL
                        RepeatSetting.One -> Player.REPEAT_MODE_ONE
                    }
                    is Command.AddToQueue, is Command.PlayNext, is Command.PlayNow -> Unit
                }
            }
            when (command) {
                is Command.AddToQueue -> insert(command.tracks.mapNotNull { itemFor(it) }, Placement.End)
                is Command.PlayNext -> insert(command.tracks.mapNotNull { itemFor(it) }, Placement.Next)
                is Command.PlayNow -> itemFor(command.track)?.let { insert(listOf(it), Placement.Now) }
                else -> Unit
            }
        }

        /** A playable item for [ref] on this phone: its own copy, or one a guest sent earlier. */
        private suspend fun itemFor(ref: TrackRef): MediaItem? {
            receivedFile(ref)?.let { return MediaItemMapper.streamedItem(it, ref.title, ref.artist, ref.album, ref.durationMs) }
            return localTrack(ref)?.let(MediaItemMapper::toMediaItem)
        }

        private suspend fun insert(items: List<MediaItem>, placement: Placement) {
            if (items.isEmpty()) return
            player.withController {
                if (mediaItemCount == 0) {
                    setMediaItems(items)
                    prepare()
                    if (placement == Placement.Now) play()
                    return@withController
                }
                when (placement) {
                    Placement.End -> addMediaItems(items)
                    Placement.Next -> addMediaItems(currentMediaItemIndex + 1, items)
                    Placement.Now -> {
                        val at = currentMediaItemIndex + 1
                        addMediaItems(at, items)
                        seekToDefaultPosition(at)
                        play()
                    }
                }
            }
        }

        /** Opens a queued song: this phone's own file, or the copy a guest sent. */
        override suspend fun open(ref: TrackRef): TrackFile? {
            receivedFile(ref)?.let { return TrackFile.of(it) }
            return queuedTrack(ref)?.let(::fileOf)
        }

        override suspend fun artwork(ref: TrackRef): ByteArray? {
            receivedFile(ref)?.let { file -> return StreamCache.artOf(file).takeIf { it.exists() }?.readBytes() }
            return queuedTrack(ref)?.let { coverJpeg(it.artwork) }
        }

        override suspend fun has(ref: TrackRef) = localTrack(ref) != null

        override suspend fun addReceived(ref: TrackRef, file: File, placement: Placement) {
            received[Uri.fromFile(file).toString()] = ref
            insert(listOf(MediaItemMapper.streamedItem(file, ref.title, ref.artist, ref.album, ref.durationMs)), placement)
        }

        private fun queuedTrack(ref: TrackRef) = player.state.value.queue.map { it.track }.firstOrNull { TrackMatcher.ref(it) == ref }

        private fun receivedFile(ref: TrackRef) = received.entries.firstOrNull { it.value == ref }?.key?.let { Uri.parse(it).path }?.let(::File)?.takeIf(GrowingFiles::isPlayable)
    }

    /** This device's player while following someone else's session. */
    private inner class GuestAdapter : GuestPlayer {
        override suspend fun load(ref: TrackRef, positionMs: Long, play: Boolean): Boolean {
            val index = library.awaitIndex() ?: return false
            val candidates = index.tracks + index.audios
            val chosen = matchChoices.value[ref]?.let(index::track)
            val result = TrackMatcher.match(ref, candidates)
            val track = chosen ?: result.track
            if (track == null) {
                // Not on this device: stop the previous song instead of letting it play on unsynced.
                player.withController {
                    stop()
                    clearMediaItems()
                }
                return false
            }
            _ambiguous.value = if (chosen == null && result.ambiguous.size > 1) ref to result.ambiguous else null
            play(MediaItemMapper.toMediaItem(track), positionMs, play)
            return true
        }

        override suspend fun loadFile(ref: TrackRef, file: File, positionMs: Long, play: Boolean): Boolean {
            _ambiguous.value = null
            play(MediaItemMapper.streamedItem(file, ref.title, ref.artist, ref.album, ref.durationMs), positionMs, play)
            return true
        }

        override suspend fun has(ref: TrackRef) = localTrack(ref) != null

        /** The songs this guest added, for the host to receive when it doesn't have them. */
        override suspend fun open(ref: TrackRef): TrackFile? = localTrack(ref)?.let(::fileOf)

        override suspend fun artwork(ref: TrackRef): ByteArray? = localTrack(ref)?.let { coverJpeg(it.artwork) }

        private suspend fun play(item: MediaItem, positionMs: Long, play: Boolean) {
            player.withController {
                setMediaItems(listOf(item), 0, positionMs)
                shuffleModeEnabled = false
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
                playWhenReady = play
            }
        }

        override suspend fun setPlaying(playing: Boolean) {
            player.withController { playWhenReady = playing }
        }

        override suspend fun seekTo(positionMs: Long) {
            player.withController { seekTo(positionMs) }
        }

        override suspend fun setSpeed(speed: Float) {
            player.withController { setPlaybackSpeed(speed) }
        }

        override suspend fun positionMs(): Long? = player.withController { if (mediaItemCount == 0) null else currentPosition }

        override suspend fun isPlaying(): Boolean = player.withController { playWhenReady } ?: false
    }

    companion object {
        const val DEFAULT_PORT = 47_321
        private const val COVER_EDGE = 512
        private val LAN_PREFIXES = listOf("wlan", "swlan", "ap", "softap", "eth", "rndis")
    }
}
