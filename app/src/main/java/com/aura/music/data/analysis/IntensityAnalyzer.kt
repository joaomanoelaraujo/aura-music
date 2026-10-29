package com.aura.music.data.analysis

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How a song moves over time, for lyric effects: how strong each moment is compared with the rest
 * of the song, where the hits (drums, attacks) fall, and how energetic the song is as a whole.
 * Computed once from the audio itself, offline, and kept on disk.
 */
class IntensityProfile(
    /** Length of each step of [levels], in ms. */
    val hopMs: Int,
    /** Strength of each moment relative to the rest of the song, 0..1 (0 = silence). */
    val levels: FloatArray,
    /** Times (ms) of the strongest hits, ascending. */
    val onsetsMs: IntArray,
    /** How strong each hit is, 0..1. */
    val onsetStrength: FloatArray,
    /** The song as a whole: 0 = calm ballad, 1 = loud, dense, driven (rock, electronic). */
    val energy: Float,
    /** Average loudness while sound plays (dBFS), for playing every song at a similar volume. */
    val loudnessDb: Float,
) {
    fun levelAt(ms: Long): Float = levels.getOrElse((ms / hopMs).toInt()) { 0f }

    /** Average strength of a stretch of the song, e.g. while one lyric line is sung. */
    fun mean(fromMs: Long, toMs: Long): Float {
        val from = (fromMs / hopMs).toInt().coerceIn(0, levels.size)
        val to = (toMs / hopMs).toInt().coerceIn(from, levels.size)
        if (to <= from) return levelAt(fromMs)
        var sum = 0f
        for (i in from until to) sum += levels[i]
        return sum / (to - from)
    }

    /** How much the latest hit still resonates at [ms]: 1 right on a strong hit, fading quickly. */
    fun pulseAt(ms: Long): Float {
        var lo = 0
        var hi = onsetsMs.size - 1
        var last = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (onsetsMs[mid] <= ms) { last = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (last < 0) return 0f
        val since = ms - onsetsMs[last]
        if (since > PULSE_TAIL_MS) return 0f
        return onsetStrength[last] * exp(-since / PULSE_DECAY_MS).toFloat()
    }

    fun write(out: OutputStream) = DataOutputStream(out).run {
        writeByte(FORMAT)
        writeShort(hopMs)
        writeFloat(energy)
        writeFloat(loudnessDb)
        writeInt(levels.size)
        levels.forEach { writeByte((it * 255).roundToInt().coerceIn(0, 255)) }
        writeInt(onsetsMs.size)
        onsetsMs.forEachIndexed { i, ms ->
            writeInt(ms)
            writeByte((onsetStrength[i] * 255).roundToInt().coerceIn(0, 255))
        }
        flush()
    }

    companion object {
        private const val FORMAT = 2
        private const val PULSE_DECAY_MS = 140.0
        private const val PULSE_TAIL_MS = 600

        fun read(input: InputStream): IntensityProfile? = runCatching {
            DataInputStream(input).run {
                if (readByte().toInt() != FORMAT) return null
                val hop = readShort().toInt()
                val energy = readFloat()
                val loudness = readFloat()
                val levels = FloatArray(readInt()) { readUnsignedByte() / 255f }
                val count = readInt()
                val onsets = IntArray(count)
                val strength = FloatArray(count)
                for (i in 0 until count) {
                    onsets[i] = readInt()
                    strength[i] = readUnsignedByte() / 255f
                }
                IntensityProfile(hop, levels, onsets, strength, energy, loudness)
            }
        }.getOrNull()
    }
}

/**
 * Builds an [IntensityProfile] from mono audio fed in chunks (so a whole song never sits in
 * memory). Per 50 ms step it measures loudness, "brightness" (zero crossings: distorted guitars and
 * cymbals are bright) and high-frequency energy, whose jumps mark the hits.
 */
class IntensityAnalyzer(private val sampleRate: Int) {
    private val hopSize = (sampleRate * HOP_MS / 1000).coerceAtLeast(1)
    private val rms = ArrayList<Float>()
    private val highs = ArrayList<Float>()
    private val crossings = ArrayList<Int>()
    private var samples = 0L
    private var hopCrossings = 0

    private var sumSq = 0.0
    private var sumHigh = 0.0
    private var inHop = 0
    private var previous = 0f

    fun feed(mono: FloatArray, count: Int = mono.size) {
        for (i in 0 until count) {
            val x = mono[i]
            val high = x - previous
            if ((x >= 0f) != (previous >= 0f)) hopCrossings++
            previous = x
            sumSq += x * x
            sumHigh += high * high
            if (++inHop == hopSize) {
                rms += sqrt(sumSq / hopSize).toFloat()
                highs += sqrt(sumHigh / hopSize).toFloat()
                crossings += hopCrossings
                hopCrossings = 0
                sumSq = 0.0
                sumHigh = 0.0
                inHop = 0
            }
        }
        samples += count
    }

    fun finish(): IntensityProfile {
        val n = rms.size
        val db = FloatArray(n) { 20 * log10(rms[it] + 1e-9f) }

        // Section level: loudness smoothed over ~1.5 s, placed between the song's calm and loud
        // references (in dB, so a long chorus still reads as loud), averaged with its rank in the
        // song (so a song without dynamics still has its stronger moments).
        val section = smooth(db, SECTION_HOPS)
        val ranked = section.filterIndexed { i, _ -> db[i] > SILENCE_DB }.sorted()
        val loudRef = ranked.getOrElse((ranked.size * 0.98).toInt()) { 0f }
        val range = maxOf(loudRef - ranked.getOrElse((ranked.size * 0.1).toInt()) { 0f }, MIN_RANGE_DB)
        val levels = FloatArray(n) { i ->
            if (db[i] <= SILENCE_DB || ranked.isEmpty()) {
                0f
            } else {
                val scaled = ((section[i] - (loudRef - range)) / range).coerceIn(0f, 1f)
                (scaled + rank(ranked, section[i])) / 2
            }
        }

        // Hits: sudden rises of loudness or of high-frequency energy (a bass drum shows in the
        // first, a snare or a pick attack in the second), above what's usual around them.
        val odf = FloatArray(n) { i ->
            if (i == 0) 0f else maxOf(0f, ln(rms[i] + 1e-6f) - ln(rms[i - 1] + 1e-6f), ln(highs[i] + 1e-6f) - ln(highs[i - 1] + 1e-6f))
        }
        val typical = odf.filter { it > 0f }.sorted()
        val floor = if (typical.isEmpty()) Float.MAX_VALUE else typical[(typical.size * 0.8).toInt().coerceAtMost(typical.size - 1)]
        val loud = if (typical.isEmpty()) 1f else typical[(typical.size * 0.95).toInt().coerceAtMost(typical.size - 1)]
        val onsets = ArrayList<Int>()
        val strengths = ArrayList<Float>()
        var lastHop = -MIN_GAP_HOPS
        for (i in 1 until n - 1) {
            if (db[i] <= SILENCE_DB) continue
            val localMedian = median(odf, i - ONSET_WINDOW, i + ONSET_WINDOW)
            val isPeak = odf[i] >= odf[i - 1] && odf[i] > odf[i + 1]
            if (isPeak && odf[i] > maxOf(floor, localMedian * 2f + 0.1f) && i - lastHop >= MIN_GAP_HOPS) {
                onsets += i * HOP_MS
                strengths += (odf[i] / loud).coerceIn(0.2f, 1f)
                lastHop = i
            }
        }

        // What sets a rock song apart from a ballad is how hard its strongest parts hit, so the
        // song's energy is measured over its most intense 30 % (a calm intro mustn't dilute it).
        val strong = (0 until n).filter { levels[it] >= STRONG_SHARE }.ifEmpty { (0 until n).filter { db[it] > SILENCE_DB } }
        val strongSet = strong.toHashSet()
        val seconds = strong.size * HOP_MS / 1000.0
        val meanDb = if (strong.isEmpty()) -90f else strong.map { db[it] }.average().toFloat()
        val onsetRate = if (seconds > 0) onsets.count { (it / HOP_MS) in strongSet } / seconds else 0.0
        val zcrRate = if (strong.isEmpty()) 0.0 else strong.sumOf { crossings[it] }.toDouble() / (strong.size * hopSize)
        val energy = (
            0.45 * norm(meanDb.toDouble(), -30.0, -9.0) +
                0.35 * norm(onsetRate, 0.5, 4.0) +
                0.20 * norm(zcrRate, 0.02, 0.15)
            ).toFloat()

        // Loudness of what is heard: the mean power of the audible parts (silences don't count).
        val audiblePower = (0 until n).filter { db[it] > SILENCE_DB }.map { rms[it].toDouble() * rms[it] }
        val loudnessDb = if (audiblePower.isEmpty()) -90f else (10 * log10(audiblePower.average())).toFloat()

        return IntensityProfile(HOP_MS, levels, onsets.toIntArray(), strengths.toFloatArray(), energy, loudnessDb)
    }

    private companion object {
        const val HOP_MS = 50
        const val SILENCE_DB = -50f
        const val STRONG_SHARE = 0.7f
        const val MIN_RANGE_DB = 6f
        const val SECTION_HOPS = 15
        const val ONSET_WINDOW = 10
        const val MIN_GAP_HOPS = 3

        fun smooth(values: FloatArray, radius: Int): FloatArray {
            val prefix = DoubleArray(values.size + 1)
            for (i in values.indices) prefix[i + 1] = prefix[i] + values[i]
            return FloatArray(values.size) { i ->
                val from = (i - radius).coerceAtLeast(0)
                val to = (i + radius + 1).coerceAtMost(values.size)
                ((prefix[to] - prefix[from]) / (to - from)).toFloat()
            }
        }

        /** Share of [sorted] below [value], 0..1. */
        fun rank(sorted: List<Float>, value: Float): Float {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] < value) lo = mid + 1 else hi = mid
            }
            return lo.toFloat() / sorted.size
        }

        fun median(values: FloatArray, from: Int, to: Int): Float {
            val window = values.copyOfRange(from.coerceAtLeast(0), (to + 1).coerceAtMost(values.size))
            window.sort()
            return window[window.size / 2]
        }

        fun norm(value: Double, low: Double, high: Double) = ((value - low) / (high - low)).coerceIn(0.0, 1.0)
    }
}

/** How a lyric line feels, from calm to the song's climax. */
enum class LineMood { Calm, Flow, Strong, Peak }

object LyricMoods {
    private const val PEAK_ENERGY = 0.55f
    private const val PEAK_LEVEL = 0.8f

    /**
     * The mood of a line sung between [fromMs] and [toMs] (audio time). The song's overall energy
     * scales it, so a ballad's chorus can lift but never shakes like a rock climax.
     */
    fun of(profile: IntensityProfile, fromMs: Long, toMs: Long): LineMood {
        val local = profile.mean(fromMs, toMs)
        val value = local * (0.35f + 0.65f * profile.energy)
        return when {
            // Peaks are reserved for energetic songs, at their strongest moments.
            profile.energy >= PEAK_ENERGY && local >= PEAK_LEVEL -> LineMood.Peak
            value >= 0.45f -> LineMood.Strong
            value >= 0.25f -> LineMood.Flow
            else -> LineMood.Calm
        }
    }

    /** Hits only matter where they're felt: a quiet verse shouldn't twitch at every hi-hat. */
    fun pulseWeight(mood: LineMood): Float = when (mood) {
        LineMood.Calm -> 0f
        LineMood.Flow -> 0.35f
        LineMood.Strong -> 0.7f
        LineMood.Peak -> 1f
    }
}
