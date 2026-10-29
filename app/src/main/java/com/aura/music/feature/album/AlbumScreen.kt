package com.aura.music.feature.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.common.totalMinutes
import com.aura.music.core.designsystem.atmosphere.Atmosphere
import com.aura.music.core.designsystem.atmosphere.rememberAtmosphere
import com.aura.music.core.designsystem.components.Artwork
import com.aura.music.core.designsystem.components.LoadingState
import com.aura.music.core.designsystem.components.TrackLeading
import com.aura.music.core.designsystem.components.TrackRow
import com.aura.music.core.designsystem.components.albumLabel
import com.aura.music.core.designsystem.components.artistLabel
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.feature.common.CollapsingCollection
import com.aura.music.feature.common.NotFound
import com.aura.music.feature.common.PlayShuffleRow
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalTrackActions

@Composable
fun AlbumScreen(viewModel: AlbumViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        AlbumUiState.Loading -> LoadingState()
        AlbumUiState.NotFound -> NotFound()
        is AlbumUiState.Ready -> AlbumContent(current, viewModel)
    }
}

@Composable
private fun AlbumContent(state: AlbumUiState.Ready, viewModel: AlbumViewModel) {
    val navigator = LocalNavigator.current
    val trackActions = LocalTrackActions.current
    val album = state.album
    val atmosphere = rememberAtmosphere(album.artwork)
    CollapsingCollection(
        title = albumLabel(album.title),
        atmosphere = atmosphere,
        onBack = { navigator.back() },
        hero = { size ->
            Artwork(album.artwork, Modifier.size(size), shape = Radius.xLarge, placeholderSeed = album.title, placeholderIcon = AuraIcons.Album)
        },
    ) {
        item(key = "title") {
            AlbumTitle(state, atmosphere, onArtist = { navigator.openArtist(album.artistId) })
        }
        item(key = "actions") {
            PlayShuffleRow(
                summary = pluralStringResource(R.plurals.track_count, album.trackCount, album.trackCount) +
                    stringResource(R.string.dot_separator) +
                    stringResource(R.string.minutes_short, totalMinutes(album.durationMs)),
                onPlay = { viewModel.play(state.tracks, 0) },
                onShuffle = { viewModel.shuffle(state.tracks) },
                accent = atmosphere.accent,
                onAccent = atmosphere.onAccent,
            )
        }
        itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
            val previousDisc = state.tracks.getOrNull(index - 1)?.discNumber
            if (state.hasMultipleDiscs && previousDisc != track.discNumber) {
                Text(
                    stringResource(R.string.disc_number, track.discNumber).uppercase(),
                    style = AuraTheme.type.overline,
                    color = AuraTheme.colors.textTertiary,
                    modifier = Modifier.padding(start = Spacing.gutter, top = Spacing.x4, bottom = Spacing.x1),
                )
            }
            TrackRow(
                track = track,
                onClick = { viewModel.play(state.tracks, index) },
                onMore = { trackActions.open(track) },
                isCurrent = track.id == state.currentTrackId,
                isPlaying = state.isPlaying,
                leading = TrackLeading.Number,
                accent = atmosphere.accent,
                showDuration = true,
            )
        }
    }
}

@Composable
private fun AlbumTitle(state: AlbumUiState.Ready, atmosphere: Atmosphere, onArtist: () -> Unit) {
    val album = state.album
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.album_label).uppercase(),
            style = AuraTheme.type.overline,
            color = AuraTheme.colors.textSecondary,
        )
        Text(
            albumLabel(album.title),
            style = AuraTheme.type.display,
            color = AuraTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.x1),
        )
        Text(
            artistLabel(album.artist),
            style = AuraTheme.type.titleSmall,
            color = atmosphere.accent,
            modifier = Modifier
                .padding(top = Spacing.x2)
                .clip(Radius.small)
                .clickable(role = Role.Button, onClick = onArtist)
                .padding(horizontal = Spacing.x2, vertical = Spacing.x1),
        )
        album.year?.let {
            Text(it.toString(), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
        }
    }
}
