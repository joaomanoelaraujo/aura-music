package com.aura.music.playback.controller

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.aura.music.core.common.ApplicationScope
import com.aura.music.core.model.DefaultRepeat
import com.aura.music.core.model.Track
import com.aura.music.data.local.AppSettings
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.media.LibraryIndex
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.playback.PlaybackEvents
import com.aura.music.playback.queue.MediaItemMapper
import com.aura.music.playback.service.AuraPlaybackService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RepeatMode { Off, All, One }

data class QueueItem(val key: String, val track: Track)

/** What the user asked the player to do, as seen by an [ActionRedirect]. */
sealed interface UserAction {
    data class Play(val tracks: List<Track>, val startIndex: Int, val shuffle: Boolean) : UserAction
    data class PlayNext(val tracks: List<Track>) : UserAction
    data class AddToQueue(val tracks: List<Track>) : UserAction
    data object TogglePlayPause : UserAction
    data object Next : UserAction
    data object Previous : UserAction
    data class Seek(val positionMs: Long) : UserAction

    /** Shuffle, repeat and queue edits: meaningless on a phone that only follows someone else. */
    data object LocalOnly : UserAction
}

/** Takes over the user's playback actions; returns true when it handled one. */
fun interface ActionRedirect {
    fun handle(action: UserAction): Boolean
}

data class PlayerUiState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val hasNext: Boolean = false,
    /** Tracks Media3 would move to next/previous, honouring shuffle order and repeat mode. */
    val nextTrack: Track? = null,
    val previousTrack: Track? = null,
) {
    val hasMedia: Boolean get() = current != null
}

data class PlaybackPosition(val positionMs: Long = 0, val durationMs: Long = 0) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * The UI's single gateway to playback. Wraps a [MediaController] bound to [AuraPlaybackService]
 * and republishes its state as immutable flows.
 */
@Singleton
class PlayerConnection @Inject constructor(
    @param:ApplicationContext private val context: Context,
    library: LibraryRepository,
    settingsRepository: SettingsRepository,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(appScope, SharingStarted.Eagerly, AppSettings())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller = MutableStateFlow<MediaController?>(null)
    private val snapshot = MutableStateFlow(ControllerSnapshot())
    /**
     * While set, the user's playback actions go here instead of the local player. AURA Together uses
     * it on guests, so picking a song anywhere in the app plays it for the whole jam.
     */
    @Volatile var redirect: ActionRedirect? = null

    private fun redirected(action: UserAction) = redirect?.handle(action) == true

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Titles of tracks that failed to play, for transient user feedback. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * The mapped queue is cached by identity: it is rebuilt only when the timeline or the library
     * changes, not on every play/pause event, which matters for queues of thousands of tracks.
     */
    private var queueCache: Triple<List<MediaItem>, LibraryIndex, List<QueueItem>>? = null

    val state: StateFlow<PlayerUiState> = combine(snapshot, library.index) { snap, index -> snap.toUiState(queueFor(snap.items, index)) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, PlayerUiState())

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED)
            snapshot.value = ControllerSnapshot.from(player, if (timelineChanged) null else snapshot.value.items)
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onCustomCommand(controller: MediaController, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (PlaybackEvents.isTrackSkipped(command)) _errors.tryEmit(PlaybackEvents.title(args))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onDisconnected(controller: MediaController) {
            // The service went away (e.g. killed); forget this controller so the next command reconnects.
            disconnect()
        }
    }

    private fun queueFor(items: List<MediaItem>, index: LibraryIndex): List<QueueItem> {
        queueCache?.let { (cachedItems, cachedIndex, queue) -> if (cachedItems === items && cachedIndex === index) return queue }
        return mapQueue(items, index).also { queueCache = Triple(items, index, it) }
    }

    @OptIn(UnstableApi::class)
    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, AuraPlaybackService::class.java))
        val future = MediaController.Builder(context, token).setListener(controllerListener).buildAsync()
        controllerFuture = future
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (connected == null) {
                if (controllerFuture === future) controllerFuture = null
                return@addListener
            }
            if (controllerFuture !== future) {
                connected.release()
                return@addListener
            }
            connected.addListener(listener)
            snapshot.value = ControllerSnapshot.from(connected, null)
            controller.value = connected
        }, ContextCompat.getMainExecutor(context))
    }

    /** Idempotent: releasing a controller can re-enter here through onDisconnected. */
    fun disconnect() {
        val future = controllerFuture ?: return
        controllerFuture = null
        controller.value?.removeListener(listener)
        controller.value = null
        MediaController.releaseFuture(future)
    }

    /**
     * Emits the playback position while collected; ticks faster while audio is playing.
     * [playingTickMs] can be lowered for views that follow the music closely, such as lyrics.
     */
    fun positions(playingTickMs: Long = TICK_PLAYING_MS): Flow<PlaybackPosition> = flow {
        while (true) {
            val player = controller.value
            if (player == null) {
                emit(PlaybackPosition())
            } else {
                emit(PlaybackPosition(player.currentPosition, player.duration.coerceAtLeast(0)))
            }
            delay(if (player?.isPlaying == true) playingTickMs else TICK_IDLE_MS)
        }
    }.distinctUntilChanged()

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (!redirected(UserAction.Play(tracks, startIndex, shuffle))) playLocally(tracks, startIndex, shuffle)
    }

    private fun playLocally(tracks: List<Track>, startIndex: Int, shuffle: Boolean) = command {
        if (tracks.isEmpty()) return@command
        val prefs = settings.value
        val shuffled = shuffle || (prefs.keepShuffle && shuffleModeEnabled)
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(0, tracks.lastIndex)
        shuffleModeEnabled = shuffled
        when (prefs.defaultRepeat) {
            DefaultRepeat.Remember -> Unit
            DefaultRepeat.Off -> repeatMode = Player.REPEAT_MODE_OFF
            DefaultRepeat.All -> repeatMode = Player.REPEAT_MODE_ALL
            DefaultRepeat.One -> repeatMode = Player.REPEAT_MODE_ONE
        }
        setMediaItems(tracks.map(MediaItemMapper::toMediaItem), start, 0L)
        prepare()
        play()
    }

    fun playNext(tracks: List<Track>) {
        if (redirected(UserAction.PlayNext(tracks))) return
        command {
            if (mediaItemCount == 0) return@command startWith(tracks)
            addMediaItems(currentMediaItemIndex + 1, tracks.map(MediaItemMapper::toMediaItem))
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (redirected(UserAction.AddToQueue(tracks))) return
        command {
            if (mediaItemCount == 0) return@command startWith(tracks)
            addMediaItems(tracks.map(MediaItemMapper::toMediaItem))
        }
    }

    fun togglePlayPause() {
        if (!redirected(UserAction.TogglePlayPause)) togglePlayPauseLocally()
    }

    private fun togglePlayPauseLocally() = command {
        when {
            playbackState == Player.STATE_IDLE -> prepare().also { play() }
            playbackState == Player.STATE_ENDED -> seekToDefaultPosition(0).also { play() }
            playWhenReady -> pause()
            else -> play()
        }
    }

    fun skipNext() {
        if (!redirected(UserAction.Next)) command { seekToNext() }
    }

    fun skipPrevious() {
        if (!redirected(UserAction.Previous)) command { seekToPrevious() }
    }

    /** Moves to the adjacent item explicitly (used by swipe gestures, which show that item's cover). */
    fun skipToNextItem() {
        if (!redirected(UserAction.Next)) command { if (hasNextMediaItem()) seekToNextMediaItem() }
    }

    fun skipToPreviousItem() {
        if (!redirected(UserAction.Previous)) command { if (hasPreviousMediaItem()) seekToPreviousMediaItem() }
    }

    fun seekTo(positionMs: Long) {
        if (!redirected(UserAction.Seek(positionMs))) command { seekTo(positionMs) }
    }

    fun toggleShuffle() {
        if (!redirected(UserAction.LocalOnly)) command { shuffleModeEnabled = !shuffleModeEnabled }
    }

    fun cycleRepeat() {
        if (redirected(UserAction.LocalOnly)) return
        command {
            repeatMode = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun skipToQueueItem(index: Int) {
        if (redirected(UserAction.LocalOnly)) return
        command {
            if (index !in 0 until mediaItemCount) return@command
            seekToDefaultPosition(index)
            play()
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (redirected(UserAction.LocalOnly)) return
        command { if (from in 0 until mediaItemCount && to in 0 until mediaItemCount) moveMediaItem(from, to) }
    }

    fun removeQueueItem(index: Int) {
        if (redirected(UserAction.LocalOnly)) return
        command { if (index in 0 until mediaItemCount) removeMediaItem(index) }
    }

    private fun MediaController.startWith(tracks: List<Track>) {
        setMediaItems(tracks.map(MediaItemMapper::toMediaItem))
        prepare()
        play()
    }

    /**
     * Runs [block] on the main thread against the connected controller and returns its result,
     * or null if the service can't be reached. Used by AURA Together for exact positions.
     */
    suspend fun <T> withController(block: MediaController.() -> T): T? = withContext(Dispatchers.Main.immediate) {
        connect()
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { controller.filterNotNull().first() }?.block()
    }

    /** Playback speed with pitch preserved; used for tiny sync corrections. */
    fun setSpeed(speed: Float) = command { setPlaybackSpeed(speed) }

    /** Runs [block] once connected; gives up quietly if the service can't be reached in time. */
    private fun command(block: MediaController.() -> Unit) {
        scope.launch {
            connect()
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { controller.filterNotNull().first() }?.block()
        }
    }

    private companion object {
        const val TICK_PLAYING_MS = 250L
        const val TICK_IDLE_MS = 1_000L
        const val CONNECT_TIMEOUT_MS = 5_000L
    }
}

/** Raw, library-independent view of the controller; mapped to tracks once the index is known. */
private data class ControllerSnapshot(
    val items: List<MediaItem> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val nextIndex: Int = C.INDEX_UNSET,
    val previousIndex: Int = C.INDEX_UNSET,
) {
    fun toUiState(queue: List<QueueItem>): PlayerUiState =
        PlayerUiState(
            current = queue.getOrNull(currentIndex)?.track,
            isPlaying = isPlaying,
            shuffle = shuffle,
            repeatMode = when (repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
            queue = queue,
            currentIndex = currentIndex,
            hasNext = hasNext,
            nextTrack = queue.getOrNull(nextIndex)?.track,
            previousTrack = queue.getOrNull(previousIndex)?.track,
        )

    companion object {
        fun from(player: Player, cachedItems: List<MediaItem>?): ControllerSnapshot = ControllerSnapshot(
            items = cachedItems ?: List(player.mediaItemCount, player::getMediaItemAt),
            currentIndex = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex,
            // Reflects intent: stays "playing" while briefly buffering so the button doesn't flicker.
            isPlaying = player.playWhenReady &&
                player.playbackState != Player.STATE_ENDED &&
                player.playbackState != Player.STATE_IDLE,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            hasNext = player.hasNextMediaItem(),
            nextIndex = player.nextMediaItemIndex,
            previousIndex = player.previousMediaItemIndex,
        )
    }
}

/** Resolves player items to library tracks, with keys that stay unique when a track is queued twice. */
private fun mapQueue(items: List<MediaItem>, index: LibraryIndex): List<QueueItem> {
    val occurrences = HashMap<String, Int>()
    return items.mapNotNull { item ->
        val track = MediaItemMapper.trackId(item)?.let(index::track) ?: MediaItemMapper.fallbackTrack(item)
        val occurrence = occurrences.merge(item.mediaId, 1, Int::plus)!!
        track?.let { QueueItem("${item.mediaId}#$occurrence", it) }
    }
}
