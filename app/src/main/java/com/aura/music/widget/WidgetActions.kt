package com.aura.music.widget

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aura.music.playback.service.AuraPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends one command to the playback service through a short-lived MediaController, so widget
 * buttons work without the app's UI. If the service was just started, it waits briefly for the
 * last session to be restored before acting.
 */
internal object WidgetCommands {
    private const val TAG = "WidgetCommands"
    private const val RESTORE_WAIT_MS = 4_000L
    private const val POLL_MS = 100L
    private const val SETTLE_MS = 300L

    @OptIn(UnstableApi::class)
    suspend fun send(context: Context, block: MediaController.() -> Unit) = withContext(Dispatchers.Main) {
        // Callbacks may receive a BroadcastReceiver context, which is not allowed to bind services.
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, AuraPlaybackService::class.java))
        val controller = runCatching { MediaController.Builder(appContext, token).buildAsync().await() }
            .onFailure { Log.w(TAG, "Could not reach the playback service", it) }
            .getOrNull() ?: return@withContext
        try {
            if (controller.mediaItemCount == 0) {
                withTimeoutOrNull(RESTORE_WAIT_MS) {
                    while (controller.mediaItemCount == 0) delay(POLL_MS)
                }
            }
            if (controller.mediaItemCount > 0) controller.block()
            delay(SETTLE_MS)
        } finally {
            controller.release()
        }
    }

    fun MediaController.togglePlayback() {
        when {
            playbackState == Player.STATE_IDLE -> prepare().also { play() }
            playbackState == Player.STATE_ENDED -> seekToDefaultPosition(0).also { play() }
            playWhenReady -> pause()
            else -> play()
        }
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetCommands.send(context) { with(WidgetCommands) { togglePlayback() } }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetCommands.send(context) { seekToNext() }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetCommands.send(context) { seekToPrevious() }
}
