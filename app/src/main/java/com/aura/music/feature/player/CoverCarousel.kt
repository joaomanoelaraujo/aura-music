package com.aura.music.feature.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.model.Track
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pure decision for a released cover swipe; kept separate so it can be unit tested. */
object CoverSwipe {
    enum class Outcome { Next, Previous, Cancel }

    /** Share of the cover width that commits a change without a fling. */
    const val DISTANCE_FRACTION = 0.28f

    /** A fling must also have moved a little, so a twitch never changes the song. */
    const val FLING_MIN_FRACTION = 0.06f

    fun decide(
        offsetPx: Float,
        velocityPx: Float,
        widthPx: Float,
        flingVelocityPx: Float,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): Outcome {
        val minFlingDistance = widthPx * FLING_MIN_FRACTION
        val towardsNext = offsetPx < 0
        val farEnough = abs(offsetPx) >= widthPx * DISTANCE_FRACTION
        val flungForward = abs(velocityPx) >= flingVelocityPx && abs(offsetPx) >= minFlingDistance &&
            (velocityPx < 0) == towardsNext
        // A strong fling back towards the centre always cancels, however far the cover travelled.
        val flungBack = abs(velocityPx) >= flingVelocityPx && (velocityPx < 0) != towardsNext
        if (flungBack || !(farEnough || flungForward)) return Outcome.Cancel
        return when {
            towardsNext && hasNext -> Outcome.Next
            !towardsNext && hasPrevious -> Outcome.Previous
            else -> Outcome.Cancel
        }
    }
}

private val CoverGap = 24.dp
private const val EDGE_RESISTANCE = 0.3f
private const val CONFIRMATION_TIMEOUT_MS = 900L

/**
 * Horizontally swipeable cover. The cover follows the finger; neighbours (the real previous and
 * next items from Media3, honouring shuffle and repeat) wait just off each side. Past the
 * threshold the incoming cover slides to the centre and only then is the player asked to move,
 * so the visual and the audio change together. Releasing early springs back.
 */
@Composable
fun CoverCarousel(
    current: Track,
    previous: Track?,
    next: Track?,
    enabled: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    cover: @Composable (track: Track, modifier: Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reduced = LocalReducedMotion.current
    val gapPx = with(LocalDensity.current) { CoverGap.toPx() }
    val flingVelocityPx = with(LocalDensity.current) { 700.dp.toPx() }
    val offset = remember { Animatable(0f) }
    // Id of the track we committed to; the cover stays parked until the player confirms it.
    var pendingId by remember { mutableStateOf<Long?>(null) }
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)

    LaunchedEffect(current.id) {
        if (pendingId == current.id || pendingId == null) {
            offset.snapTo(0f)
            pendingId = null
        }
    }
    LaunchedEffect(pendingId) {
        if (pendingId == null) return@LaunchedEffect
        delay(CONFIRMATION_TIMEOUT_MS)
        // The player didn't move (end of queue, error): return the cover gracefully.
        pendingId = null
        offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f))
    }

    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val step = width + gapPx
        // Once the player has switched, the incoming cover *is* the current one: show it centred.
        val displayedOffset = { if (pendingId != null && current.id == pendingId) 0f else offset.value }

        val gesture = if (!enabled) {
            Modifier
        } else {
            Modifier.pointerInput(previous?.id, next?.id, step) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        if (pendingId != null) return@detectHorizontalDragGestures
                        val velocity = tracker.calculateVelocity().x
                        val outcome = CoverSwipe.decide(offset.value, velocity, width, flingVelocityPx, previous != null, next != null)
                        val target = when (outcome) {
                            CoverSwipe.Outcome.Next -> next
                            CoverSwipe.Outcome.Previous -> previous
                            CoverSwipe.Outcome.Cancel -> null
                        }
                        scope.launch {
                            if (target == null) {
                                offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f), velocity)
                                return@launch
                            }
                            pendingId = target.id
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            val destination = if (outcome == CoverSwipe.Outcome.Next) -step else step
                            if (reduced) offset.snapTo(destination) else offset.animateTo(destination, spring(dampingRatio = 1f, stiffness = 520f), velocity)
                            if (outcome == CoverSwipe.Outcome.Next) currentOnNext() else currentOnPrevious()
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f)) } },
                ) { change, dragAmount ->
                    if (pendingId != null) return@detectHorizontalDragGestures
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val proposed = offset.value + dragAmount
                    val blocked = (proposed < 0 && next == null) || (proposed > 0 && previous == null)
                    val value = if (blocked) offset.value + dragAmount * EDGE_RESISTANCE else proposed
                    scope.launch { offset.snapTo(value.coerceIn(-step, step)) }
                }
            }
        }

        Box(Modifier.fillMaxSize().then(gesture)) {
            if (enabled) {
                previous?.let { track ->
                    cover(track, Modifier.fillMaxSize().graphicsLayer { neighbour(displayedOffset(), -step, step) })
                }
                next?.let { track ->
                    cover(track, Modifier.fillMaxSize().graphicsLayer { neighbour(displayedOffset(), step, step) })
                }
            }
            cover(
                current,
                Modifier.fillMaxSize().graphicsLayer {
                    val o = displayedOffset()
                    translationX = o
                    val scale = 1f - 0.04f * (abs(o) / step).coerceIn(0f, 1f)
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }
}

/** Places a neighbour at [base] (±step) and reveals it as it approaches the centre. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.neighbour(offset: Float, base: Float, step: Float) {
    translationX = base + offset
    val progress = (abs(offset) / step).coerceIn(0f, 1f)
    // Only the neighbour on the side being revealed is visible.
    alpha = if ((base > 0 && offset < 0) || (base < 0 && offset > 0)) progress else 0f
    val scale = 0.96f + 0.04f * progress
    scaleX = scale
    scaleY = scale
}
