package com.aura.music.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Custom session events sent from the service to the UI's controller. */
object PlaybackEvents {
    private const val ACTION_TRACK_SKIPPED = "com.aura.music.event.TRACK_SKIPPED"
    private const val KEY_TITLE = "title"

    val TrackSkipped = SessionCommand(ACTION_TRACK_SKIPPED, Bundle.EMPTY)

    fun trackSkippedArgs(title: String) = Bundle().apply { putString(KEY_TITLE, title) }

    fun isTrackSkipped(command: SessionCommand) = command.customAction == ACTION_TRACK_SKIPPED

    fun title(args: Bundle): String = args.getString(KEY_TITLE).orEmpty()
}
