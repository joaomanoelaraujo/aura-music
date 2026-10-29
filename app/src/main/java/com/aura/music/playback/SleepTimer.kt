package com.aura.music.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /** [endsAtElapsed] uses [SystemClock.elapsedRealtime], immune to wall-clock changes. */
    data class Running(val endsAtElapsed: Long, val totalMs: Long) : SleepTimerState {
        fun remainingMs(now: Long = SystemClock.elapsedRealtime()) = (endsAtElapsed - now).coerceAtLeast(0)
    }

    data object EndOfTrack : SleepTimerState
}

/**
 * Pauses playback after a delay (fading out over the last seconds) or at the end of the current
 * track. The UI only changes [state]; the playback service attaches the player and does the work,
 * so the timer keeps running with the app in the background.
 */
@Singleton
class SleepTimer @Inject constructor(private val volume: PlaybackVolume) {

    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var player: Player? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    private val endOfTrackListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && _state.value == SleepTimerState.EndOfTrack) {
                cancel()
            }
        }
    }

    fun start(minutes: Int) {
        val total = minutes * 60_000L
        _state.value = SleepTimerState.Running(SystemClock.elapsedRealtime() + total, total)
        apply()
    }

    fun startEndOfTrack() {
        _state.value = SleepTimerState.EndOfTrack
        apply()
    }

    /** Adds time to a running timer. */
    fun extend(minutes: Int) {
        val running = _state.value as? SleepTimerState.Running ?: return start(minutes)
        _state.value = running.copy(endsAtElapsed = running.endsAtElapsed + minutes * 60_000L, totalMs = running.totalMs + minutes * 60_000L)
        apply()
    }

    fun cancel() {
        _state.value = SleepTimerState.Off
        apply()
    }

    fun attach(player: Player, scope: CoroutineScope) {
        this.player = player
        this.scope = scope
        player.addListener(endOfTrackListener)
        apply()
    }

    fun detach() {
        job?.cancel()
        player?.run {
            removeListener(endOfTrackListener)
            pauseAtEndOfItems(false)
        }
        volume.set(PlaybackVolume.Source.SleepTimer, 1f)
        player = null
        scope = null
    }

    private fun apply() {
        val player = player ?: return
        val scope = scope ?: return
        job?.cancel()
        volume.set(PlaybackVolume.Source.SleepTimer, 1f)
        player.pauseAtEndOfItems(_state.value == SleepTimerState.EndOfTrack)
        val running = _state.value as? SleepTimerState.Running ?: return
        job = scope.launch {
            while (isActive) {
                val remaining = running.remainingMs()
                if (remaining <= 0) break
                if (remaining <= FADE_MS) volume.set(PlaybackVolume.Source.SleepTimer, (remaining.toFloat() / FADE_MS).coerceIn(0.05f, 1f))
                delay(if (remaining <= FADE_MS) FADE_STEP_MS else minOf(remaining - FADE_MS, 1_000L).coerceAtLeast(FADE_STEP_MS))
            }
            player.pause()
            volume.set(PlaybackVolume.Source.SleepTimer, 1f)
            _state.value = SleepTimerState.Off
        }
    }

    @OptIn(UnstableApi::class)
    private fun Player.pauseAtEndOfItems(enabled: Boolean) {
        (this as? androidx.media3.exoplayer.ExoPlayer)?.pauseAtEndOfMediaItems = enabled
    }

    private companion object {
        const val FADE_MS = 10_000L
        const val FADE_STEP_MS = 200L
    }
}
