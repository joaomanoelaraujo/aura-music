package com.aura.music.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Short, non-blocking confirmations ("Added to queue"). */
@Stable
class Messenger(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
}

/** A screen-specific action appended to the shared track sheet (e.g. "Remove from playlist"). */
data class ExtraTrackAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Opens the shared track sheets from any screen. */
interface TrackActions {
    fun open(track: Track, extras: List<ExtraTrackAction> = emptyList())
    fun addToPlaylist(tracks: List<Track>)
    fun showDetails(track: Track)
}

/** Lets any screen open or close the player (e.g. "Continue listening" on Home). */
interface PlayerSheetController {
    fun expand()
    fun collapse()
}

val LocalMessenger = staticCompositionLocalOf<Messenger> { error("Messenger not provided") }
val LocalTrackActions = staticCompositionLocalOf<TrackActions> { error("TrackActions not provided") }
val LocalPlayerSheet = staticCompositionLocalOf<PlayerSheetController> { error("PlayerSheet not provided") }

/** Space reserved at the bottom of scrolling content for the mini-player and navigation bar. */
val LocalBottomInset = compositionLocalOf { 0.dp }
