package com.aura.music.together.sync

import com.aura.music.data.search.TextNormalizer
import com.aura.music.core.model.Track
import com.aura.music.together.protocol.TrackRef
import kotlin.math.abs

/**
 * Estimates `hostClock - localClock` from ping round trips (NTP-style). Samples with the smallest
 * round trip are the most trustworthy, so the estimate uses the best of the recent window.
 */
class ClockSync(private val window: Int = 8) {
    private data class Sample(val offsetMs: Long, val rttMs: Long)

    private val samples = ArrayDeque<Sample>()

    /** [sentLocal] and [receivedLocal] on the guest's clock, [hostMs] the host's reading in between. */
    fun add(sentLocal: Long, hostMs: Long, receivedLocal: Long) {
        val rtt = (receivedLocal - sentLocal).coerceAtLeast(0)
        val offset = hostMs + rtt / 2 - receivedLocal
        samples.addLast(Sample(offset, rtt))
        while (samples.size > window) samples.removeFirst()
    }

    val hasEstimate: Boolean get() = samples.isNotEmpty()

    val offsetMs: Long get() = samples.minByOrNull { it.rttMs }?.offsetMs ?: 0

    val rttMs: Long get() = samples.minOfOrNull { it.rttMs } ?: 0

    fun hostNow(localNow: Long): Long = localNow + offsetMs
}

sealed interface Correction {
    data object None : Correction
    data class Seek(val toMs: Long) : Correction
    /** Temporarily play slightly faster (>1) or slower (<1) to converge without a jump. */
    data class Nudge(val speed: Float) : Correction
}

/**
 * Decides how to bring a guest back in line. Tiny drifts are ignored (inaudible), moderate ones
 * are absorbed with a ±3 % speed change (pitch preserved by Media3), and large ones seek.
 *
 * Position readings are noisy by a few tens of milliseconds, so the speed change uses hysteresis:
 * it starts above [startNudgeMs] and only stops once back under [stopNudgeMs]. Without it the
 * speed flips between faster and slower around the threshold. A seek is never repeated within
 * [seekCooldownMs] to avoid oscillation while the decoder settles.
 */
class DriftCorrector(
    private val startNudgeMs: Long = 70,
    private val stopNudgeMs: Long = 20,
    private val seekThresholdMs: Long = 250,
    private val seekCooldownMs: Long = 1_500,
) {
    private var lastSeekAt = Long.MIN_VALUE / 2
    private var nudging = false

    fun decide(localPositionMs: Long, expectedPositionMs: Long, isPlaying: Boolean, nowMs: Long): Correction {
        val drift = localPositionMs - expectedPositionMs
        val size = abs(drift)
        if (!isPlaying) {
            nudging = false
            return if (size > seekThresholdMs) seek(expectedPositionMs, nowMs) else Correction.None
        }
        if (size >= seekThresholdMs) {
            nudging = false
            return if (nowMs - lastSeekAt >= seekCooldownMs) seek(expectedPositionMs, nowMs) else Correction.None
        }
        nudging = if (nudging) size > stopNudgeMs else size > startNudgeMs
        return when {
            !nudging -> Correction.None
            drift > 0 -> Correction.Nudge(1f - NUDGE)
            else -> Correction.Nudge(1f + NUDGE)
        }
    }

    private fun seek(to: Long, now: Long): Correction {
        lastSeekAt = now
        return Correction.Seek(to)
    }

    private companion object {
        const val NUDGE = 0.03f
    }
}

/** Keeps a short history of measured drift so the UI can report real sync quality. */
class DriftStats(private val size: Int = 20) {
    private val values = ArrayDeque<Long>()

    fun add(driftMs: Long) {
        values.addLast(abs(driftMs))
        while (values.size > size) values.removeFirst()
    }

    val averageMs: Long get() = if (values.isEmpty()) 0 else values.sum() / values.size

    /** Called after a track change or seek, so start-up transients don't pollute the figure. */
    fun reset() = values.clear()
}

data class MatchResult(val track: Track?, val ambiguous: List<Track> = emptyList())

/**
 * Finds the local copy of a song by normalized title and artist, accepting small duration
 * differences between encodes. When several files fit equally, the caller asks the user.
 */
object TrackMatcher {
    private const val DURATION_TOLERANCE_MS = 2_500

    fun ref(track: Track) = TrackRef(track.title, track.artist, track.album, track.durationMs, track.sizeBytes.takeIf { it > 0 })

    fun match(ref: TrackRef, library: List<Track>): MatchResult {
        val title = TextNormalizer.normalize(ref.title)
        val artist = TextNormalizer.normalize(ref.artist)
        val candidates = library.filter { t ->
            TextNormalizer.normalize(t.title) == title &&
                (artist.isEmpty() || TextNormalizer.normalize(t.artist) == artist) &&
                abs(t.durationMs - ref.durationMs) <= DURATION_TOLERANCE_MS
        }
        if (candidates.isEmpty()) return MatchResult(null)
        ref.sizeBytes?.let { size -> candidates.firstOrNull { it.sizeBytes == size }?.let { return MatchResult(it) } }
        val album = TextNormalizer.normalize(ref.album)
        val ranked = candidates.sortedWith(
            compareBy<Track> { if (album.isNotEmpty() && TextNormalizer.normalize(it.album) == album) 0 else 1 }
                .thenBy { abs(it.durationMs - ref.durationMs) },
        )
        val best = ranked.first()
        val ties = ranked.filter {
            TextNormalizer.normalize(it.album) == TextNormalizer.normalize(best.album) && abs(it.durationMs - best.durationMs) < 300
        }
        return if (ties.size > 1) MatchResult(best, ties) else MatchResult(best)
    }
}
