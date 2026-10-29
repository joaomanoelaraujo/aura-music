package com.aura.music.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.LocalGradientIntensity
import com.aura.music.core.designsystem.atmosphere.rememberAtmosphere
import com.aura.music.core.designsystem.components.AlbumGridItem
import com.aura.music.core.designsystem.components.ArtistGridItem
import com.aura.music.core.designsystem.components.Artwork
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraMark
import com.aura.music.core.designsystem.components.PlayPauseButton
import com.aura.music.core.designsystem.components.SectionHeader
import com.aura.music.core.designsystem.components.artistLabel
import com.aura.music.core.designsystem.components.cardClickable
import com.aura.music.core.designsystem.components.pressScale
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.HomeSection
import com.aura.music.core.model.Track
import com.aura.music.data.repository.MixSummary
import com.aura.music.feature.common.LibraryGate
import com.aura.music.feature.common.MixCover
import com.aura.music.feature.common.mixTitle
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalBottomInset
import com.aura.music.ui.LocalPlayerSheet
import com.aura.music.ui.LocalTrackActions
import java.util.Calendar

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        HomeHeader()
        LibraryGate(state.status, viewModel::refresh) {
            HomeContent(state, viewModel)
        }
    }
}

@Composable
private fun HomeHeader() {
    val navigator = LocalNavigator.current
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = Spacing.gutter, end = Spacing.x2, top = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraMark(size = 22.dp, animate = false)
        Spacer(Modifier.width(Spacing.x2))
        Text(
            stringResource(R.string.app_name),
            style = AuraTheme.type.title.copy(letterSpacing = 0.32.em),
            color = AuraTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        AuraIconButton(AuraIcons.Together, stringResource(R.string.together_title), navigator::openTogether)
        AuraIconButton(AuraIcons.Search, stringResource(R.string.cd_search), navigator::openSearch)
        AuraIconButton(AuraIcons.Tune, stringResource(R.string.cd_settings), navigator::openSettings)
    }
}

@Composable
private fun HomeContent(state: HomeUiState, viewModel: HomeViewModel) {
    val navigator = LocalNavigator.current
    val trackActions = LocalTrackActions.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x6)) {
        item(key = "greeting") { Greeting() }
        state.sections.forEach { section ->
            when (section) {
                HomeSection.Continue -> state.continueTrack?.let { track ->
                    item(key = "continue") { ContinueListening(track, state.isPlaying, viewModel::resume) }
                }
                HomeSection.Shortcuts -> item(key = "shortcuts") {
                    Shortcuts(
                        favoritesCount = state.favoritesCount,
                        playlistsCount = state.playlistsCount,
                        onFavorites = navigator::openFavorites,
                        onPlaylists = navigator::openPlaylists,
                    )
                }
                HomeSection.Mixes -> if (state.mixes.isNotEmpty() || state.recentFromHistory) {
                    item(key = "mixesHeader") { SectionHeader(stringResource(R.string.home_mixes)) }
                    item(key = "mixes") {
                        Shelf {
                            items(state.mixes, key = { it.kind.name }) { mix -> MixCard(mix, onClick = { navigator.openMix(mix.kind) }) }
                            if (state.recentFromHistory) item(key = "recap") { RecapCard(onClick = navigator::openRecap) }
                        }
                    }
                }
                HomeSection.RecentTracks -> trackShelf(
                    key = "recentTracks",
                    title = if (state.recentFromHistory) R.string.home_recent_tracks else R.string.home_recently_added_tracks,
                    tracks = state.recentTracks,
                    onPlay = viewModel::playFrom,
                    onMore = trackActions::open,
                )
                HomeSection.RecentlyAdded -> if (state.recentFromHistory) {
                    trackShelf("recentlyAdded", R.string.home_recently_added_tracks, state.recentlyAdded, viewModel::playFrom, trackActions::open)
                }
                HomeSection.RecentAlbums -> if (state.recentAlbums.isNotEmpty()) {
                    item(key = "albumsHeader") { SectionHeader(stringResource(R.string.home_recent_albums)) }
                    item(key = "albums") {
                        Shelf {
                            items(state.recentAlbums, key = { it.id }) { album ->
                                AlbumGridItem(album, onClick = { navigator.openAlbum(album.id) }, modifier = Modifier.width(152.dp))
                            }
                        }
                    }
                }
                HomeSection.TopArtists -> if (state.topArtists.isNotEmpty()) {
                    item(key = "artistsHeader") { SectionHeader(stringResource(R.string.home_top_artists)) }
                    item(key = "artists") {
                        Shelf {
                            items(state.topArtists, key = { it.id }) { artist ->
                                ArtistGridItem(artist, onClick = { navigator.openArtist(artist.id) }, modifier = Modifier.width(112.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.trackShelf(
    key: String,
    title: Int,
    tracks: List<Track>,
    onPlay: (List<Track>, Int) -> Unit,
    onMore: (Track) -> Unit,
) {
    if (tracks.isEmpty()) return
    item(key = "${key}Header") { SectionHeader(stringResource(title)) }
    item(key = key) {
        Shelf {
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackCard(track = track, onClick = { onPlay(tracks, index) }, onLongClick = { onMore(track) })
            }
        }
    }
}

/** A self-building mix: its cover, name and size. */
@Composable
private fun MixCard(mix: MixSummary, onClick: () -> Unit) {
    Column(
        // Only the cover is rounded; clipping the whole card would cut the text's first letters.
        Modifier
            .width(152.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        MixCover(mix.kind, Modifier.size(152.dp))
        Spacer(Modifier.height(Spacing.x2))
        Text(stringResource(mixTitle(mix.kind)), style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            pluralStringResource(R.plurals.track_count, mix.tracks.size, mix.tracks.size),
            style = AuraTheme.type.bodySmall,
            color = AuraTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}

/** The way into the listening look back. */
@Composable
private fun RecapCard(onClick: () -> Unit) {
    Column(
        // Only the cover is rounded; clipping the whole card would cut the text's first letters.
        Modifier
            .width(152.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(
            Modifier.size(152.dp).clip(Radius.xLarge).background(Brush.linearGradient(listOf(AuraTheme.colors.accent, AuraTheme.colors.surface))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AuraIcons.History, contentDescription = null, tint = AuraTheme.colors.textPrimary, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(Spacing.x2))
        Text(stringResource(R.string.recap_title), style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 1)
        Text(stringResource(R.string.recap_card_body), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun Greeting() {
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..17 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        }
    }
    Text(
        stringResource(greeting),
        style = AuraTheme.type.displayLarge,
        color = AuraTheme.colors.textPrimary,
        modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x6, bottom = Spacing.x4),
    )
}

/** Hero card for the current session, tinted with that track's atmosphere. */
@Composable
private fun ContinueListening(track: Track, isPlaying: Boolean, onTogglePlay: () -> Unit) {
    val playerSheet = LocalPlayerSheet.current
    val atmosphere = rememberAtmosphere(track.artwork)
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .padding(horizontal = Spacing.gutter)
            .fillMaxWidth()
            .pressScale(interaction, 0.98f)
            .clip(Radius.xLarge)
            .background(Brush.linearGradient(listOf(atmosphere.glow.copy(alpha = 0.55f * LocalGradientIntensity.current), AuraTheme.colors.surface)))
            .clickable(interaction, indication = null, role = Role.Button, onClick = playerSheet::expand)
            .padding(Spacing.x4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Artwork(track.artwork, Modifier.size(84.dp), shape = Radius.medium, placeholderSeed = track.album)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_continue).uppercase(), style = AuraTheme.type.overline, color = AuraTheme.colors.textSecondary)
            Spacer(Modifier.height(Spacing.x1))
            Text(track.title, style = AuraTheme.type.title, color = AuraTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(artistLabel(track.artist), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1)
        }
        PlayPauseButton(
            isPlaying = isPlaying,
            onClick = onTogglePlay,
            size = 52.dp,
            container = atmosphere.accent,
            content = atmosphere.onAccent,
        )
    }
}

@Composable
private fun Shortcuts(favoritesCount: Int, playlistsCount: Int, onFavorites: () -> Unit, onPlaylists: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x4),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        ShortcutTile(
            icon = AuraIcons.HeartFilled,
            title = stringResource(R.string.home_favorites),
            subtitle = pluralStringResource(R.plurals.track_count, favoritesCount, favoritesCount),
            onClick = onFavorites,
            modifier = Modifier.weight(1f),
        )
        ShortcutTile(
            icon = AuraIcons.Playlist,
            title = stringResource(R.string.home_playlists),
            subtitle = playlistsCount.toString(),
            onClick = onPlaylists,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ShortcutTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(interaction, 0.97f)
            .clip(Radius.large)
            .background(AuraTheme.colors.surface)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(Spacing.x4),
    ) {
        Icon(icon, contentDescription = null, tint = AuraTheme.colors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(Spacing.x5))
        Text(title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary)
        Text(subtitle, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
    }
}

@Composable
private fun Shelf(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
        content = content,
    )
}

@Composable
private fun TrackCard(track: Track, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
            .cardClickable(onClick, onLongClick),
    ) {
        Artwork(track.artwork, Modifier.fillMaxWidth().aspectRatio(1f), shape = Radius.medium, placeholderSeed = track.album)
        Text(
            track.title,
            style = AuraTheme.type.titleSmall,
            color = AuraTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.x2),
        )
        Text(
            artistLabel(track.artist),
            style = AuraTheme.type.bodySmall,
            color = AuraTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
