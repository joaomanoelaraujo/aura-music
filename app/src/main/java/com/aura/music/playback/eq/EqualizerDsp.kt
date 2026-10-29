package com.aura.music.playback.eq

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** AURA's 10-band graphic equalizer: one band per octave, ±12 dB. */
object EqBands {
    val centersHz = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    const val MIN_DB = -12f
    const val MAX_DB = 12f
    val count get() = centersHz.size
}

/** Ready-made curves (dB per band, low to high). */
enum class EqPreset(val gainsDb: FloatArray) {
    Flat(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BassBoost(floatArrayOf(7f, 6f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)),
    Rock(floatArrayOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
    Pop(floatArrayOf(-1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f, -1f, -1f)),
    Jazz(floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
    Classical(floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
    Vocal(floatArrayOf(-2f, -2f, -1f, 1f, 3f, 4f, 4f, 2f, 0f, -1f)),
    Electronic(floatArrayOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f)),
    Acoustic(floatArrayOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 2f, 2f, 1f)),
    TrebleBoost(floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 6f, 7f)),
}

/**
 * The equalizer's signal processing, independent of the player: a cascade of peaking biquad
 * filters per channel (each slider is exact at its own frequency), with automatic headroom so boosting doesn't
 * clip, and a gentle limiter for what's left. Coefficients follow the Audio EQ Cookbook (R. B-J).
 */
class EqualizerDsp(private val sampleRate: Int, private val channels: Int) {

    private class Biquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

    private var filters: Array<Biquad> = emptyArray()
    /** Per channel and filter: the two state values of the transposed direct form II. */
    private var state = FloatArray(0)
    private var gain = 1f
    var active = false
        private set

    /** Applies new band gains (dB); filter memory is kept so changes don't click. */
    fun setGains(gainsDb: FloatArray, enabled: Boolean) {
        active = enabled && gainsDb.any { abs(it) > 0.05f }
        if (!active) return
        val nyquist = sampleRate / 2.0
        filters = EqBands.centersHz.indices
            .filter { EqBands.centersHz[it] < nyquist * 0.9 }
            .map { band ->
                val db = gainsDb.getOrElse(band) { 0f }.coerceIn(EqBands.MIN_DB, EqBands.MAX_DB).toDouble()
                peaking(EqBands.centersHz[band].toDouble(), db, Q)
            }.toTypedArray()
        if (state.size != filters.size * channels * 2) state = FloatArray(filters.size * channels * 2)
        // Neighbouring boosted bands add up; leaving headroom for most of the peak avoids clipping.
        val peak = max(0f, gainsDb.maxOrNull() ?: 0f)
        gain = 10f.pow(-peak * HEADROOM / 20f)
    }

    fun reset() {
        state.fill(0f)
    }

    /** Processes interleaved samples in place, in -1..1. */
    fun process(samples: FloatArray, count: Int) {
        if (!active) return
        val f = filters
        val s = state
        var i = 0
        while (i < count) {
            for (ch in 0 until channels) {
                var x = samples[i + ch] * gain
                var base = ch * f.size * 2
                for (q in f) {
                    val y = q.b0 * x + s[base]
                    s[base] = q.b1 * x - q.a1 * y + s[base + 1]
                    s[base + 1] = q.b2 * x - q.a2 * y
                    x = y
                    base += 2
                }
                samples[i + ch] = limit(x)
            }
            i += channels
        }
        // Denormal numbers make old phones crawl through silence; flush the tiny remainders.
        for (k in s.indices) if (abs(s[k]) < 1e-20f) s[k] = 0f
    }

    private fun peaking(hz: Double, db: Double, q: Double): Biquad {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * hz / sampleRate
        val alpha = sin(w) / (2 * q)
        val c = cos(w)
        return normalized(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

    private companion object {
        /** One octave per band. */
        const val Q = 1.41

        /** Share of the highest boost given back as headroom. */
        const val HEADROOM = 0.75f

        /** Above this level the limiter starts to round peaks off smoothly. */
        const val KNEE = 0.9f

        fun limit(x: Float): Float {
            val m = abs(x)
            if (m <= KNEE) return x
            val over = (m - KNEE) / (1 - KNEE)
            val y = KNEE + (1 - KNEE) * tanh(over.toDouble()).toFloat()
            return if (x < 0) -y else y
        }
    }
}
