package com.aura.music.core.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.common.formatDuration
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalDecorativeMotion
import com.aura.music.core.designsystem.theme.LocalListDensity
import com.aura.music.core.model.ListDensity
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Sizes
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.Album
import com.aura.music.core.model.Artist
import com.aura.music.core.model.Track

@Composable
fun artistLabel(name: String?): String = name ?: stringResource(R.string.unknown_artist)

@Composable
fun albumLabel(title: String?): String = title ?: stringResource(R.string.unknown_album)

/** Generic two-line list row used across the app for consistent rhythm. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AuraListRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    titleColor: Color = AuraTheme.colors.textPrimary,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val compact = LocalListDensity.current == ListDensity.Compact
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 54.dp else 64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = Spacing.gutter, end = if (trailing != null) Spacing.x2 else Spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f).padding(vertical = if (compact) Spacing.x1 else Spacing.x2)) {
            Text(title, style = AuraTheme.type.titleSmall, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = AuraTheme.type.bodySmall,
                    color = AuraTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}

enum class TrackLeading { Artwork, Number, None }

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    leading: TrackLeading = TrackLeading.Artwork,
    accent: Color = AuraTheme.colors.accent,
    showDuration: Boolean = false,
    onMore: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val subtitle = buildString {
        append(artistLabel(track.artist))
        if (showDuration) append("  ·  ").append(formatDuration(track.durationMs))
    }
    AuraListRow(
        title = track.title,
        subtitle = subtitle,
        onClick = onClick,
        onLongClick = onMore,
        modifier = modifier,
        titleColor = if (isCurrent) accent else AuraTheme.colors.textPrimary,
        leading = when (leading) {
            TrackLeading.Artwork -> {
                { TrackArtwork(track, isCurrent, isPlaying, accent) }
            }
            TrackLeading.Number -> {
                { TrackNumber(track.trackNumber, isCurrent, isPlaying, accent) }
            }
            TrackLeading.None -> null
        },
        trailing = trailing ?: onMore?.let { more ->
            {
                AuraIconButton(
                    icon = AuraIcons.More,
                    contentDescription = stringResource(R.string.cd_more_options, track.title),
                    onClick = more,
                    tint = AuraTheme.colors.textSecondary,
                    iconSize = Sizes.iconSmall,
                    scaleOnPress = false,
                )
            }
        },
    )
}

@Composable
private fun TrackArtwork(track: Track, isCurrent: Boolean, isPlaying: Boolean, accent: Color) {
    Box(Modifier.size(Sizes.rowArtwork), contentAlignment = Alignment.Center) {
        Artwork(track.artwork, Modifier.fillMaxSize(), shape = Radius.small, placeholderSeed = track.album)
        if (isCurrent) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(Radius.small)
                    .background(AuraTheme.colors.background.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) { NowPlayingBars(isPlaying, accent) }
        }
    }
}

@Composable
private fun TrackNumber(number: Int, isCurrent: Boolean, isPlaying: Boolean, accent: Color) {
    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
        if (isCurrent) {
            NowPlayingBars(isPlaying, accent)
        } else {
            Text(
                text = if (number > 0) number.toString() else "–",
                style = AuraTheme.type.numeric,
                color = AuraTheme.colors.textTertiary,
            )
        }
    }
}

/** Three bars that breathe while audio plays and rest when paused or when motion is reduced. */
@Composable
fun NowPlayingBars(isPlaying: Boolean, color: Color, modifier: Modifier = Modifier, barWidth: Dp = 3.dp) {
    val animate = isPlaying && LocalDecorativeMotion.current
    val transition = rememberInfiniteTransition(label = "bars")
    val heights = listOf(0.55f to 520, 0.9f to 430, 0.7f to 610).map { (rest, period) ->
        if (animate) {
            transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(period), RepeatMode.Reverse),
                label = "bar",
            ).value
        } else {
            rest * 0.6f
        }
    }
    Row(
        modifier = modifier.size(16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { fraction ->
            Box(
                Modifier
                    .width(barWidth)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

@Composable
fun AlbumGridItem(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.cardClickable(onClick).padding(bottom = Spacing.x2)) {
        Artwork(
            album.artwork,
            Modifier.fillMaxWidth().aspectRatio(1f),
            shape = Radius.medium,
            placeholderSeed = album.title,
            placeholderIcon = AuraIcons.Album,
        )
        Text(
            albumLabel(album.title),
            style = AuraTheme.type.titleSmall,
            color = AuraTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.x2),
        )
        Text(
            artistLabel(album.artist),
            style = AuraTheme.type.bodySmall,
            color = AuraTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AlbumRow(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val subtitle = listOfNotNull(artistLabel(album.artist), album.year?.toString()).joinToString("  ·  ")
    AuraListRow(
        title = albumLabel(album.title),
        subtitle = subtitle,
        onClick = onClick,
        modifier = modifier,
        leading = {
            Artwork(album.artwork, Modifier.size(56.dp), placeholderSeed = album.title, placeholderIcon = AuraIcons.Album)
        },
    )
}

@Composable
fun ArtistRow(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AuraListRow(
        title = artistLabel(artist.name),
        subtitle = pluralStringResource(R.plurals.track_count, artist.trackCount, artist.trackCount),
        onClick = onClick,
        modifier = modifier,
        leading = { ArtistArtwork(artist.artwork, artist.name, Modifier.size(52.dp)) },
    )
}

@Composable
fun ArtistGridItem(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.cardClickable(onClick).padding(Spacing.x1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtistArtwork(artist.artwork, artist.name, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            artistLabel(artist.name),
            style = AuraTheme.type.titleSmall,
            color = AuraTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.x2),
        )
        Text(
            pluralStringResource(R.plurals.track_count, artist.trackCount, artist.trackCount),
            style = AuraTheme.type.bodySmall,
            color = AuraTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * Click handling for artwork cards: a gentle press-scale instead of a clipped ripple, so the
 * card's text is never cut by rounded corners.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.cardClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return pressScale(interaction, pressedScale = 0.97f)
        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
}
