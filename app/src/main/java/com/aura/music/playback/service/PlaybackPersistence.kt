package com.aura.music.playback.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aura.music.data.local.PlaybackStateStore
import com.aura.music.data.local.SavedPlayback
import com.aura.music.data.repository.HistoryRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.playback.queue.MediaItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the queue and position on disk so playback can resume after the process dies,
 * and records listening history. Runs inside the service, independent of any UI.
 */
class PlaybackPersistence(
    private val player: Player,
    private val stateStore: PlaybackStateStore,
    private val history: HistoryRepository,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var recordedMediaId: String? = null
    private var tickerJob: Job? = null

    suspend fun restore() {
        val saved = stateStore.load() ?: return
        val tracks = library.awaitIndex()?.tracks(saved.trackIds).orEmpty()
        // A controller may have started new playback while the library was loading.
        if (tracks.isEmpty() || player.mediaItemCount > 0) return
        val startIndex = saved.index.coerceIn(0, tracks.lastIndex)
        // Modes first, so the items are shuffled once rather than twice.
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        player.setMediaItems(tracks.map(MediaItemMapper::toMediaItem), startIndex, resumePosition(tracks[startIndex].durationMs, saved.positionMs))
        player.prepare()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        recordedMediaId = null
        if (player.isPlaying) recordPlay()
        savePosition()
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        val ids = queueIds()
        scope.launch { stateStore.saveQueue(ids) }
        savePosition()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveModes()

    override fun onRepeatModeChanged(repeatMode: Int) = saveModes()

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            recordPlay()
            startTicker()
        } else {
            tickerJob?.cancel()
            savePosition()
        }
    }

    /** Called when the service is going away; the write must not be cancelled with the scope. */
    suspend fun flush() = withContext(NonCancellable) {
        snapshot()?.let { stateStore.save(it) }
    }

    private fun recordPlay() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (mediaId == recordedMediaId) return
        recordedMediaId = mediaId
        val trackId = mediaId.toLongOrNull() ?: return
        scope.launch { history.record(trackId) }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                savePosition()
            }
        }
    }

    private fun saveModes() {
        val shuffle = player.shuffleModeEnabled
        val repeat = player.repeatMode
        scope.launch { stateStore.saveModes(shuffle, repeat) }
    }

    private fun queueIds(): List<Long> =
        (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }

    private fun savePosition() {
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        scope.launch { stateStore.savePosition(index, position) }
    }

    private fun snapshot(): SavedPlayback? {
        if (player.mediaItemCount == 0) return null
        return SavedPlayback(
            trackIds = queueIds(),
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 10_000L
        private const val END_TOLERANCE_MS = 1_500L

        /**
         * A session saved at the very end of a track would "play" and immediately finish;
         * resume such tracks from the beginning instead.
         */
        fun resumePosition(durationMs: Long, savedPositionMs: Long): Long =
            if (durationMs > 0 && savedPositionMs >= durationMs - END_TOLERANCE_MS) 0L else savedPositionMs.coerceAtLeast(0)
    }
}
