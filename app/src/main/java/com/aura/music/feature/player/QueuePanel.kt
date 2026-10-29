package com.aura.music.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.Atmosphere
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.ButtonStyle
import com.aura.music.core.designsystem.components.REORDERABLE_CONTENT_TYPE
import com.aura.music.core.designsystem.components.TrackRow
import com.aura.music.core.designsystem.components.dragHandle
import com.aura.music.core.designsystem.components.reorderAccessibility
import com.aura.music.core.designsystem.components.reorderableItem
import com.aura.music.core.designsystem.components.rememberReorderState
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Sizes
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.playback.controller.PlayerUiState
import com.aura.music.playback.controller.QueueItem

data class QueueActions(
    val onClose: () -> Unit,
    val onSkipTo: (Int) -> Unit,
    val onMove: (from: Int, to: Int) -> Unit,
    val onRemove: (Int) -> Unit,
    val onAddSongs: () -> Unit,
    val onSaveAsPlaylist: () -> Unit,
)

@Composable
fun QueuePanel(
    state: PlayerUiState,
    atmosphere: Atmosphere,
    topInset: Dp,
    bottomInset: Dp,
    actions: QueueActions,
    modifier: Modifier = Modifier,
) {
    var items by remember { mutableStateOf(state.queue) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    // The player's queue as it was when the drag began; a drop is only valid against that version.
    var queueAtDragStart by remember { mutableStateOf<List<QueueItem>?>(null) }
    val currentQueue by rememberUpdatedState(state.queue)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentIndex.coerceAtLeast(0))
    val reorder = rememberReorderState(
        listState = listState,
        onMove = { fromKey, toKey ->
            val from = items.indexOfFirst { it.key == fromKey }
            val to = items.indexOfFirst { it.key == toKey }
            if (from >= 0 && to >= 0) {
                if (queueAtDragStart == null) queueAtDragStart = currentQueue
                draggedKey = fromKey as String
                items = items.toMutableList().apply { add(to, removeAt(from)) }
            }
        },
        onDragFinished = {
            val key = draggedKey
            val snapshot = queueAtDragStart
            if (snapshot != null && snapshot == currentQueue) {
                val from = snapshot.indexOfFirst { it.key == key }
                val to = items.indexOfFirst { it.key == key }
                if (from >= 0 && to >= 0 && from != to) actions.onMove(from, to)
            } else {
                // The queue changed underneath the drag (skip, removal…): resync instead of guessing.
                items = currentQueue
            }
            draggedKey = null
            queueAtDragStart = null
        },
    )
    LaunchedEffect(state.queue) {
        if (reorder.draggingKey == null) items = state.queue
    }
    val currentKey = state.queue.getOrNull(state.currentIndex)?.key

    Column(
        modifier
            .fillMaxSize()
            .background(atmosphere.background)
            .padding(top = topInset),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.x2, top = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.queue_title),
                style = AuraTheme.type.headline,
                color = AuraTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            AuraIconButton(AuraIcons.ChevronDown, stringResource(R.string.queue_close), actions.onClose)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            AuraButton(
                stringResource(R.string.queue_add),
                actions.onAddSongs,
                icon = AuraIcons.Plus,
                style = ButtonStyle.Secondary,
            )
            AuraButton(
                stringResource(R.string.queue_save),
                actions.onSaveAsPlaylist,
                icon = AuraIcons.Save,
                style = ButtonStyle.Secondary,
                enabled = state.queue.isNotEmpty(),
            )
        }
        if (state.shuffle) {
            Text(
                stringResource(R.string.queue_shuffle_note),
                style = AuraTheme.type.bodySmall,
                color = AuraTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x1),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, _ -> REORDERABLE_CONTENT_TYPE }) { index, item ->
                QueueRow(
                    item = item,
                    isCurrent = item.key == currentKey,
                    isPlaying = state.isPlaying,
                    accent = atmosphere.accent,
                    onClick = { state.queue.indexOfFirst { it.key == item.key }.takeIf { it >= 0 }?.let(actions.onSkipTo) },
                    onRemove = { state.queue.indexOfFirst { it.key == item.key }.takeIf { it >= 0 }?.let(actions.onRemove) },
                    handle = Modifier.dragHandle(reorder, item.key),
                    modifier = reorderableItem(reorder, item.key)
                        .background(if (reorder.draggingKey == item.key) AuraTheme.colors.surfaceElevated else androidx.compose.ui.graphics.Color.Transparent)
                        .reorderAccessibility(
                            canMoveUp = index > 0,
                            canMoveDown = index < items.lastIndex,
                            onMoveUp = { actions.onMove(index, index - 1) },
                            onMoveDown = { actions.onMove(index, index + 1) },
                        ),
                )
                if (index == items.lastIndex) Spacer(Modifier.height(bottomInset + Spacing.x4))
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    handle: Modifier,
    modifier: Modifier,
) {
    val reorderLabel = stringResource(R.string.cd_reorder, item.track.title)
    TrackRow(
        track = item.track,
        onClick = onClick,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        accent = accent,
        modifier = modifier,
        trailing = {
            if (!isCurrent) {
                AuraIconButton(
                    AuraIcons.Close,
                    stringResource(R.string.cd_remove_from_queue, item.track.title),
                    onRemove,
                    tint = AuraTheme.colors.textTertiary,
                    iconSize = Sizes.iconSmall,
                )
            }
            Box(
                handle
                    .size(Sizes.touchTarget)
                    .semantics { contentDescription = reorderLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AuraIcons.DragHandle, contentDescription = null, tint = AuraTheme.colors.textTertiary, modifier = Modifier.size(Sizes.iconSmall))
            }
        },
    )
}
