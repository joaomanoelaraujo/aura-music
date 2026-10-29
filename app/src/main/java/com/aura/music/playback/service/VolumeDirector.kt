package com.aura.music.playback.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aura.music.data.analysis.SongAnalysisRepository
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.playback.PlaybackVolume
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The arithmetic of even volume and smooth transitions, kept apart from the player to be testable. */
object VolumePlan {
    /** Songs louder than this are turned down to it; quieter ones are left alone (no boosting, no clipping). */
    const val TARGET_DB = -16f
    private const val MAX_CUT_DB = 12f

    fun normalization(loudnessDb: Float?): Float {
        if (loudnessDb == null) return 1f
        val cut = (TARGET_DB - loudnessDb).coerceIn(-MAX_CUT_DB, 0f)
        return 10f.pow(cut / 20f)
    }

    /**
     * Volume factor for a smooth transition: rising over the first [fadeMs] when the song started
     * by itself after the previous one, falling over the last [fadeMs] when another song follows.
     * Equal-power curves keep the perceived loudness steady through the change.
     */
    fun transition(positionMs: Long, durationMs: Long, fadeMs: Long, fadeIn: Boolean, hasNext: Boolean): Float {
        if (fadeMs <= 0) return 1f
        val rising = if (fadeIn) curve(positionMs.toFloat() / fadeMs) else 1f
        val falling = if (hasNext && durationMs > 0) curve((durationMs - positionMs).toFloat() / fadeMs) else 1f
        return minOf(rising, falling)
    }

    private fun curve(x: Float): Float = if (x >= 1f) 1f else sin(x.coerceAtLeast(0f) * PI / 2).toFloat()
}

/**
 * Plays every song at a similar volume (from its analysis) and, when enabled, fades songs into each
 * other. A song not analyzed yet plays as it is and gets analyzed for next time; the next song in
 * the queue is analyzed ahead, so transitions are ready when they come.
 */
class VolumeDirector(
    private val player: Player,
    private val volume: PlaybackVolume,
    private val library: LibraryRepository,
    private val analysis: SongAnalysisRepository,
    settings: SettingsRepository,
    private val scope: CoroutineScope,
) : Player.Listener {
    private var normalize = true
    private var fadeMs = 0L
    private var fadeIn = false
    private var lookup: Job? = null
    private var ticker: Job? = null

    init {
        settings.settings.map { it.normalizeVolume to it.transitionSeconds }.distinctUntilChanged().let { flow ->
            scope.launch {
                flow.collect { (norm, seconds) ->
                    normalize = norm
                    fadeMs = seconds * 1000L
                    applyNormalization(player.currentMediaItem)
                    restartTicker()
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Only songs that follow by themselves fade in; a song the user picks starts at full volume.
        fadeIn = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        applyNormalization(mediaItem)
        prepareNext()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = restartTicker()

    private fun applyNormalization(item: MediaItem?) {
        lookup?.cancel()
        val id = item?.mediaId?.toLongOrNull()
        if (!normalize || id == null) {
            volume.set(PlaybackVolume.Source.Normalization, 1f)
            return
        }
        lookup = scope.launch {
            val track = library.awaitIndex()?.track(id)
            val summary = track?.let { withContext(Dispatchers.IO) { analysis.summary(it) } }
            // Unknown loudness: play as is now, never change the volume in the middle of a song.
            volume.set(PlaybackVolume.Source.Normalization, VolumePlan.normalization(summary?.takeUnless { it.failed }?.loudnessDb))
            if (summary == null && track != null) launch(Dispatchers.IO) { runCatching { analysis.profile(track) } }
        }
    }

    private fun prepareNext() {
        val index = player.nextMediaItemIndex.takeIf { it >= 0 } ?: return
        val id = player.getMediaItemAt(index).mediaId.toLongOrNull() ?: return
        scope.launch(Dispatchers.IO) {
            val track = library.awaitIndex()?.track(id) ?: return@launch
            if (analysis.needsAnalysis(track)) runCatching { analysis.profile(track) }
        }
    }

    private fun restartTicker() {
        ticker?.cancel()
        if (fadeMs <= 0 || !player.isPlaying) {
            volume.set(PlaybackVolume.Source.Transition, 1f)
            return
        }
        ticker = scope.launch {
            while (isActive) {
                val position = player.currentPosition
                val duration = player.duration
                val factor = VolumePlan.transition(position, duration, fadeMs, fadeIn, player.hasNextMediaItem())
                volume.set(PlaybackVolume.Source.Transition, factor)
                val inFade = factor < 1f || (duration > 0 && duration - position < fadeMs + 1_000)
                delay(if (inFade) FADE_TICK_MS else IDLE_TICK_MS)
            }
        }
    }

    private companion object {
        const val FADE_TICK_MS = 40L
        const val IDLE_TICK_MS = 500L
    }
}
