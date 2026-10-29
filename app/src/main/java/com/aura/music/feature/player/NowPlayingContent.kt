package com.aura.music.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.Atmosphere
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraSeekBar
import com.aura.music.core.designsystem.components.PlayPauseButton
import com.aura.music.core.designsystem.components.albumLabel
import com.aura.music.core.designsystem.components.artistLabel
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalDecorativeMotion
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.playback.controller.PlaybackPosition
import com.aura.music.playback.controller.PlayerUiState
import com.aura.music.core.model.NowPlayingButton
import com.aura.music.playback.controller.RepeatMode

/** Callbacks of the full player, grouped to keep signatures readable. */
data class NowPlayingActions(
    val onCollapse: () -> Unit,
    val onMore: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onToggleShuffle: () -> Unit,
    val onCycleRepeat: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onOpenArtist: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenDetails: () -> Unit,
    val onToggleLyrics: () -> Unit,
    val onOpenSleepTimer: () -> Unit,
    val onOpenEqualizer: () -> Unit,
)

/** State of the configurable button row under the transport controls. */
data class UtilityState(
    val buttons: List<NowPlayingButton>,
    val lyricsActive: Boolean,
    /** Remaining time or "end of track" when a sleep timer runs. */
    val sleepLabel: String?,
)

/**
 * Full player layout. The artwork slot is an empty spacer: [PlayerSheet] draws the cover there
 * so it can travel continuously from the mini-player.
 */
@Composable
fun NowPlayingContent(
    state: PlayerUiState,
    position: PlaybackPosition,
    isFavorite: Boolean,
    atmosphere: Atmosphere,
    geometry: NowPlayingGeometry,
    topInset: Dp,
    actions: NowPlayingActions,
    utility: UtilityState,
    modifier: Modifier = Modifier,
    centerContent: (@Composable () -> Unit)? = null,
) {
    val track = state.current ?: return
    Column(
        modifier
            .fillMaxSize()
            .padding(top = topInset, start = NowPlayingGeometry.HorizontalPadding, end = NowPlayingGeometry.HorizontalPadding),
    ) {
        Header(albumLabel(track.album), actions)
        val centerHeight = geometry.gapAboveArtwork + geometry.artworkSize + geometry.gapBelowArtwork
        if (centerContent == null) {
            Spacer(Modifier.height(centerHeight))
        } else {
            Box(Modifier.fillMaxWidth().height(centerHeight).padding(top = geometry.gapAboveArtwork / 2)) { centerContent() }
        }
        TitleBlock(track.title, artistLabel(track.artist), isFavorite, atmosphere.accent, geometry.titleBlockHeight, actions)
        Box(Modifier.height(NowPlayingGeometry.SeekHeight), contentAlignment = Alignment.Center) {
            AuraSeekBar(
                positionMs = position.positionMs,
                durationMs = position.durationMs.takeIf { it > 0 } ?: track.durationMs,
                onSeek = actions.onSeek,
                accent = atmosphere.accent,
            )
        }
        Spacer(Modifier.height(Spacing.x3))
        Controls(state, atmosphere, actions)
        Spacer(Modifier.height(Spacing.x4))
        UtilityRow(actions, utility, atmosphere.accent)
    }
}

@Composable
private fun Header(album: String, actions: NowPlayingActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(NowPlayingGeometry.HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraIconButton(AuraIcons.ChevronDown, stringResource(R.string.cd_collapse_player), actions.onCollapse, Modifier.padding(end = Spacing.x1))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.player_now_playing).uppercase(),
                style = AuraTheme.type.overline,
                color = AuraTheme.colors.textSecondary,
            )
            Text(
                album,
                style = AuraTheme.type.bodySmall,
                color = AuraTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        AuraIconButton(AuraIcons.More, stringResource(R.string.cd_more_options, album), actions.onMore, Modifier.padding(start = Spacing.x1))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleBlock(title: String, artist: String, isFavorite: Boolean, accent: Color, height: Dp, actions: NowPlayingActions) {
    val reduced = LocalReducedMotion.current
    val heartScale by animateFloatAsState(if (isFavorite) 1f else 0.92f, Motion.settle(reduced), label = "heart")
    Row(
        Modifier
            .fillMaxWidth()
            .height(height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AuraTheme.type.display,
                color = AuraTheme.colors.textPrimary,
                maxLines = 1,
                modifier = Modifier
                    .semantics { heading() }
                    .then(if (!LocalDecorativeMotion.current) Modifier else Modifier.basicMarquee(initialDelayMillis = 2_000)),
            )
            Text(
                artist,
                style = AuraTheme.type.body,
                color = AuraTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(Radius.small)
                    .clickable(role = Role.Button, onClick = actions.onOpenArtist),
            )
        }
        AuraIconButton(
            icon = if (isFavorite) AuraIcons.HeartFilled else AuraIcons.Heart,
            contentDescription = stringResource(if (isFavorite) R.string.cd_unfavorite else R.string.cd_favorite),
            onClick = actions.onToggleFavorite,
            tint = if (isFavorite) accent else AuraTheme.colors.textSecondary,
            modifier = Modifier.graphicsLayer {
                scaleX = heartScale
                scaleY = heartScale
            },
        )
    }
}

@Composable
private fun Controls(state: PlayerUiState, atmosphere: Atmosphere, actions: NowPlayingActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(NowPlayingGeometry.ControlsHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ModeToggle(
            icon = AuraIcons.Shuffle,
            active = state.shuffle,
            description = stringResource(if (state.shuffle) R.string.cd_shuffle_on else R.string.cd_shuffle_off),
            accent = atmosphere.accent,
            onClick = actions.onToggleShuffle,
        )
        AuraIconButton(AuraIcons.Previous, stringResource(R.string.cd_previous), actions.onPrevious, iconSize = 30.dp)
        PlayPauseButton(
            isPlaying = state.isPlaying,
            onClick = actions.onTogglePlay,
            size = 76.dp,
            container = atmosphere.accent,
            content = atmosphere.onAccent,
        )
        AuraIconButton(AuraIcons.Next, stringResource(R.string.cd_next), actions.onNext, iconSize = 30.dp, enabled = state.hasNext || state.repeatMode != RepeatMode.Off)
        ModeToggle(
            icon = if (state.repeatMode == RepeatMode.One) AuraIcons.RepeatOne else AuraIcons.Repeat,
            active = state.repeatMode != RepeatMode.Off,
            description = stringResource(
                when (state.repeatMode) {
                    RepeatMode.Off -> R.string.cd_repeat_off
                    RepeatMode.All -> R.string.cd_repeat_all
                    RepeatMode.One -> R.string.cd_repeat_one
                },
            ),
            accent = atmosphere.accent,
            onClick = actions.onCycleRepeat,
        )
    }
}

/** Shuffle/repeat: accent tint plus a small dot so state isn't conveyed by color alone. */
@Composable
private fun ModeToggle(icon: ImageVector, active: Boolean, description: String, accent: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AuraIconButton(icon, description, onClick, tint = if (active) accent else AuraTheme.colors.textSecondary, iconSize = 22.dp)
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (active) accent else Color.Transparent),
        )
    }
}

/**
 * The user-chosen secondary buttons. With up to three they carry labels; with more they become
 * icon-only (labels stay as content descriptions) so the row never wraps or truncates.
 */
@Composable
private fun UtilityRow(actions: NowPlayingActions, utility: UtilityState, accent: Color) {
    val compact = utility.buttons.size > 3
    Row(
        Modifier
            .fillMaxWidth()
            .height(NowPlayingGeometry.UtilityHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (utility.buttons.size == 1) Arrangement.Center else Arrangement.SpaceBetween,
    ) {
        utility.buttons.forEach { button ->
            when (button) {
                NowPlayingButton.Lyrics -> UtilityButton(
                    AuraIcons.Lyrics, stringResource(R.string.player_lyrics), actions.onToggleLyrics, compact,
                    active = utility.lyricsActive, accent = accent,
                )
                NowPlayingButton.Queue -> UtilityButton(AuraIcons.Queue, stringResource(R.string.player_queue), actions.onOpenQueue, compact)
                NowPlayingButton.Details -> UtilityButton(AuraIcons.Info, stringResource(R.string.player_details), actions.onOpenDetails, compact)
                NowPlayingButton.SleepTimer -> UtilityButton(
                    AuraIcons.Timer, utility.sleepLabel ?: stringResource(R.string.player_sleep_timer), actions.onOpenSleepTimer,
                    compact && utility.sleepLabel == null, active = utility.sleepLabel != null, accent = accent,
                )
                NowPlayingButton.Equalizer -> UtilityButton(AuraIcons.Equalizer, stringResource(R.string.player_equalizer), actions.onOpenEqualizer, compact)
            }
        }
    }
}

@Composable
private fun UtilityButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    iconOnly: Boolean = false,
    active: Boolean = false,
    accent: Color = AuraTheme.colors.textSecondary,
) {
    val tint = if (active) accent else AuraTheme.colors.textSecondary
    if (iconOnly) {
        AuraIconButton(icon, label, onClick, tint = tint, iconSize = 20.dp)
        return
    }
    Row(
        Modifier
            .clip(Radius.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.x2))
        Text(label, style = AuraTheme.type.label, color = tint, maxLines = 1)
    }
}
