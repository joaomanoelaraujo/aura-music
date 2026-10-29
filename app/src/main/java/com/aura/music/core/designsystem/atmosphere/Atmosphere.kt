package com.aura.music.core.designsystem.atmosphere

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.image.ArtworkUpdates
import com.aura.music.core.model.ArtworkSource

@Immutable
data class Atmosphere(
    val background: Color,
    val glow: Color,
    val accent: Color,
    val onAccent: Color,
) {
    companion object {
        val Neutral = AtmosphereColorMath.Neutral.toAtmosphere()
    }
}

fun AtmosphereColors.toAtmosphere() = Atmosphere(Color(background), Color(glow), Color(accent), Color(onAccent))

val LocalAtmosphereEngine = staticCompositionLocalOf<AtmosphereEngine?> { null }

/** Whether the user enabled Adaptive Atmosphere in settings. */
val LocalAtmosphereEnabled = staticCompositionLocalOf { true }

/** 0..1: how far artwork colors move away from AURA's neutral palette. */
val LocalAtmosphereIntensity = staticCompositionLocalOf { 1f }

/** 0..1: strength of the atmospheric glow and gradients. */
val LocalGradientIntensity = staticCompositionLocalOf { 1f }

/**
 * Resolves the atmosphere for [source] and animates between palettes when it changes.
 * Falls back to AURA's neutral identity when there is no artwork or the feature is disabled.
 */
@Composable
fun rememberAtmosphere(source: ArtworkSource?): Atmosphere {
    val engine = LocalAtmosphereEngine.current
    val enabled = LocalAtmosphereEnabled.current
    val intensity = LocalAtmosphereIntensity.current
    val light = AuraTheme.colors.isLight
    val reduced = LocalReducedMotion.current
    fun resolve(colors: AtmosphereColors?): Atmosphere {
        val neutral = if (light) AtmosphereColorMath.NeutralLight else AtmosphereColorMath.Neutral
        val themed = colors?.let { if (light) AtmosphereColorMath.lightVariant(it) else it } ?: neutral
        return AtmosphereColorMath.blend(neutral, themed, if (enabled) intensity else 0f).toAtmosphere()
    }
    var target by remember(light) {
        mutableStateOf(resolve(source?.let { engine?.cached(it) }))
    }
    val artworkVersion = ArtworkUpdates.version
    LaunchedEffect(source?.albumId, enabled, engine, intensity, light, artworkVersion) {
        target = if (!enabled || source == null || engine == null) resolve(null) else resolve(engine.colorsFor(source))
    }
    val spec = Motion.tween<Color>(Motion.ATMOSPHERE, reduced)
    val background by animateColorAsState(target.background, spec, label = "atmosphereBackground")
    val glow by animateColorAsState(target.glow, spec, label = "atmosphereGlow")
    val accent by animateColorAsState(target.accent, spec, label = "atmosphereAccent")
    val onAccent by animateColorAsState(target.onAccent, spec, label = "atmosphereOnAccent")
    return Atmosphere(background, glow, accent, onAccent)
}

/**
 * Paints the atmospheric backdrop: the tinted base, a soft glow behind the artwork area
 * and a fade back to the base so content below stays calm and legible.
 */
fun Modifier.atmosphereBackground(atmosphere: Atmosphere, glowCenterFraction: Float = 0.22f, glowStrength: Float = 1f): Modifier =
    drawBehind {
        drawRect(atmosphere.background)
        if (glowStrength <= 0f) return@drawBehind
        drawRect(
            Brush.radialGradient(
                colors = listOf(atmosphere.glow.copy(alpha = 0.55f * glowStrength), atmosphere.glow.copy(alpha = 0.18f * glowStrength), Color.Transparent),
                center = Offset(size.width / 2f, size.height * glowCenterFraction),
                radius = size.maxDimension * 0.62f,
            ),
        )
        drawRect(
            Brush.verticalGradient(
                0.45f to Color.Transparent,
                1f to atmosphere.background,
            ),
        )
    }
