package com.aura.music.playback.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.aura.music.core.model.OutputPreference
import com.aura.music.core.model.PreviousBehavior
import com.aura.music.data.lyrics.EmbeddedLyricsReader
import com.aura.music.data.lyrics.EmbeddedLyricsStore
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.lyrics.LyricsRepository
import com.aura.music.data.metadata.MetadataRepository
import com.aura.music.data.analysis.SongAnalysisRepository
import kotlinx.coroutines.flow.first
import com.aura.music.data.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Publishes lyrics embedded in the current file (read by the extractor anyway) for the UI. */
@UnstableApi
class EmbeddedLyricsTap(private val player: Player, private val store: EmbeddedLyricsStore) : Player.Listener {
    override fun onTracksChanged(tracks: Tracks) {
        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                EmbeddedLyricsReader.read(metadata)?.let {
                    store.put(trackId, it)
                    return
                }
            }
        }
    }
}

/**
 * Looks for the current song's lyrics online once it has played for a moment (so skipping through
 * songs doesn't trigger lookups) and only when it has none of its own: embedded lyrics are known
 * by now, since they arrive with the tracks. Found lyrics are stored, so they work offline later.
 */
@UnstableApi
class OnlineLyricsPrefetch(
    private val player: Player,
    private val library: LibraryRepository,
    private val lyrics: LyricsRepository,
    private val effects: SongAnalysisRepository,
    private val settings: SettingsRepository,
    private val metadata: MetadataRepository,
    private val scope: CoroutineScope,
) : Player.Listener {
    private var job: Job? = null

    override fun onTracksChanged(tracks: Tracks) {
        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        job?.cancel()
        job = scope.launch {
            delay(SETTLE_MS)
            if (player.currentMediaItem?.mediaId?.toLongOrNull() != trackId) return@launch
            val track = library.awaitIndex()?.track(trackId) ?: return@launch
            withContext(Dispatchers.IO) { runCatching { lyrics.fetchOnlineIfMissing(track) } }
            // An album without a cover gets one from MusicBrainz, when allowed.
            withContext(Dispatchers.IO) { runCatching { metadata.fetchCoverIfMissing(track) } }
            // With synced lyrics in place, read how the song moves so its lyric effects are ready.
            if (settings.settings.first().lyricsEffects && lyrics.observe(track).first().lyrics.synced) {
                runCatching { effects.profile(track) }
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}

/**
 * Applies "previous" preferences to every controller (app, notification, headset, car):
 * either restart the track first (Media3's default, after 3 s) or always jump back.
 */
@UnstableApi
class AuraForwardingPlayer(player: Player, private val previousBehavior: () -> PreviousBehavior) : ForwardingPlayer(player) {
    override fun seekToPrevious() {
        if (previousBehavior() == PreviousBehavior.AlwaysPrevious && hasPreviousMediaItem()) {
            seekToPreviousMediaItem()
        } else {
            super.seekToPrevious()
        }
    }
}

/**
 * Routes audio to the preferred output when it is connected and, if enabled, resumes playback
 * when headphones come back after Android paused it for "audio becoming noisy".
 */
@UnstableApi
class AudioOutputController(
    context: Context,
    private val player: ExoPlayer,
    private val preference: () -> OutputPreference,
    private val resumeOnConnect: () -> Boolean,
) : Player.Listener {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var pausedByDisconnectAt = 0L

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            applyPreference()
            if (addedDevices.any { it.isHeadphones() } && resumeOnConnect() && pausedByDisconnectAt > 0 &&
                SystemClock.elapsedRealtime() - pausedByDisconnectAt < RESUME_WINDOW_MS
            ) {
                pausedByDisconnectAt = 0
                // Give the route a moment to settle so the first notes don't go to the speaker.
                handler.postDelayed({ if (!player.playWhenReady) player.play() }, ROUTE_SETTLE_MS)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = applyPreference()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, handler)
        applyPreference()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
        handler.removeCallbacksAndMessages(null)
    }

    fun applyPreference() {
        val wanted = preference()
        val device = if (wanted == OutputPreference.Auto) {
            null
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.matches(wanted) }
        }
        player.setPreferredAudioDevice(device)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        pausedByDisconnectAt = when {
            !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> SystemClock.elapsedRealtime()
            playWhenReady -> 0
            else -> pausedByDisconnectAt
        }
    }

    private fun AudioDeviceInfo.isHeadphones(): Boolean = type in HEADPHONE_TYPES

    private fun AudioDeviceInfo.matches(preference: OutputPreference): Boolean = when (preference) {
        OutputPreference.Auto -> false
        OutputPreference.Speaker -> type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        OutputPreference.Wired -> type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        OutputPreference.Bluetooth -> type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type in BLE_TYPES
        OutputPreference.Usb -> type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE
    }

    private companion object {
        const val RESUME_WINDOW_MS = 30 * 60_000L
        const val ROUTE_SETTLE_MS = 600L
        val BLE_TYPES = setOf(26, 27) // TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER (API 31)
        val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            26,
        )
    }
}
