package com.aura.music.feature.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.common.formatDuration
import com.aura.music.core.common.formatFileSize
import com.aura.music.core.designsystem.components.Artwork
import com.aura.music.core.designsystem.components.AuraListRow
import com.aura.music.core.designsystem.components.AuraSheet
import com.aura.music.core.designsystem.components.SheetAction
import com.aura.music.core.designsystem.components.SheetHeader
import com.aura.music.core.designsystem.components.TextInputDialog
import com.aura.music.core.designsystem.components.albumLabel
import com.aura.music.core.designsystem.components.artistLabel
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.AudioKind
import com.aura.music.core.model.Track
import com.aura.music.feature.library.labelRes
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.ExtraTrackAction
import com.aura.music.ui.LocalMessenger
import com.aura.music.ui.LocalPlayerSheet
import com.aura.music.ui.TrackActions

private sealed interface ActiveSheet {
    data class Actions(val track: Track, val extras: List<ExtraTrackAction>) : ActiveSheet
    data class AddToPlaylist(val tracks: List<Track>) : ActiveSheet
    data class CreatePlaylist(val tracks: List<Track>) : ActiveSheet
    data class Details(val track: Track) : ActiveSheet
    data class Reclassify(val track: Track) : ActiveSheet
    data class FixInfo(val track: Track) : ActiveSheet
}

/**
 * Hosts every track-related sheet in one place so all screens share identical behaviour.
 * Provides a [TrackActions] instance to [content].
 */
@Composable
fun TrackActionsHost(content: @Composable (TrackActions) -> Unit) {
    var sheet by remember { mutableStateOf<ActiveSheet?>(null) }
    val actions = remember {
        object : TrackActions {
            override fun open(track: Track, extras: List<ExtraTrackAction>) {
                sheet = ActiveSheet.Actions(track, extras)
            }

            override fun addToPlaylist(tracks: List<Track>) {
                if (tracks.isNotEmpty()) sheet = ActiveSheet.AddToPlaylist(tracks)
            }

            override fun showDetails(track: Track) {
                sheet = ActiveSheet.Details(track)
            }
        }
    }
    content(actions)
    when (val current = sheet) {
        is ActiveSheet.Actions -> TrackActionsSheet(current, onDismiss = { sheet = null }, onNext = { sheet = it })
        is ActiveSheet.AddToPlaylist -> AddToPlaylistSheet(current.tracks, onDismiss = { sheet = null }, onCreateNew = {
            sheet = ActiveSheet.CreatePlaylist(current.tracks)
        })
        is ActiveSheet.CreatePlaylist -> CreatePlaylistDialog(current.tracks, onDismiss = { sheet = null })
        is ActiveSheet.Details -> TrackDetailsSheet(current.track, onDismiss = { sheet = null })
        is ActiveSheet.Reclassify -> ReclassifySheet(current.track, onDismiss = { sheet = null })
        is ActiveSheet.FixInfo -> FixInfoSheet(current.track, onDismiss = { sheet = null })
        null -> Unit
    }
}

@Composable
private fun TrackActionsSheet(
    state: ActiveSheet.Actions,
    onDismiss: () -> Unit,
    onNext: (ActiveSheet) -> Unit,
    viewModel: TrackActionsViewModel = hiltViewModel(),
) {
    val track = state.track
    val favorites by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val overrides by viewModel.overrides.collectAsStateWithLifecycle()
    val isFavorite = track.id in favorites
    val messenger = LocalMessenger.current
    val navigator = LocalNavigator.current
    val playerSheet = LocalPlayerSheet.current
    val resources = LocalResources.current

    AuraSheet(onDismiss) {
        SheetHeader(
            title = track.title,
            subtitle = artistLabel(track.artist),
            leading = { Artwork(track.artwork, Modifier.size(52.dp), placeholderSeed = track.album) },
        )
        SheetAction(AuraIcons.PlayNext, stringResource(R.string.action_play_next), onClick = {
            viewModel.playNext(track)
            messenger.show(resources.getString(R.string.msg_play_next))
            onDismiss()
        })
        SheetAction(AuraIcons.AddToQueue, stringResource(R.string.action_add_to_queue), onClick = {
            viewModel.addToQueue(track)
            messenger.show(resources.getString(R.string.msg_added_to_queue))
            onDismiss()
        })
        SheetAction(AuraIcons.Playlist, stringResource(R.string.action_add_to_playlist), onClick = {
            onNext(ActiveSheet.AddToPlaylist(listOf(track)))
        })
        SheetAction(
            icon = if (isFavorite) AuraIcons.HeartFilled else AuraIcons.Heart,
            label = stringResource(if (isFavorite) R.string.action_unfavorite else R.string.action_favorite),
            onClick = {
                viewModel.toggleFavorite(track) { added ->
                    messenger.show(resources.getString(if (added) R.string.msg_favorited else R.string.msg_unfavorited))
                }
                onDismiss()
            },
        )
        if (track.album != null && track.albumId >= 0) {
            SheetAction(AuraIcons.Album, stringResource(R.string.action_go_to_album), onClick = {
                onDismiss()
                playerSheet.collapse()
                navigator.openAlbum(track.albumId)
            })
        }
        if (track.artist != null && track.artistId >= 0) {
            SheetAction(AuraIcons.Artist, stringResource(R.string.action_go_to_artist), onClick = {
                onDismiss()
                playerSheet.collapse()
                navigator.openArtist(track.artistId)
            })
        }
        SheetAction(AuraIcons.Info, stringResource(R.string.action_details), onClick = { onNext(ActiveSheet.Details(track)) })
        if (track.uri.isNotEmpty()) {
            if (track.kind != AudioKind.Music) {
                SheetAction(AuraIcons.Note, stringResource(R.string.action_mark_music), onClick = {
                    viewModel.setKind(track, AudioKind.Music)
                    messenger.show(resources.getString(R.string.msg_marked_music))
                    onDismiss()
                })
            }
            SheetAction(AuraIcons.Edit, stringResource(R.string.action_reclassify), onClick = { onNext(ActiveSheet.Reclassify(track)) })
            if (track.kind == AudioKind.Music) {
                SheetAction(AuraIcons.Search, stringResource(R.string.action_fix_info), onClick = { onNext(ActiveSheet.FixInfo(track)) })
            }
            if (track.kind != AudioKind.Music && track.folder.isNotEmpty()) {
                SheetAction(AuraIcons.Folder, stringResource(R.string.action_folder_as_music), onClick = {
                    viewModel.treatFolderAsMusic(track)
                    messenger.show(resources.getString(R.string.msg_folder_rule, track.folder.trimEnd('/')))
                    onDismiss()
                })
            }
            if (track.id in overrides) {
                SheetAction(AuraIcons.Refresh, stringResource(R.string.action_reset_classification), onClick = {
                    viewModel.resetClassification(track)
                    messenger.show(resources.getString(R.string.msg_reclassified))
                    onDismiss()
                })
            }
            SheetAction(AuraIcons.EyeOff, stringResource(R.string.action_hide_file), onClick = {
                viewModel.hide(track)
                messenger.show(resources.getString(R.string.msg_hidden))
                onDismiss()
            })
        }
        state.extras.forEach { extra ->
            SheetAction(extra.icon, extra.label, onClick = {
                extra.onClick()
                onDismiss()
            })
        }
    }
}

@Composable
private fun AddToPlaylistSheet(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    viewModel: TrackActionsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    AuraSheet(onDismiss) {
        Text(
            stringResource(R.string.action_add_to_playlist),
            style = AuraTheme.type.headline,
            color = AuraTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        )
        SheetAction(AuraIcons.Plus, stringResource(R.string.new_playlist), onClick = onCreateNew, tint = AuraTheme.colors.accent)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(playlists, key = { it.id }) { playlist ->
                AuraListRow(
                    title = playlist.name,
                    subtitle = pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount),
                    onClick = {
                        viewModel.addToPlaylist(playlist, tracks) { added ->
                            val message = if (added == 0) {
                                resources.getString(R.string.msg_already_in_playlist, playlist.name)
                            } else {
                                resources.getQuantityString(R.plurals.msg_added_to_playlist, added, added, playlist.name)
                            }
                            messenger.show(message)
                        }
                        onDismiss()
                    },
                    leading = {
                        Artwork(playlist.artwork, Modifier.size(48.dp), placeholderSeed = playlist.name, placeholderIcon = AuraIcons.Playlist)
                    },
                )
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    viewModel: TrackActionsViewModel = hiltViewModel(),
) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    TextInputDialog(
        title = stringResource(R.string.new_playlist),
        initialValue = "",
        placeholder = stringResource(R.string.playlist_name_placeholder),
        confirmLabel = stringResource(R.string.action_create),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = { name ->
            viewModel.createPlaylist(name, tracks) { messenger.show(resources.getString(R.string.msg_playlist_created, name.trim())) }
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun TrackDetailsSheet(track: Track, onDismiss: () -> Unit) {
    val rows = buildList {
        add(R.string.details_artist to artistLabel(track.artist))
        add(R.string.details_album to albumLabel(track.album))
        track.albumArtist?.let { add(R.string.details_album_artist to it) }
        track.year?.let { add(R.string.details_year to it.toString()) }
        track.genre?.let { add(R.string.details_genre to it) }
        if (track.trackNumber > 0) add(R.string.details_track to track.trackNumber.toString())
        add(R.string.details_duration to formatDuration(track.durationMs))
        track.mimeType?.let { add(R.string.details_format to it.substringAfter('/').uppercase()) }
        track.bitrate?.let { add(R.string.details_bitrate to stringResource(R.string.details_bitrate_value, it / 1000)) }
        if (track.sizeBytes > 0) add(R.string.details_size to formatFileSize(track.sizeBytes))
        if (track.displayName.isNotEmpty()) add(R.string.details_location to track.folder + track.displayName)
        add(R.string.details_classification to stringResource(track.kind.labelRes))
    }
    AuraSheet(onDismiss) {
        SheetHeader(
            title = track.title,
            subtitle = stringResource(R.string.details_title),
            leading = { Artwork(track.artwork, Modifier.size(52.dp), placeholderSeed = track.album) },
        )
        Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2)) {
            rows.forEach { (label, value) -> DetailRow(stringResource(label), value) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.x2)) {
        Text(label, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, modifier = Modifier.weight(0.38f))
        Text(value, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textPrimary, modifier = Modifier.weight(0.62f))
    }
}

@Composable
private fun ReclassifySheet(
    track: Track,
    onDismiss: () -> Unit,
    viewModel: TrackActionsViewModel = hiltViewModel(),
) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    AuraSheet(onDismiss) {
        SheetHeader(title = stringResource(R.string.reclassify_title), subtitle = track.title)
        AudioKind.entries.forEach { kind ->
            SheetAction(
                icon = if (kind == track.kind) AuraIcons.Check else AuraIcons.Wave,
                label = stringResource(kind.labelRes),
                tint = if (kind == track.kind) AuraTheme.colors.accent else AuraTheme.colors.textPrimary,
                onClick = {
                    if (kind != track.kind) {
                        viewModel.setKind(track, kind)
                        messenger.show(
                            resources.getString(if (kind == AudioKind.Music) R.string.msg_marked_music else R.string.msg_moved_audio),
                        )
                    }
                    onDismiss()
                },
            )
        }
    }
}
