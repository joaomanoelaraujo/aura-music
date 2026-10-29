package com.aura.music.playback.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.aura.music.R
import com.aura.music.data.repository.FavoritesRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.guava.future

/**
 * Extra media buttons (favourite, shuffle) exposed through the official Media3 mechanism:
 * media button preferences. Android 13+ System UI renders them in the media controls' extra
 * slots (shade and lock screen); older versions show them as notification actions.
 * No custom notification layouts are used, so platform behaviour stays intact.
 */
@UnstableApi
class SessionButtons(
    private val context: Context,
    private val favorites: FavoritesRepository,
    private val scope: CoroutineScope,
) {
    private val toggleFavorite = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    private val toggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)

    val sessionCommands: SessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(toggleFavorite)
            .add(toggleShuffle)
            .build()

    fun buttonsFor(player: Player): List<CommandButton> {
        val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val isFavorite = trackId != null && trackId in favorites.favoriteIds.value
        return listOf(
            CommandButton.Builder(if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(context.getString(if (isFavorite) R.string.cd_unfavorite else R.string.cd_favorite))
                .setSessionCommand(toggleFavorite)
                .setEnabled(trackId != null)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(context.getString(if (player.shuffleModeEnabled) R.string.cd_shuffle_on else R.string.cd_shuffle_off))
                .setSessionCommand(toggleShuffle)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    /** Returns null when [command] isn't one of these buttons. */
    fun handle(command: SessionCommand, player: Player): ListenableFuture<SessionResult>? = when (command.customAction) {
        ACTION_TOGGLE_FAVORITE -> {
            val trackId = player.currentMediaItem?.mediaId?.toLongOrNull()
            scope.future {
                if (trackId != null) favorites.toggle(trackId)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
        }
        ACTION_TOGGLE_SHUFFLE -> {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        else -> null
    }

    /** Keeps the buttons in sync with the current item, shuffle mode and favourites. */
    fun bind(session: MediaSession) {
        val player = session.player
        val refresh = { session.setMediaButtonPreferences(buttonsFor(player)) }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) refresh()
            }
        })
        favorites.favoriteIds.onEach { refresh() }.launchIn(scope)
    }

    private companion object {
        const val ACTION_TOGGLE_FAVORITE = "com.aura.music.action.TOGGLE_FAVORITE"
        const val ACTION_TOGGLE_SHUFFLE = "com.aura.music.action.TOGGLE_SHUFFLE"
    }
}
