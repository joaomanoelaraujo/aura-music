package com.aura.music.core.designsystem.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import com.aura.music.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** Lazy-list content type that marks items as draggable. */
const val REORDERABLE_CONTENT_TYPE = "aura.reorderable"

/**
 * Drag-to-reorder for LazyColumn without extra dependencies. Items are identified by key so
 * headers and other non-movable rows never interfere; [onMove] receives keys of the dragged
 * item and the item it should take the place of.
 */
@Stable
class ReorderState(
    private val listState: LazyListState,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
    private val onDragFinished: () -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var dragOffset by mutableFloatStateOf(0f)
        private set

    private val draggedInfo: LazyListItemInfo?
        get() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    fun start(key: Any) {
        draggingKey = key
        dragOffset = 0f
    }

    fun drag(delta: Float) {
        val dragged = draggedInfo ?: return
        dragOffset += delta
        val center = dragged.offset + dragOffset + dragged.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != dragged.key &&
                item.contentType == REORDERABLE_CONTENT_TYPE &&
                center.toInt() in item.offset..(item.offset + item.size)
        } ?: return
        dragOffset += dragged.offset - target.offset
        onMove(dragged.key, target.key)
    }

    fun end() {
        if (draggingKey != null) onDragFinished()
        draggingKey = null
        dragOffset = 0f
    }

    /** Pixels the list should scroll by to keep the dragged item visible near the edges. */
    internal fun overscroll(): Float {
        val dragged = draggedInfo ?: return 0f
        val viewportStart = listState.layoutInfo.viewportStartOffset
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val top = dragged.offset + dragOffset
        val bottom = top + dragged.size
        return when {
            dragOffset < 0 && top < viewportStart + EDGE -> -SCROLL_STEP
            dragOffset > 0 && bottom > viewportEnd - EDGE -> SCROLL_STEP
            else -> 0f
        }
    }

    private companion object {
        const val EDGE = 80
        const val SCROLL_STEP = 18f
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    onMove: (fromKey: Any, toKey: Any) -> Unit,
    onDragFinished: () -> Unit = {},
): ReorderState {
    val move by rememberUpdatedState(onMove)
    val finished by rememberUpdatedState(onDragFinished)
    val state = remember(listState) { ReorderState(listState, { a, b -> move(a, b) }, { finished() }) }
    LaunchedEffect(state) {
        while (true) {
            snapshotFlow { state.draggingKey }.filter { it != null }.first()
            while (state.draggingKey != null) {
                val delta = state.overscroll()
                if (delta != 0f) {
                    val consumed = listState.scrollBy(delta)
                    state.drag(consumed)
                }
                delay(16)
            }
        }
    }
    return state
}

/** Applies the lifted appearance to the dragged row and smooth placement animation to the others. */
@Composable
fun LazyItemScope.reorderableItem(state: ReorderState, key: Any): Modifier {
    val isDragging = state.draggingKey == key
    return if (isDragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.dragOffset
                scaleX = 1.02f
                scaleY = 1.02f
            }
    } else {
        Modifier.animateItem()
    }
}

/** Drag handle gesture: attach to the handle icon of each row. */
fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDrag = { change, amount ->
            change.consume()
            state.drag(amount.y)
        },
        onDragEnd = state::end,
        onDragCancel = state::end,
    )
}

/** Screen-reader alternative to dragging: "move up" / "move down" actions on each row. */
@Composable
fun Modifier.reorderAccessibility(canMoveUp: Boolean, canMoveDown: Boolean, onMoveUp: () -> Unit, onMoveDown: () -> Unit): Modifier {
    val up = stringResource(R.string.a11y_move_up)
    val down = stringResource(R.string.a11y_move_down)
    return semantics {
        customActions = buildList {
            if (canMoveUp) add(CustomAccessibilityAction(up) { onMoveUp(); true })
            if (canMoveDown) add(CustomAccessibilityAction(down) { onMoveDown(); true })
        }
    }
}
