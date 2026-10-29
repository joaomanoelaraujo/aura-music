package com.aura.music.playback.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import coil3.ImageLoader
import com.aura.music.MainActivity
import com.aura.music.R
import com.aura.music.data.lyrics.LyricsRepository
import com.aura.music.data.metadata.MetadataRepository
import com.aura.music.data.analysis.SongAnalysisRepository
import com.aura.music.playback.PlaybackEvents
import com.aura.music.playback.KaraokeController
import com.aura.music.playback.PlaybackVolume
import com.aura.music.playback.stream.StreamAwareDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.aura.music.widget.WidgetStateStore
import com.aura.music.widget.WidgetSync
import com.aura.music.data.local.PlaybackStateStore
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.repository.FavoritesRepository
import com.aura.music.data.repository.HistoryRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.local.AppSettings
import com.aura.music.data.lyrics.EmbeddedLyricsStore
import com.aura.music.playback.EqualizerController
import com.aura.music.playback.SleepTimer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the player for the whole app. Playback lives here, not in any screen, so it survives
 * the UI being destroyed and integrates with notifications, lock screen and headset buttons.
 */
@UnstableApi
@AndroidEntryPoint
class AuraPlaybackService : MediaLibraryService() {

    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var stateStore: PlaybackStateStore
    @Inject lateinit var history: HistoryRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var favorites: FavoritesRepository
    @Inject lateinit var widgetState: WidgetStateStore
    @Inject lateinit var embeddedLyrics: EmbeddedLyricsStore
    @Inject lateinit var lyrics: LyricsRepository
    @Inject lateinit var lyricsFx: SongAnalysisRepository
    @Inject lateinit var playbackVolume: PlaybackVolume
    @Inject lateinit var karaoke: KaraokeController
    @Inject lateinit var metadata: MetadataRepository
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var equalizer: EqualizerController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var persistence: PlaybackPersistence
    private lateinit var widgetSync: WidgetSync
    private lateinit var outputs: AudioOutputController

    /** Latest settings, read synchronously by player callbacks. */
    @Volatile private var current = AppSettings()

    override fun onCreate() {
        super.onCreate()
        player = buildPlayer()
        persistence = PlaybackPersistence(player, stateStore, history, library, scope)
        player.addListener(persistence)
        player.addListener(ErrorRecovery())
        widgetSync = WidgetSync(this, player, widgetState, scope)
        player.addListener(widgetSync)
        widgetSync.start()
        player.addListener(EmbeddedLyricsTap(player, embeddedLyrics))
        player.addListener(OnlineLyricsPrefetch(player, library, lyrics, lyricsFx, settings, metadata, scope))
        outputs = AudioOutputController(this, player, { current.outputPreference }, { current.resumeOnConnect })
        player.addListener(outputs)
        outputs.start()
        playbackVolume.attach(player)
        player.addListener(VolumeDirector(player, playbackVolume, library, lyricsFx, settings, scope))
        sleepTimer.attach(player, scope)
        val sessionPlayer = AuraForwardingPlayer(player) { current.previousBehavior }
        val buttons = SessionButtons(this, favorites, scope)
        val librarySession = MediaLibrarySession.Builder(this, sessionPlayer, LibrarySessionCallback(library, stateStore, settings, buttons, scope, cacheDir))
            .setSessionActivity(sessionActivity())
            .setBitmapLoader(ArtworkBitmapLoader(this, imageLoader, scope))
            .build()
        session = librarySession
        buttons.bind(librarySession)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )
        observeSettings()
        scope.launch {
            if (settings.settings.first().resumeLastSession) persistence.restore()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Bounded so a slow disk can never turn service shutdown into an ANR.
        runBlocking {
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
                persistence.flush()
                widgetSync.flushStopped()
            }
        }
        sleepTimer.detach()
        playbackVolume.detach()
        outputs.stop()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(this, AuraRenderersFactory())
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        // Jam songs still arriving from another phone play while they download.
        .setMediaSourceFactory(DefaultMediaSourceFactory(StreamAwareDataSource.Factory(this)))
        .build()

    /** Puts karaoke and AURA's equalizer into the audio pipeline, ahead of the speed/pitch stage. */
    private inner class AuraRenderersFactory : DefaultRenderersFactory(this) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(karaoke.processor, equalizer.processor))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
    }

    private fun observeSettings() {
        settings.settings
            .onEach {
                val outputChanged = it.outputPreference != current.outputPreference
                current = it
                player.skipSilenceEnabled = it.skipSilence
                if (outputChanged) outputs.applyPreference()
            }
            .launchIn(scope)
        settings.settings
            .distinctUntilChanged { a, b -> a.pauseOnDisconnect == b.pauseOnDisconnect }
            .onEach { player.setHandleAudioBecomingNoisy(it.pauseOnDisconnect) }
            .launchIn(scope)
    }

    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Files can disappear while queued (deleted, SD card removed): those items are skipped and the
     * UI is told which one. Any other failure (audio output, decoder glitches) only pauses, so the
     * queue is never destroyed by problems unrelated to the file itself.
     */
    private inner class ErrorRecovery : Player.Listener {
        private var consecutiveSkips = 0

        override fun onPlayerError(error: PlaybackException) {
            val failedIndex = player.currentMediaItemIndex
            val title = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
            if (!error.isFileProblem() || consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
                Log.w(TAG, "Playback error, pausing", error)
                consecutiveSkips = 0
                player.pause()
                return
            }
            Log.w(TAG, "Unplayable file, skipping", error)
            consecutiveSkips++
            session?.broadcastCustomCommand(PlaybackEvents.TrackSkipped, PlaybackEvents.trackSkippedArgs(title))
            if (player.mediaItemCount <= 1) {
                player.clearMediaItems()
                return
            }
            val wasLast = failedIndex == player.mediaItemCount - 1
            player.removeMediaItem(failedIndex)
            // At the end of a non-repeating queue, stop instead of replaying the previous track.
            if (wasLast && player.repeatMode == Player.REPEAT_MODE_OFF) player.pause()
            player.prepare()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveSkips = 0
        }

        private fun PlaybackException.isFileProblem(): Boolean =
            errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED until PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
    }

    private companion object {
        const val TAG = "AuraPlaybackService"
        const val FLUSH_TIMEOUT_MS = 1_500L
        const val MAX_CONSECUTIVE_SKIPS = 5
    }
}
