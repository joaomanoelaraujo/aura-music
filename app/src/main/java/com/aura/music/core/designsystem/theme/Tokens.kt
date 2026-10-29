package com.aura.music.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.aura.music.core.model.ListDensity
import androidx.compose.ui.unit.dp

/** 4 dp spacing scale. */
object Spacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp
    val x10 = 40.dp
    val x12 = 48.dp
    val x16 = 64.dp

    /** Horizontal page margin shared by every screen. */
    val gutter = x5
}

/** Corner shapes, scaled by the user's corner style (sharp, soft, round). */
object Radius {
    val small: RoundedCornerShape @Composable @ReadOnlyComposable get() = scaled(12)
    val medium: RoundedCornerShape @Composable @ReadOnlyComposable get() = scaled(16)
    val large: RoundedCornerShape @Composable @ReadOnlyComposable get() = scaled(20)
    val xLarge: RoundedCornerShape @Composable @ReadOnlyComposable get() = scaled(24)
    val pill = RoundedCornerShape(percent = 50)

    @Composable
    @ReadOnlyComposable
    private fun scaled(dp: Int) = RoundedCornerShape((dp * LocalCornerScale.current).dp)
}

val LocalCornerScale = staticCompositionLocalOf { 1f }

/** Comfortable or compact list rows. */
val LocalListDensity = staticCompositionLocalOf { ListDensity.Comfortable }

object Sizes {
    val touchTarget = 48.dp
    val icon = 24.dp
    val iconSmall = 20.dp
    val rowArtwork = 48.dp
    val miniPlayerHeight = 64.dp
    val bottomBarHeight = 64.dp
}

object Motion {
    /** Set by the theme: below 1 when the user prefers shorter, calmer transitions. */
    @Volatile var durationScale: Float = 1f

    const val SHORT = 160
    const val MEDIUM = 280
    const val LONG = 420
    const val ATMOSPHERE = 750

    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Tween that collapses to an instant change when the user prefers reduced motion. */
    fun <T> tween(durationMs: Int, reduced: Boolean, easing: Easing = Standard): FiniteAnimationSpec<T> =
        if (reduced) snap() else androidx.compose.animation.core.tween((durationMs * durationScale).toInt(), easing = easing)

    /** Critically damped spring: responsive without bounce. */
    fun <T> settle(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 420f)
}

/** True when animations should be minimised (system setting or in-app preference). */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** False when decorative, looping motion (marquees, equalizer bars, pulses) should stay still. */
val LocalDecorativeMotion = staticCompositionLocalOf { true }
