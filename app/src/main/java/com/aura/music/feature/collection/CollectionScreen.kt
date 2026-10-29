package com.aura.music.feature.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.rememberAtmosphere
import com.aura.music.core.designsystem.components.Artwork
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.components.LoadingState
import com.aura.music.core.designsystem.components.TrackRow
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.feature.common.CollapsingCollection
import com.aura.music.feature.common.CollectionTitle
import com.aura.music.feature.common.MixCover
import com.aura.music.feature.common.PlayShuffleRow
import com.aura.music.feature.common.mixTitle
import com.aura.music.navigation.CollectionKind
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalTrackActions

@Composable
fun CollectionScreen(viewModel: CollectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.isLoading) {
        LoadingState()
        return
    }
    val navigator = LocalNavigator.current
    val trackActions = LocalTrackActions.current
    val title = viewModel.title ?: stringResource(viewModel.mix?.let(::mixTitle) ?: R.string.favorites_title)
    val eyebrow = stringResource(
        when (state.kind) {
            CollectionKind.Favorites -> R.string.playlist_label
            CollectionKind.Genre -> R.string.genre_label
            CollectionKind.Folder -> R.string.folder_label
            CollectionKind.Mix -> R.string.mix_label
        },
    )
    val heroSource = state.tracks.firstOrNull()?.artwork
    val atmosphere = rememberAtmosphere(heroSource)

    CollapsingCollection(
        title = title,
        atmosphere = atmosphere,
        onBack = { navigator.back() },
        hero = { size ->
            val mix = viewModel.mix
            if (state.kind == CollectionKind.Favorites) {
                FavoritesCover(Modifier.size(size))
            } else if (mix != null) {
                MixCover(mix, Modifier.size(size))
            } else {
                Artwork(heroSource, Modifier.size(size), shape = Radius.xLarge, placeholderSeed = title, placeholderIcon = state.kind.icon)
            }
        },
    ) {
        item(key = "title") {
            CollectionTitle(
                eyebrow = eyebrow,
                title = title,
                subtitle = pluralStringResource(R.plurals.track_count, state.tracks.size, state.tracks.size),
            )
        }
        if (state.tracks.isEmpty()) {
            item(key = "empty") {
                if (state.kind == CollectionKind.Mix) {
                    EmptyState(AuraIcons.Wave, stringResource(R.string.mix_empty_title), stringResource(R.string.mix_empty_body))
                } else {
                    EmptyState(AuraIcons.Heart, stringResource(R.string.favorites_empty_title), stringResource(R.string.favorites_empty_body))
                }
            }
        } else {
            item(key = "actions") {
                PlayShuffleRow(
                    summary = "",
                    onPlay = { viewModel.play(0) },
                    onShuffle = viewModel::shuffle,
                    accent = atmosphere.accent,
                    onAccent = atmosphere.onAccent,
                )
            }
        }
        itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                track = track,
                onClick = { viewModel.play(index) },
                onMore = { trackActions.open(track) },
                isCurrent = track.id == state.currentTrackId,
                isPlaying = state.isPlaying,
                accent = atmosphere.accent,
                showDuration = true,
            )
        }
    }
}

/** Favorites has no single cover, so it gets a quiet signature tile instead. */
@Composable
private fun FavoritesCover(modifier: Modifier) {
    val colors = AuraTheme.colors
    Box(
        modifier
            .clip(Radius.xLarge)
            .background(Brush.linearGradient(listOf(colors.accent.copy(alpha = 0.35f), colors.surface))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AuraIcons.HeartFilled, contentDescription = null, tint = colors.accent, modifier = Modifier.size(64.dp))
    }
}

private val CollectionKind.icon
    get() = when (this) {
        CollectionKind.Favorites -> AuraIcons.Heart
        CollectionKind.Genre -> AuraIcons.Wave
        CollectionKind.Folder -> AuraIcons.Folder
        CollectionKind.Mix -> AuraIcons.Wave
    }
