package com.aura.music.feature.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.common.totalMinutes
import com.aura.music.core.designsystem.atmosphere.Atmosphere
import com.aura.music.core.designsystem.atmosphere.rememberAtmosphere
import com.aura.music.core.designsystem.components.Artwork
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraSheet
import com.aura.music.core.designsystem.components.ConfirmDialog
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.components.LoadingState
import com.aura.music.core.designsystem.components.REORDERABLE_CONTENT_TYPE
import com.aura.music.core.designsystem.components.SheetAction
import com.aura.music.core.designsystem.components.TextInputDialog
import com.aura.music.core.designsystem.components.TrackRow
import com.aura.music.core.designsystem.components.dragHandle
import com.aura.music.core.designsystem.components.rememberReorderState
import com.aura.music.core.designsystem.components.reorderAccessibility
import com.aura.music.core.designsystem.components.reorderableItem
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Sizes
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.PlaylistEntry
import com.aura.music.feature.common.CollapsingCollection
import com.aura.music.feature.common.CollectionTitle
import com.aura.music.feature.common.NotFound
import com.aura.music.feature.common.PlayShuffleRow
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.ExtraTrackAction
import com.aura.music.ui.LocalTrackActions

private enum class PlaylistDialog { Rename, Delete, Menu }

@Composable
fun PlaylistScreen(viewModel: PlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val current = state) {
        PlaylistUiState.Loading -> LoadingState()
        PlaylistUiState.NotFound -> NotFound()
        is PlaylistUiState.Ready -> PlaylistContent(current, viewModel)
    }
}

@Composable
private fun PlaylistContent(state: PlaylistUiState.Ready, viewModel: PlaylistViewModel) {
    val navigator = LocalNavigator.current
    val trackActions = LocalTrackActions.current
    val playlist = state.detail.playlist
    val atmosphere = rememberAtmosphere(playlist.artwork)
    var editing by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<PlaylistDialog?>(null) }
    val removeLabel = stringResource(R.string.action_remove_from_playlist)

    var entries by remember { mutableStateOf(state.detail.entries) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        onMove = { fromKey, toKey ->
            val from = entries.indexOfFirst { it.entryId == fromKey }
            val to = entries.indexOfFirst { it.entryId == toKey }
            if (from >= 0 && to >= 0) entries = entries.toMutableList().apply { add(to, removeAt(from)) }
        },
        onDragFinished = { viewModel.reorder(entries.map { it.entryId }) },
    )
    LaunchedEffect(state.detail.entries) {
        if (reorder.draggingKey == null) entries = state.detail.entries
    }

    CollapsingCollection(
        title = playlist.name,
        atmosphere = atmosphere,
        onBack = { navigator.back() },
        listState = listState,
        hero = { size ->
            Artwork(playlist.artwork, Modifier.size(size), shape = Radius.xLarge, placeholderSeed = playlist.name, placeholderIcon = AuraIcons.Playlist)
        },
        actions = {
            if (editing) {
                AuraIconButton(AuraIcons.Check, stringResource(R.string.action_done), { editing = false }, tint = atmosphere.accent)
            } else {
                AuraIconButton(AuraIcons.Plus, stringResource(R.string.playlist_add_songs), { navigator.openTrackPicker(playlist.id) })
                AuraIconButton(AuraIcons.More, stringResource(R.string.cd_more_options, playlist.name), { dialog = PlaylistDialog.Menu })
            }
        },
    ) {
        item(key = "title") {
            CollectionTitle(
                eyebrow = stringResource(R.string.playlist_label),
                title = playlist.name,
                subtitle = pluralStringResource(R.plurals.track_count, playlist.trackCount, playlist.trackCount) +
                    stringResource(R.string.dot_separator) +
                    stringResource(R.string.minutes_short, totalMinutes(playlist.durationMs)),
            )
        }
        if (state.detail.unavailableCount > 0) {
            item(key = "unavailable") {
                Text(
                    pluralStringResource(R.plurals.playlist_unavailable, state.detail.unavailableCount, state.detail.unavailableCount),
                    style = AuraTheme.type.bodySmall,
                    color = AuraTheme.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
                )
            }
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = AuraIcons.Playlist,
                    title = stringResource(R.string.playlist_empty_title),
                    message = stringResource(R.string.playlist_empty_body),
                    actionLabel = stringResource(R.string.playlist_add_songs),
                    onAction = { navigator.openTrackPicker(playlist.id) },
                )
            }
        } else if (!editing) {
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
        itemsIndexed(entries, key = { _, entry -> entry.entryId }, contentType = { _, _ -> REORDERABLE_CONTENT_TYPE }) { index, entry ->
            PlaylistRow(
                entry = entry,
                editing = editing,
                state = state,
                atmosphere = atmosphere,
                onClick = { viewModel.play(index) },
                onMore = {
                    trackActions.open(entry.track, listOf(ExtraTrackAction(removeLabel, AuraIcons.Trash) { viewModel.remove(entry.entryId) }))
                },
                onRemove = { viewModel.remove(entry.entryId) },
                handle = Modifier.dragHandle(reorder, entry.entryId),
                modifier = reorderableItem(reorder, entry.entryId)
                    .background(if (reorder.draggingKey == entry.entryId) AuraTheme.colors.surfaceElevated else Color.Transparent)
                    .then(
                        if (!editing) {
                            Modifier
                        } else {
                            Modifier.reorderAccessibility(
                                canMoveUp = index > 0,
                                canMoveDown = index < entries.lastIndex,
                                onMoveUp = { viewModel.reorder(entries.map { it.entryId }.moved(index, index - 1)) },
                                onMoveDown = { viewModel.reorder(entries.map { it.entryId }.moved(index, index + 1)) },
                            )
                        },
                    ),
            )
        }
    }

    PlaylistDialogs(dialog, playlist.name, onDismiss = { dialog = null }, onEdit = { editing = true }, onOpen = { dialog = it }, viewModel = viewModel)
}

@Composable
private fun PlaylistRow(
    entry: PlaylistEntry,
    editing: Boolean,
    state: PlaylistUiState.Ready,
    atmosphere: Atmosphere,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onRemove: () -> Unit,
    handle: Modifier,
    modifier: Modifier,
) {
    val track = entry.track
    val reorderLabel = stringResource(R.string.cd_reorder, track.title)
    TrackRow(
        track = track,
        onClick = if (editing) ({}) else onClick,
        onMore = if (editing) null else onMore,
        isCurrent = track.id == state.currentTrackId,
        isPlaying = state.isPlaying,
        accent = atmosphere.accent,
        showDuration = !editing,
        modifier = modifier,
        trailing = if (!editing) {
            null
        } else {
            {
                AuraIconButton(
                    AuraIcons.Close,
                    stringResource(R.string.cd_remove_from_playlist, track.title),
                    onRemove,
                    tint = AuraTheme.colors.textTertiary,
                    iconSize = Sizes.iconSmall,
                )
                Box(handle.size(Sizes.touchTarget).semantics { contentDescription = reorderLabel }, contentAlignment = Alignment.Center) {
                    Icon(AuraIcons.DragHandle, contentDescription = null, tint = AuraTheme.colors.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
                }
            }
        },
    )
}

@Composable
private fun PlaylistDialogs(
    dialog: PlaylistDialog?,
    name: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onOpen: (PlaylistDialog) -> Unit,
    viewModel: PlaylistViewModel,
) {
    val navigator = LocalNavigator.current
    when (dialog) {
        PlaylistDialog.Menu -> AuraSheet(onDismiss) {
            SheetAction(AuraIcons.DragHandle, stringResource(R.string.playlist_edit), onClick = {
                onDismiss()
                onEdit()
            })
            SheetAction(AuraIcons.Edit, stringResource(R.string.playlist_rename), onClick = { onOpen(PlaylistDialog.Rename) })
            SheetAction(AuraIcons.Plus, stringResource(R.string.playlist_add_songs), onClick = {
                onDismiss()
                navigator.openTrackPicker(viewModel.playlistId)
            })
            SheetAction(AuraIcons.Trash, stringResource(R.string.playlist_delete), onClick = { onOpen(PlaylistDialog.Delete) }, tint = AuraTheme.colors.danger)
        }
        PlaylistDialog.Rename -> TextInputDialog(
            title = stringResource(R.string.playlist_rename),
            initialValue = name,
            placeholder = stringResource(R.string.playlist_name_placeholder),
            confirmLabel = stringResource(R.string.action_save),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.rename(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        PlaylistDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.playlist_delete),
            message = stringResource(R.string.playlist_delete_message, name),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                onDismiss()
                viewModel.delete { navigator.back() }
            },
            onDismiss = onDismiss,
        )
        null -> Unit
    }
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }
