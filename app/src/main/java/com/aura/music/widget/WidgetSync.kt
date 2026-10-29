package com.aura.music.widget

import android.content.Context
import androidx.media3.common.Player
import com.aura.music.playback.queue.MediaItemMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mirrors the player into the widget state. Runs inside the playback service, so widgets stay
 * correct without the UI. Updates are event-driven and debounced; while audio plays, progress is
 * refreshed only every [PROGRESS_INTERVAL_MS] and only if a widget is actually on a home screen.
 */
class WidgetSync(
    private val context: Context,
    private val player: Player,
    private val store: WidgetStateStore,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var pending: Job? = null
    private var ticker: Job? = null

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(*RELEVANT_EVENTS)) schedule()
    }

    fun start() = schedule()

    /** Final, non-playing state when the service goes away. */
    suspend fun flushStopped() = withContext(NonCancellable) {
        ticker?.cancel()
        pending?.cancel()
        publish(snapshot().copy(isPlaying = false))
    }

    private fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            val snapshot = snapshot()
            publish(snapshot)
            updateTicker(snapshot.isPlaying)
        }
    }

    private suspend fun publish(snapshot: WidgetSnapshot) {
        store.write(snapshot)
        AuraWidgets.updateAll(context)
    }

    private suspend fun updateTicker(isPlaying: Boolean) {
        ticker?.cancel()
        if (!isPlaying || !AuraWidgets.anyPlaced(context)) return
        ticker = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                publish(snapshot())
            }
        }
    }

    private fun snapshot(): WidgetSnapshot {
        val item = player.currentMediaItem ?: return WidgetSnapshot.Empty.copy(capturedAt = System.currentTimeMillis())
        val metadata = item.mediaMetadata
        val artwork = MediaItemMapper.artworkSource(metadata)
        return WidgetSnapshot(
            hasMedia = true,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString(),
            isPlaying = player.playWhenReady &&
                player.playbackState != Player.STATE_ENDED &&
                player.playbackState != Player.STATE_IDLE,
            albumId = artwork?.albumId ?: -1,
            trackUri = artwork?.trackUri.orEmpty(),
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0),
            capturedAt = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val PROGRESS_INTERVAL_MS = 30_000L
        val RELEVANT_EVENTS = intArrayOf(
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_MEDIA_METADATA_CHANGED,
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_POSITION_DISCONTINUITY,
        )
    }
}
