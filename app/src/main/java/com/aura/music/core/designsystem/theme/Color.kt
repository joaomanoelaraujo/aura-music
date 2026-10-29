package com.aura.music.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic color roles. Screens never reference raw hex values directly.
 * Elevation is tonal rather than shadow-based: background → surface → surfaceElevated →
 * surfaceHighest are the four elevation levels, each a little lighter than the last.
 */
@Immutable
data class AuraColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceHighest: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val scrim: Color,
    val isLight: Boolean = false,
)

val AuraDarkColors = AuraColors(
    background = Color(0xFF101114),
    surface = Color(0xFF191B20),
    surfaceElevated = Color(0xFF23262D),
    surfaceHighest = Color(0xFF2D3038),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF989AA3),
    textTertiary = Color(0xFF6B6D76),
    outline = Color(0x1FFFFFFF),
    accent = Color(0xFFC7C2EE),
    onAccent = Color(0xFF14131C),
    danger = Color(0xFFEE8E8A),
    scrim = Color(0x99000000),
)

/** Light palette: warm off-white ground, white surfaces, deep lavender accent (6.5:1 on white). */
val AuraLightColors = AuraColors(
    background = Color(0xFFF4F3F6),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEBEAF0),
    surfaceHighest = Color(0xFFE1E0E8),
    textPrimary = Color(0xFF15161A),
    textSecondary = Color(0xFF575963),
    textTertiary = Color(0xFF85878F),
    outline = Color(0x1F000000),
    accent = Color(0xFF5B54A8),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFB3261E),
    scrim = Color(0x66000000),
    isLight = true,
)

internal val LocalAuraColors = staticCompositionLocalOf { AuraDarkColors }
