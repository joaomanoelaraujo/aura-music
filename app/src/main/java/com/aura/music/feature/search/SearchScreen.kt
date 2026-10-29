package com.aura.music.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.components.AlbumGridItem
import com.aura.music.core.designsystem.components.ArtistGridItem
import com.aura.music.core.designsystem.components.Artwork
import com.aura.music.core.designsystem.components.AuraChip
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraListRow
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.components.PageTitle
import com.aura.music.core.designsystem.components.SearchField
import com.aura.music.core.designsystem.components.SectionHeader
import com.aura.music.core.designsystem.components.TrackRow
import com.aura.music.core.designsystem.components.albumLabel
import com.aura.music.core.designsystem.components.artistLabel
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.Track
import com.aura.music.data.search.SearchFilter
import com.aura.music.data.search.TopResult
import com.aura.music.feature.common.LibraryGate
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalBottomInset
import com.aura.music.ui.LocalTrackActions

@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (state.query.isEmpty()) focus.requestFocus() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        PageTitle(stringResource(R.string.nav_search), Modifier.padding(top = Spacing.x4))
        SearchField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = stringResource(R.string.search_placeholder),
            onSearch = {
                viewModel.commitQuery()
                focusManager.clearFocus()
            },
            modifier = Modifier
                .padding(horizontal = Spacing.gutter, vertical = Spacing.x2)
                .focusRequester(focus),
        )
        if (state.query.isNotBlank()) FilterRow(state.filter, viewModel::setFilter)
        LibraryGate(state.status, viewModel::refresh) {
            when {
                state.query.isBlank() -> DiscoverList(state, viewModel)
                state.results.isEmpty && state.results.suggestions.isEmpty() -> Box(Modifier.fillMaxSize()) {
                    EmptyState(
                        AuraIcons.Search,
                        stringResource(R.string.search_no_results_title),
                        stringResource(R.string.search_no_results_body, state.query.trim()),
                    )
                }
                else -> ResultsList(state, viewModel)
            }
        }
    }
}

@Composable
private fun FilterRow(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x1),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        SearchFilter.entries.forEach { filter ->
            AuraChip(stringResource(filter.label), filter == selected, onClick = { onSelect(filter) })
        }
    }
}

private val SearchFilter.label: Int
    get() = when (this) {
        SearchFilter.All -> R.string.search_filter_all
        SearchFilter.Songs -> R.string.library_songs
        SearchFilter.Artists -> R.string.library_artists
        SearchFilter.Albums -> R.string.library_albums
        SearchFilter.Playlists -> R.string.library_playlists
        SearchFilter.Audios -> R.string.library_audios
    }

@Composable
private fun DiscoverList(state: SearchUiState, viewModel: SearchViewModel) {
    val navigator = LocalNavigator.current
    val trackActions = LocalTrackActions.current
    val discover = state.discover
    val hasAnything = discover.recentSearches.isNotEmpty() || discover.usualTracks.isNotEmpty() ||
        discover.topArtists.isNotEmpty() || discover.recentAlbums.isNotEmpty() || discover.recentlyAdded.isNotEmpty()
    if (!hasAnything) {
        Box(Modifier.fillMaxSize()) {
            EmptyState(AuraIcons.Search, stringResource(R.string.search_hint_title), stringResource(R.string.search_hint_body))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x6)) {
        if (discover.recentSearches.isNotEmpty()) {
            item(key = "recentHeader") {
                SectionHeader(stringResource(R.string.search_recent), actionLabel = stringResource(R.string.search_clear_recent), onAction = viewModel::clearRecent)
            }
            item(key = "recent") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                ) {
                    discover.recentSearches.forEach { text ->
                        RecentChip(text, onClick = { viewModel.setQuery(text) }, onRemove = { viewModel.removeRecent(text) })
                    }
                }
            }
        }
        trackShelf("usual", R.string.search_usual, discover.usualTracks, state, viewModel, trackActions::open)
        if (discover.topArtists.isNotEmpty()) {
            item(key = "artistsHeader") { SectionHeader(stringResource(R.string.search_favorite_artists)) }
            item(key = "artists") {
                LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                    items(discover.topArtists, key = { it.id }) { artist ->
                        ArtistGridItem(artist, onClick = { navigator.openArtist(artist.id) }, modifier = Modifier.width(104.dp))
                    }
                }
            }
        }
        if (discover.recentAlbums.isNotEmpty()) {
            item(key = "albumsHeader") { SectionHeader(stringResource(R.string.search_recent_albums)) }
            item(key = "albums") {
                LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.x4)) {
                    items(discover.recentAlbums, key = { it.id }) { album ->
                        AlbumGridItem(album, onClick = { navigator.openAlbum(album.id) }, modifier = Modifier.width(140.dp))
                    }
                }
            }
        }
        if (discover.frequentPlaylists.isNotEmpty()) {
            item(key = "playlistsHeader") { SectionHeader(stringResource(R.string.search_frequent_playlists)) }
            items(discover.frequentPlaylists, key = { "p${it.id}" }) { playlist ->
                AuraListRow(
                    title = playlist.name,
                    subtitle = pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount),
                    onClick = { navigator.openPlaylist(playlist.id) },
                    leading = { Artwork(playlist.artwork, Modifier.size(52.dp), placeholderSeed = playlist.name, placeholderIcon = AuraIcons.Playlist) },
                )
            }
        }
        trackShelf("added", R.string.home_recently_added_tracks, discover.recentlyAdded, state, viewModel, trackActions::open)
    }
}

private fun LazyListScope.trackShelf(
    key: String,
    title: Int,
    tracks: List<Track>,
    state: SearchUiState,
    viewModel: SearchViewModel,
    onMore: (Track) -> Unit,
) {
    if (tracks.isEmpty()) return
    item(key = "${key}Header") { SectionHeader(stringResource(title)) }
    itemsIndexed(tracks, key = { _, t -> "$key${t.id}" }) { index, track ->
        TrackRow(
            track = track,
            onClick = { viewModel.play(tracks, index) },
            onMore = { onMore(track) },
            isCurrent = track.id == state.currentTrackId,
            isPlaying = state.isPlaying,
        )
    }
}

@Composable
private fun RecentChip(text: String, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(Radius.pill)
            .background(AuraTheme.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = Spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AuraIcons.History, contentDescription = null, tint = AuraTheme.colors.textTertiary, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = AuraTheme.type.label,
            color = AuraTheme.colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.padding(start = Spacing.x2),
        )
        AuraIconButton(
            AuraIcons.Close,
            stringResource(R.string.search_remove_recent, text),
            onRemove,
            tint = AuraTheme.colors.textTertiary,
            iconSize = 14.dp,
        )
    }
}

@Composable
private fun ResultsList(state: SearchUiState, viewModel: SearchViewModel) {
    val navigator = LocalNavigator.current
    val trackActions = LocalTrackActions.current
    val focusManager = LocalFocusManager.current
    val results = state.results
    val openArtist = { id: Long -> viewModel.commitQuery(); navigator.openArtist(id) }
    val openAlbum = { id: Long -> viewModel.commitQuery(); navigator.openAlbum(id) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x6)) {
        if (results.suggestions.isNotEmpty()) {
            item(key = "suggestions") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
                ) {
                    results.suggestions.forEach { suggestion ->
                        SuggestionChip(suggestion, onClick = { viewModel.setQuery(suggestion) })
                    }
                }
            }
        }
        results.top?.let { top ->
            item(key = "topHeader") { SectionHeader(stringResource(R.string.search_top_result)) }
            item(key = "top") {
                TopResultCard(
                    top = top,
                    onClick = {
                        focusManager.clearFocus()
                        when (top) {
                            is TopResult.OfTrack -> viewModel.play(listOf(top.track), 0)
                            is TopResult.OfArtist -> openArtist(top.artist.id)
                            is TopResult.OfAlbum -> openAlbum(top.album.id)
                        }
                    },
                )
            }
        }
        if (results.tracks.isNotEmpty()) {
            item(key = "tracksHeader") { SectionHeader(stringResource(R.string.library_songs)) }
            itemsIndexed(results.tracks, key = { _, t -> "t${t.id}" }) { index, track ->
                TrackRow(
                    track = track,
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.play(results.tracks, index)
                    },
                    onMore = { trackActions.open(track) },
                    isCurrent = track.id == state.currentTrackId,
                    isPlaying = state.isPlaying,
                    showDuration = true,
                )
            }
        }
        if (results.artists.isNotEmpty()) {
            item(key = "artistsHeader") { SectionHeader(stringResource(R.string.library_artists)) }
            item(key = "artists") {
                LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                    items(results.artists, key = { it.id }) { artist ->
                        ArtistGridItem(artist, onClick = { openArtist(artist.id) }, modifier = Modifier.width(104.dp))
                    }
                }
            }
        }
        if (results.albums.isNotEmpty()) {
            item(key = "albumsHeader") { SectionHeader(stringResource(R.string.library_albums)) }
            item(key = "albums") {
                LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.x4)) {
                    items(results.albums, key = { it.id }) { album ->
                        AlbumGridItem(album, onClick = { openAlbum(album.id) }, modifier = Modifier.width(140.dp))
                    }
                }
            }
        }
        if (results.playlists.isNotEmpty()) {
            item(key = "playlistsHeader") { SectionHeader(stringResource(R.string.library_playlists)) }
            items(results.playlists, key = { "p${it.id}" }) { playlist ->
                AuraListRow(
                    title = playlist.name,
                    subtitle = pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount),
                    onClick = {
                        viewModel.commitQuery()
                        navigator.openPlaylist(playlist.id)
                    },
                    leading = { Artwork(playlist.artwork, Modifier.size(52.dp), placeholderSeed = playlist.name, placeholderIcon = AuraIcons.Playlist) },
                )
            }
        }
        if (results.audios.isNotEmpty()) {
            item(key = "audiosHeader") { SectionHeader(stringResource(R.string.library_audios)) }
            itemsIndexed(results.audios, key = { _, t -> "a${t.id}" }) { index, track ->
                TrackRow(
                    track = track,
                    onClick = { viewModel.play(results.audios, index) },
                    onMore = { trackActions.open(track) },
                    isCurrent = track.id == state.currentTrackId,
                    isPlaying = state.isPlaying,
                    showDuration = true,
                )
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(Radius.pill)
            .background(AuraTheme.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AuraIcons.Search, contentDescription = null, tint = AuraTheme.colors.accent, modifier = Modifier.size(14.dp))
        Text(text, style = AuraTheme.type.label, color = AuraTheme.colors.textPrimary, maxLines = 1, modifier = Modifier.padding(start = Spacing.x2))
    }
}

@Composable
private fun TopResultCard(top: TopResult, onClick: () -> Unit) {
    val (title, subtitle, artwork) = when (top) {
        is TopResult.OfTrack -> Triple(top.track.title, stringResource(R.string.search_top_song, artistLabel(top.track.artist)), top.track.artwork)
        is TopResult.OfArtist -> Triple(artistLabel(top.artist.name), stringResource(R.string.artist_label), top.artist.artwork)
        is TopResult.OfAlbum -> Triple(albumLabel(top.album.title), stringResource(R.string.search_top_album, artistLabel(top.album.artist)), top.album.artwork)
    }
    Row(
        Modifier
            .padding(horizontal = Spacing.gutter)
            .fillMaxWidth()
            .clip(Radius.large)
            .background(AuraTheme.colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Artwork(
            artwork,
            Modifier.size(76.dp),
            shape = if (top is TopResult.OfArtist) androidx.compose.foundation.shape.CircleShape else Radius.medium,
            placeholderSeed = title,
            placeholderIcon = if (top is TopResult.OfArtist) AuraIcons.Artist else AuraIcons.Note,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraTheme.type.headline, color = AuraTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
