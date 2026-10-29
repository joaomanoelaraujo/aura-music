package com.aura.music.core.designsystem.atmosphere

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A color sampled from artwork together with how much of the image it covers. */
data class Swatch(val rgb: Int, val population: Int)

/** ARGB colors derived from a piece of artwork. */
data class AtmosphereColors(
    val background: Int,
    val glow: Int,
    val accent: Int,
    val onAccent: Int,
)

/**
 * Turns raw artwork swatches into a restrained, legible palette:
 * a near-black tinted background, a muted glow and an accent that always
 * meets WCAG AA contrast against the background.
 */
object AtmosphereColorMath {

    const val MIN_ACCENT_CONTRAST = 4.5
    const val MAX_ACCENT_SATURATION = 0.52f
    const val BACKGROUND_LIGHTNESS = 0.085f
    private const val MAX_BACKGROUND_SATURATION = 0.26f
    private const val GRAYSCALE_THRESHOLD = 0.09f

    val Neutral = AtmosphereColors(
        background = 0xFF101114.toInt(),
        glow = 0xFF2A2B33.toInt(),
        accent = 0xFFC7C2EE.toInt(),
        onAccent = 0xFF14131C.toInt(),
    )

    fun derive(swatches: List<Swatch>): AtmosphereColors {
        val seed = pickSeed(swatches) ?: return Neutral
        val hsl = toHsl(seed)
        val isGrayscale = hsl[1] < GRAYSCALE_THRESHOLD
        val hue = hsl[0]
        val saturation = if (isGrayscale) 0f else hsl[1]

        val background = fromHsl(hue, min(saturation * 0.55f, MAX_BACKGROUND_SATURATION), BACKGROUND_LIGHTNESS)
        val glow = fromHsl(hue, min(saturation * 0.8f, 0.42f), 0.27f)
        val accent = legibleAccent(hue, saturation.coerceIn(if (isGrayscale) 0f else 0.22f, MAX_ACCENT_SATURATION), background)
        return AtmosphereColors(background, glow, accent, onAccentFor(accent, hue))
    }

    val NeutralLight = AtmosphereColors(
        background = 0xFFF4F3F6.toInt(),
        glow = 0xFFDCD9EC.toInt(),
        accent = 0xFF5B54A8.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
    )

    /**
     * The same artwork identity for light surfaces: a barely tinted pale ground, a soft glow and
     * an accent darkened until it reaches AA contrast against that ground.
     */
    fun lightVariant(dark: AtmosphereColors): AtmosphereColors {
        if (dark == Neutral) return NeutralLight
        val hsl = toHsl(dark.accent)
        val hue = hsl[0]
        val saturation = if (hsl[1] < GRAYSCALE_THRESHOLD) 0f else hsl[1]
        val background = fromHsl(hue, min(saturation * 0.4f, 0.24f), 0.955f)
        val glow = fromHsl(hue, min(saturation * 0.7f, 0.45f), 0.82f)
        var lightness = 0.42f
        var accent = fromHsl(hue, saturation.coerceAtLeast(if (saturation == 0f) 0f else 0.3f), lightness)
        while (contrastRatio(accent, background) < MIN_ACCENT_CONTRAST && lightness > 0.08f) {
            lightness -= 0.03f
            accent = fromHsl(hue, saturation.coerceAtLeast(if (saturation == 0f) 0f else 0.3f), lightness)
        }
        val white = 0xFFFFFFFF.toInt()
        val onAccent = if (contrastRatio(accent, white) >= MIN_ACCENT_CONTRAST) white else fromHsl(hue, 0.18f, 0.08f)
        return AtmosphereColors(background, glow, accent, onAccent)
    }

    /** Prefers well-represented, moderately saturated mid-tones over large dull areas. */
    internal fun pickSeed(swatches: List<Swatch>): Int? = swatches
        .filter { it.population > 0 }
        .maxByOrNull { swatch ->
            val (_, s, l) = toHsl(swatch.rgb).let { Triple(it[0], it[1], it[2]) }
            swatch.population * (0.3f + s) * (1f - abs(l - 0.5f))
        }?.rgb

    private fun legibleAccent(hue: Float, saturation: Float, background: Int): Int {
        var lightness = 0.70f
        var accent = fromHsl(hue, saturation, lightness)
        while (contrastRatio(accent, background) < MIN_ACCENT_CONTRAST && lightness < 0.95f) {
            lightness += 0.03f
            accent = fromHsl(hue, saturation, lightness)
        }
        return accent
    }

    private fun onAccentFor(accent: Int, hue: Float): Int {
        val dark = fromHsl(hue, 0.18f, 0.08f)
        return if (contrastRatio(accent, dark) >= MIN_ACCENT_CONTRAST) dark else 0xFFFFFFFF.toInt()
    }

    /**
     * Interpolates every role from [from] to [to]. The accent is re-checked so a partial blend
     * never drops below AA contrast: if it would, the fully derived accent is kept.
     */
    fun blend(from: AtmosphereColors, to: AtmosphereColors, amount: Float): AtmosphereColors {
        val t = amount.coerceIn(0f, 1f)
        if (t >= 1f) return to
        if (t <= 0f) return from
        val background = mix(from.background, to.background, t)
        val accent = mix(from.accent, to.accent, t).takeIf { contrastRatio(it, background) >= MIN_ACCENT_CONTRAST } ?: to.accent
        val onAccent = if (contrastRatio(accent, to.onAccent) >= MIN_ACCENT_CONTRAST) to.onAccent else from.onAccent
        return AtmosphereColors(background, mix(from.glow, to.glow, t), accent, onAccent)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a) + 0.05
        val lb = relativeLuminance(b) + 0.05
        return max(la, lb) / min(la, lb)
    }

    fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color shr 16 and 0xFF) +
            0.7152 * channel(color shr 8 and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    /** Returns [hue 0..360, saturation 0..1, lightness 0..1]. */
    fun toHsl(color: Int): FloatArray {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val l = (maxC + minC) / 2f
        if (delta == 0f) return floatArrayOf(0f, 0f, l)
        val s = delta / (1f - abs(2f * l - 1f))
        val h = when (maxC) {
            r -> 60f * (((g - b) / delta).mod(6f))
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return floatArrayOf(h, s.coerceIn(0f, 1f), l)
    }

    fun fromHsl(hue: Float, saturation: Float, lightness: Float): Int {
        val c = (1f - abs(2f * lightness - 1f)) * saturation
        val hPrime = (hue.mod(360f)) / 60f
        val x = c * (1f - abs(hPrime.mod(2f) - 1f))
        val (r1, g1, b1) = when (hPrime.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = lightness - c / 2f
        fun channel(v: Float) = ((v + m).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
    }
}
