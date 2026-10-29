package com.aura.music.feature.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.rememberAtmosphere
import com.aura.music.core.designsystem.components.AlbumGridItem
import com.aura.music.core.designsystem.components.ArtistArtwork
import com.aura.music.core.designsystem.components.LoadingState
import com.aura.music.core.designsystem.components.SectionHeader
import com.aura.music.core.designsystem.components.TrackRow
import com.aura.music.core.designsystem.components.artistLabel
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.feature.common.CollapsingCollection
import com.aura.music.feature.common.CollectionTitle
import com.aura.music.feature.common.NotFound
import com.aura.music.feature.common.PlayShuffleRow
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalTrackActions

@Composable
fun ArtistScreen(viewModel: ArtistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        ArtistUiState.Loading -> LoadingState()
        ArtistUiState.NotFound -> NotFound()
        is ArtistUiState.Ready -> ArtistContent(current, viewModel)
    }
}

@Composable
private fun ArtistContent(state: ArtistUiState.Ready, viewModel: ArtistViewModel) {
    val navigator = LocalNavigator.current
    val trackActions = LocalTrackActions.current
    val artist = state.artist
    val atmosphere = rememberAtmosphere(artist.artwork)
    val name = artistLabel(artist.name)
    CollapsingCollection(
        title = name,
        atmosphere = atmosphere,
        onBack = { navigator.back() },
        hero = { size -> ArtistArtwork(artist.artwork, artist.name, Modifier.size(size * 0.82f)) },
    ) {
        item(key = "title") {
            CollectionTitle(
                eyebrow = stringResource(R.string.artist_label),
                title = name,
                subtitle = pluralStringResource(R.plurals.album_count, state.albums.size, state.albums.size) +
                    stringResource(R.string.dot_separator) +
                    pluralStringResource(R.plurals.track_count, artist.trackCount, artist.trackCount),
            )
        }
        item(key = "actions") {
            PlayShuffleRow(
                summary = "",
                onPlay = { viewModel.play(state.tracks, 0) },
                onShuffle = { viewModel.shuffle(state.tracks) },
                accent = atmosphere.accent,
                onAccent = atmosphere.onAccent,
            )
        }
        if (state.albums.isNotEmpty()) {
            item(key = "albumsHeader") { SectionHeader(stringResource(R.string.artist_albums)) }
            item(key = "albums") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
                ) {
                    items(state.albums, key = { it.id }) { album ->
                        AlbumGridItem(album, onClick = { navigator.openAlbum(album.id) }, modifier = Modifier.width(148.dp))
                    }
                }
            }
        }
        item(key = "tracksHeader") { SectionHeader(stringResource(R.string.artist_top_tracks)) }
        itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                track = track,
                onClick = { viewModel.play(state.tracks, index) },
                onMore = { trackActions.open(track) },
                isCurrent = track.id == state.currentTrackId,
                isPlaying = state.isPlaying,
                accent = atmosphere.accent,
                showDuration = true,
            )
        }
    }
}
