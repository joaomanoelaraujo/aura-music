package com.aura.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp
import com.aura.music.core.designsystem.atmosphere.AtmosphereColors

/** The same dark palette as AURA for Android (core/designsystem/theme/Color.kt). */
@Immutable
data class AuraColors(
    val background: Color = Color(0xFF101114),
    val surface: Color = Color(0xFF191B20),
    val surfaceElevated: Color = Color(0xFF23262D),
    val surfaceHighest: Color = Color(0xFF2D3038),
    val textPrimary: Color = Color(0xFFF5F5F7),
    val textSecondary: Color = Color(0xFF989AA3),
    val textTertiary: Color = Color(0xFF6B6D76),
    val outline: Color = Color(0x1FFFFFFF),
    val accent: Color = Color(0xFFC7C2EE),
    val onAccent: Color = Color(0xFF14131C),
    val danger: Color = Color(0xFFEE8E8A),
    /** Tint of the soft light behind the content, taken from the current cover. */
    val glow: Color = Color(0xFF786CD2),
)

val LocalAuraColors = staticCompositionLocalOf { AuraColors() }

object Aura {
    val colors: AuraColors @Composable get() = LocalAuraColors.current
}

private fun manrope(weight: Int) = Font(
    resource = "fonts/manrope_variable.ttf",
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Body text: Manrope (variable), the same family as the Android app. */
val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Display text: Instrument Serif, used for titles as on Android and the website. */
val InstrumentSerif = FontFamily(Font(resource = "fonts/instrument_serif_regular.ttf", weight = FontWeight.Normal))

object AuraType {
    val display = TextStyle(fontFamily = InstrumentSerif, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp)
    val title = TextStyle(fontFamily = InstrumentSerif, fontSize = 30.sp, lineHeight = 34.sp)
    val heading = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 15.sp, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 14.sp, lineHeight = 20.sp)
    val bodyStrong = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp)
    val label = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp)
}

/**
 * Applies the palette, recoloured by the current cover's [atmosphere] (Adaptive Atmosphere):
 * the accent and glow follow the artwork, always with the contrast the shared colour math enforces.
 */
@Composable
fun AuraDesktopTheme(atmosphere: AtmosphereColors?, content: @Composable () -> Unit) {
    val base = AuraColors()
    val accent by animateColorAsState(atmosphere?.accent?.let(::Color) ?: base.accent, tween(900))
    val onAccent by animateColorAsState(atmosphere?.onAccent?.let(::Color) ?: base.onAccent, tween(900))
    val glow by animateColorAsState(atmosphere?.glow?.let(::Color) ?: base.glow, tween(900))
    val colors = base.copy(accent = accent, onAccent = onAccent, glow = glow)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.outline,
        ),
    ) {
        CompositionLocalProvider(LocalAuraColors provides colors, content = content)
    }
}
