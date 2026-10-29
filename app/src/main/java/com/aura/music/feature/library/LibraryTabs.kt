package com.aura.music.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.components.AlbumGridItem
import com.aura.music.core.designsystem.components.AlbumRow
import com.aura.music.core.designsystem.components.ArtistGridItem
import com.aura.music.core.designsystem.components.ArtistRow
import com.aura.music.core.designsystem.components.Artwork
import com.aura.music.core.designsystem.components.AuraChip
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraListRow
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.components.TextInputDialog
import com.aura.music.core.designsystem.components.TrackRow
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Sizes
import com.aura.music.core.common.formatDuration
import java.text.DateFormat
import java.util.Date
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.Album
import com.aura.music.core.model.Artist
import com.aura.music.core.model.AudioKind
import com.aura.music.core.model.CoverSize
import com.aura.music.core.model.Track
import com.aura.music.core.model.Folder
import com.aura.music.core.model.Genre
import com.aura.music.core.model.LibraryLayout
import com.aura.music.core.model.Playlist
import com.aura.music.feature.common.PlayShuffleRow
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalBottomInset
import com.aura.music.ui.LocalTrackActions

private val listBottomPadding @Composable get() = PaddingValues(bottom = LocalBottomInset.current + Spacing.x4)

@Composable
fun SongsTab(state: LibraryUiState, viewModel: LibraryViewModel) {
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val trackActions = LocalTrackActions.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
        item(key = "actions") {
            PlayShuffleRow(
                summary = pluralStringResource(R.plurals.track_count, state.tracks.size, state.tracks.size),
                onPlay = { viewModel.play(state.tracks, 0) },
                onShuffle = { viewModel.shuffle(state.tracks) },
            )
        }
        itemsIndexed(state.tracks, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track ->
            TrackRow(
                track = track,
                onClick = { viewModel.play(state.tracks, index) },
                onMore = { trackActions.open(track) },
                isCurrent = track.id == nowPlaying.trackId,
                isPlaying = nowPlaying.isPlaying,
                showDuration = true,
            )
        }
    }
}

@Composable
fun AlbumsTab(albums: List<Album>, layout: LibraryLayout, coverSize: CoverSize) {
    val navigator = LocalNavigator.current
    if (layout == LibraryLayout.Grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(coverSize.albumCellDp.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x2, bottom = LocalBottomInset.current + Spacing.x4),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
            verticalArrangement = Arrangement.spacedBy(Spacing.x4),
        ) {
            items(albums, key = { it.id }, contentType = { "album" }) { album -> AlbumGridItem(album, onClick = { navigator.openAlbum(album.id) }) }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
            items(albums, key = { it.id }, contentType = { "album" }) { album -> AlbumRow(album, onClick = { navigator.openAlbum(album.id) }) }
        }
    }
}

@Composable
fun ArtistsTab(artists: List<Artist>, layout: LibraryLayout, coverSize: CoverSize) {
    val navigator = LocalNavigator.current
    if (layout == LibraryLayout.Grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(coverSize.artistCellDp.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.x2, bottom = LocalBottomInset.current + Spacing.x4),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
            verticalArrangement = Arrangement.spacedBy(Spacing.x4),
        ) {
            items(artists, key = { it.id }, contentType = { "artist" }) { artist -> ArtistGridItem(artist, onClick = { navigator.openArtist(artist.id) }) }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
            items(artists, key = { it.id }, contentType = { "artist" }) { artist -> ArtistRow(artist, onClick = { navigator.openArtist(artist.id) }) }
        }
    }
}

@Composable
fun PlaylistsTab(playlists: List<Playlist>, viewModel: LibraryViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    var creating by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
        item(key = "new") {
            AuraListRow(
                title = stringResource(R.string.new_playlist),
                subtitle = null,
                onClick = { creating = true },
                titleColor = AuraTheme.colors.accent,
                leading = { IconTile(AuraIcons.Plus, accent = true) },
            )
        }
        if (playlists.isEmpty()) {
            item(key = "empty") {
                EmptyState(AuraIcons.Playlist, stringResource(R.string.playlists_empty_title), stringResource(R.string.playlists_empty_body))
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            AuraListRow(
                title = playlist.name,
                subtitle = pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount),
                onClick = { navigator.openPlaylist(playlist.id) },
                leading = {
                    Artwork(playlist.artwork, Modifier.size(56.dp), placeholderSeed = playlist.name, placeholderIcon = AuraIcons.Playlist)
                },
            )
        }
    }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.new_playlist),
            initialValue = "",
            placeholder = stringResource(R.string.playlist_name_placeholder),
            confirmLabel = stringResource(R.string.action_create),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { name ->
                creating = false
                viewModel.createPlaylist(name) { id -> navigator.openPlaylist(id) }
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
fun FoldersTab(folders: List<Folder>) {
    val navigator = LocalNavigator.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
        items(folders, key = { it.path }) { folder ->
            AuraListRow(
                title = folder.name,
                subtitle = pluralStringResource(R.plurals.track_count, folder.trackCount, folder.trackCount) + "  ·  " + folder.path.trimEnd('/'),
                onClick = { navigator.openFolder(folder.path) },
                leading = { IconTile(AuraIcons.Folder) },
            )
        }
    }
}

@Composable
fun GenresTab(genres: List<Genre>) {
    val navigator = LocalNavigator.current
    if (genres.isEmpty()) {
        EmptyState(AuraIcons.Wave, stringResource(R.string.genres_empty_title), stringResource(R.string.genres_empty_body))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
        items(genres, key = { it.name }) { genre ->
            AuraListRow(
                title = genre.name,
                subtitle = pluralStringResource(R.plurals.track_count, genre.trackCount, genre.trackCount),
                onClick = { navigator.openGenre(genre.name) },
                leading = { Artwork(genre.artwork, Modifier.size(56.dp), placeholderSeed = genre.name, placeholderIcon = AuraIcons.Wave) },
            )
        }
    }
}

@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Boolean = false) {
    Box(
        Modifier
            .size(56.dp)
            .clip(Radius.small)
            .background(AuraTheme.colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (accent) AuraTheme.colors.accent else AuraTheme.colors.textSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
fun AudiosTab(audios: Map<AudioGroup, List<Track>>, viewModel: LibraryViewModel) {
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val trackActions = LocalTrackActions.current
    var group by rememberSaveable { mutableStateOf(AudioGroup.WhatsApp) }
    val items = audios[group].orEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.x1),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            AudioGroup.entries.forEach { g ->
                val count = audios[g].orEmpty().size
                AuraChip(
                    text = stringResource(g.labelRes) + if (count > 0) "  $count" else "",
                    selected = g == group,
                    onClick = { group = g },
                    accent = AuraTheme.colors.textPrimary,
                )
            }
        }
        if (items.isEmpty()) {
            EmptyState(AuraIcons.Mic, stringResource(R.string.audios_empty_title), stringResource(R.string.audios_empty_body))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
            item(key = "note") {
                Text(
                    stringResource(R.string.audios_note),
                    style = AuraTheme.type.bodySmall,
                    color = AuraTheme.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
                )
            }
            itemsIndexed(items, key = { _, track -> track.id }) { index, track ->
                AudioRow(
                    track = track,
                    isCurrent = track.id == nowPlaying.trackId,
                    onClick = { viewModel.play(items, index) },
                    onMore = { trackActions.open(track) },
                )
            }
        }
    }
}

@Composable
private fun AudioRow(track: Track, isCurrent: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    val date = remember(track.dateAddedSec) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(track.dateAddedSec * 1000))
    }
    AuraListRow(
        title = track.title,
        subtitle = listOf(formatDuration(track.durationMs), date, stringResource(track.kind.labelRes)).joinToString("  ·  "),
        onClick = onClick,
        onLongClick = onMore,
        titleColor = if (isCurrent) AuraTheme.colors.accent else AuraTheme.colors.textPrimary,
        leading = { IconTile(track.kind.icon) },
        trailing = {
            AuraIconButton(
                icon = AuraIcons.More,
                contentDescription = stringResource(R.string.cd_more_options, track.title),
                onClick = onMore,
                tint = AuraTheme.colors.textSecondary,
                iconSize = Sizes.iconSmall,
            )
        },
    )
}

private val AudioGroup.labelRes: Int
    get() = when (this) {
        AudioGroup.WhatsApp -> R.string.audios_whatsapp
        AudioGroup.Recordings -> R.string.audios_recordings
        AudioGroup.Other -> R.string.audios_other
    }

val AudioKind.labelRes: Int
    get() = when (this) {
        AudioKind.Music -> R.string.kind_music
        AudioKind.WhatsAppVoice -> R.string.kind_whatsapp_voice
        AudioKind.WhatsAppAudio -> R.string.kind_whatsapp_audio
        AudioKind.VoiceMessage -> R.string.kind_voice_message
        AudioKind.Recording -> R.string.kind_recording
        AudioKind.Podcast -> R.string.kind_podcast
        AudioKind.Other -> R.string.kind_other
    }

private val AudioKind.icon: ImageVector
    get() = when (this) {
        AudioKind.WhatsAppVoice, AudioKind.VoiceMessage -> AuraIcons.Chat
        AudioKind.Recording -> AuraIcons.Mic
        AudioKind.Music -> AuraIcons.Note
        else -> AuraIcons.Wave
    }
