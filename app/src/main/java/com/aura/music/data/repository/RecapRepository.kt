package com.aura.music.data.repository

import com.aura.music.core.model.AudioKind
import com.aura.music.core.model.Track
import com.aura.music.data.local.db.PlayEventDao
import com.aura.music.data.local.db.PlayEventEntity
import com.aura.music.data.local.db.SongAnalysisDao
import com.aura.music.data.local.db.SongAnalysisEntity
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class RecapPeriod { Month, Year, AllTime }

data class RankedTrack(val track: Track, val plays: Int)

data class RankedArtist(val name: String, val plays: Int, val sample: Track)

/** A look back at listening: how much, what, who, when and in which mood. */
data class Recap(
    val plays: Int,
    val minutes: Long,
    val songs: Int,
    val artists: Int,
    val topTracks: List<RankedTrack>,
    val topArtists: List<RankedArtist>,
    /** Plays per hour of the day (0..23), in the phone's time zone. */
    val byHour: IntArray,
    /** Day of week with most plays, 1 = Monday .. 7 = Sunday; null without plays. */
    val busiestDay: Int?,
    /** Shares of analyzed plays that were calm and energetic (the rest is in between). */
    val calmShare: Float,
    val energyShare: Float,
    val analyzedPlays: Int,
) {
    val isEmpty: Boolean get() = plays == 0
}

@Singleton
class RecapRepository @Inject constructor(
    private val plays: PlayEventDao,
    private val analysis: SongAnalysisDao,
    private val library: LibraryRepository,
) {
    suspend fun recap(period: RecapPeriod, zone: ZoneId = ZoneId.systemDefault()): Recap {
        val now = ZonedDateTime.now(zone)
        val from = when (period) {
            RecapPeriod.Month -> now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
            RecapPeriod.Year -> now.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS)
            RecapPeriod.AllTime -> null
        }?.toInstant()?.toEpochMilli() ?: 0L
        val index = library.awaitIndex()
        val tracks = index?.let { (it.tracks + it.audios).associateBy(Track::id) }.orEmpty()
        return compute(plays.eventsSince(from), tracks, analysis.all(), zone)
    }

    companion object {
        private const val TOP = 5

        fun compute(events: List<PlayEventEntity>, tracks: Map<Long, Track>, analyses: List<SongAnalysisEntity>, zone: ZoneId): Recap {
            // Only music still in the library counts (voice notes and deleted files don't).
            val played = events.mapNotNull { e -> tracks[e.trackId]?.takeIf { it.kind == AudioKind.Music }?.let { e to it } }
            val perTrack = played.groupingBy { it.second.id }.eachCount()
            val topTracks = perTrack.entries.sortedByDescending { it.value }.take(TOP).map { RankedTrack(tracks.getValue(it.key), it.value) }
            val perArtist = played.filter { !it.second.artist.isNullOrBlank() && it.second.artist != "<unknown>" }.groupBy { it.second.artist!! }
            val topArtists = perArtist.entries.sortedByDescending { it.value.size }.take(TOP).map { (name, list) -> RankedArtist(name, list.size, list.first().second) }
            val byHour = IntArray(24)
            val byDay = IntArray(8)
            for ((event, _) in played) {
                val time = Instant.ofEpochMilli(event.playedAt).atZone(zone)
                byHour[time.hour]++
                byDay[time.dayOfWeek.value]++
            }
            val energies = analyses.filter { !it.failed }.associate { it.trackId to it.energy }
            val analyzed = played.mapNotNull { energies[it.second.id] }
            return Recap(
                plays = played.size,
                minutes = played.sumOf { it.second.durationMs } / 60_000,
                songs = perTrack.size,
                artists = perArtist.size,
                topTracks = topTracks,
                topArtists = topArtists,
                byHour = byHour,
                busiestDay = (1..7).maxByOrNull { byDay[it] }?.takeIf { byDay[it] > 0 },
                calmShare = if (analyzed.isEmpty()) 0f else analyzed.count { it < SmartMixes.CALM_BELOW }.toFloat() / analyzed.size,
                energyShare = if (analyzed.isEmpty()) 0f else analyzed.count { it > SmartMixes.ENERGY_ABOVE }.toFloat() / analyzed.size,
                analyzedPlays = analyzed.size,
            )
        }
    }
}
