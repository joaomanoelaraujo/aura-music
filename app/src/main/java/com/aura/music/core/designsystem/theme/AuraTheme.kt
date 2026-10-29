package com.aura.music.core.designsystem.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.aura.music.core.model.CornerStyle
import com.aura.music.core.model.ListDensity
import com.aura.music.core.model.MotionLevel
import com.aura.music.core.model.ThemeMode

object AuraTheme {
    val colors: AuraColors
        @Composable @ReadOnlyComposable get() = LocalAuraColors.current

    val type: AuraTypography
        @Composable @ReadOnlyComposable get() = LocalAuraTypography.current
}

/** Visual preferences that shape the theme; defaults reproduce AURA's original look. */
data class ThemePreferences(
    val mode: ThemeMode = ThemeMode.Dark,
    val motion: MotionLevel = MotionLevel.Full,
    val corners: CornerStyle = CornerStyle.Soft,
    val density: ListDensity = ListDensity.Comfortable,
    val textScale: Float = 1f,
)

/**
 * AURA's theme. Material 3 is kept underneath only so platform components (sheets, dialogs,
 * text fields) inherit AURA's palette; visual identity comes from the Aura tokens.
 *
 * The user's text size multiplies the system font scale, so accessibility settings still apply.
 */
@Composable
fun AuraTheme(
    preferences: ThemePreferences = ThemePreferences(),
    content: @Composable () -> Unit,
) {
    val dark = when (preferences.mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val colors = if (dark) AuraDarkColors else AuraLightColors
    val context = LocalContext.current
    val systemReducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val level = if (systemReducedMotion) MotionLevel.Off else preferences.motion
    Motion.durationScale = if (level == MotionLevel.Reduced) REDUCED_DURATION_SCALE else 1f
    val baseDensity = LocalDensity.current
    val density = remember(baseDensity, preferences.textScale) {
        Density(baseDensity.density, baseDensity.fontScale * preferences.textScale)
    }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalCornerScale provides preferences.corners.scale,
        LocalListDensity provides preferences.density,
        LocalReducedMotion provides (level == MotionLevel.Off),
        LocalDecorativeMotion provides (level == MotionLevel.Full),
    ) {
        AuraColorScope(colors, content)
    }
}

/**
 * Forces AURA's dark palette for immersive surfaces (the player, which sits on the album's
 * atmosphere) regardless of the app theme.
 */
@Composable
fun AuraDarkSurface(content: @Composable () -> Unit) {
    if (!AuraTheme.colors.isLight) content() else AuraColorScope(AuraDarkColors, content)
}

@Composable
private fun AuraColorScope(colors: AuraColors, content: @Composable () -> Unit) {
    val materialScheme = remember(colors) { colors.toMaterial() }
    val materialType = remember {
        Typography(
            bodyLarge = AuraType.body,
            bodyMedium = AuraType.bodySmall,
            titleLarge = AuraType.headline,
            titleMedium = AuraType.title,
            labelLarge = AuraType.label,
        )
    }
    MaterialTheme(colorScheme = materialScheme, typography = materialType) {
        CompositionLocalProvider(
            LocalAuraColors provides colors,
            LocalAuraTypography provides AuraType,
            LocalRippleConfiguration provides RippleConfiguration(
                color = colors.textPrimary,
                rippleAlpha = RippleAlpha(0.08f, 0.10f, 0.06f, 0.10f),
            ),
            LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)),
            content = content,
        )
    }
}

private fun AuraColors.toMaterial(): ColorScheme {
    val base = if (isLight) lightColorScheme() else darkColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        secondary = textSecondary,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceElevated,
        surfaceContainerHighest = surfaceHighest,
        outline = outline,
        outlineVariant = outline,
        error = danger,
        scrim = scrim,
    )
}

private const val REDUCED_DURATION_SCALE = 0.6f
