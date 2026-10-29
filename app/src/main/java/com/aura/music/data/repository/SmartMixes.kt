package com.aura.music.data.repository

import com.aura.music.core.model.AudioKind
import com.aura.music.core.model.Track
import com.aura.music.data.local.db.PlayEventDao
import com.aura.music.data.local.db.SongAnalysisDao
import com.aura.music.data.local.db.SongAnalysisEntity
import com.aura.music.data.local.db.TrackPlayStats
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Mixes AURA puts together by itself. */
enum class MixKind { Calm, Energy, MostPlayed, Rediscover }

data class MixSummary(val kind: MixKind, val tracks: List<Track>)

/**
 * Playlists that build themselves: by mood, from each song's audio analysis (calm or energetic),
 * and from listening history (most played, and old favourites not heard in a while). Mood mixes
 * are reshuffled once a day so they don't always start the same way.
 */
@Singleton
class SmartMixes @Inject constructor(
    library: LibraryRepository,
    analysis: SongAnalysisDao,
    plays: PlayEventDao,
) {
    private val all: Flow<List<MixSummary>> = combine(library.index, analysis.observeAll(), plays.observeTrackStats()) { index, analyses, stats ->
        val music = index.tracks.filter { it.kind == AudioKind.Music }
        val now = System.currentTimeMillis()
        MixKind.entries.map { kind -> MixSummary(kind, select(kind, music, analyses, stats, now)) }
    }

    /** Mixes with enough songs to be worth playing. */
    val available: Flow<List<MixSummary>> = all.map { mixes -> mixes.filter { it.tracks.size >= MIN_TRACKS } }

    fun tracks(kind: MixKind): Flow<List<Track>> = all.map { mixes -> mixes.first { it.kind == kind }.tracks }

    companion object {
        const val MIN_TRACKS = 5
        private const val MAX_TRACKS = 100
        private const val TOP_TRACKS = 50
        const val CALM_BELOW = 0.35f
        const val ENERGY_ABOVE = 0.6f
        private const val REDISCOVER_MIN_PLAYS = 3
        private const val REDISCOVER_AFTER_MS = 60L * 24 * 60 * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun select(kind: MixKind, music: List<Track>, analyses: List<SongAnalysisEntity>, stats: List<TrackPlayStats>, now: Long): List<Track> {
            val byId = music.associateBy { it.id }
            val dailyShuffle = Random(now / DAY_MS)
            return when (kind) {
                MixKind.Calm -> analyses.filter { !it.failed && it.energy < CALM_BELOW }
                    .sortedBy { it.energy }.mapNotNull { byId[it.trackId] }.take(MAX_TRACKS).shuffled(dailyShuffle)
                MixKind.Energy -> analyses.filter { !it.failed && it.energy > ENERGY_ABOVE }
                    .sortedByDescending { it.energy }.mapNotNull { byId[it.trackId] }.take(MAX_TRACKS).shuffled(dailyShuffle)
                MixKind.MostPlayed -> stats.filter { it.plays >= 2 }
                    .sortedByDescending { it.plays }.mapNotNull { byId[it.trackId] }.take(TOP_TRACKS)
                MixKind.Rediscover -> stats.filter { it.plays >= REDISCOVER_MIN_PLAYS && now - it.lastPlayed > REDISCOVER_AFTER_MS }
                    .sortedByDescending { it.plays }.mapNotNull { byId[it.trackId] }.take(TOP_TRACKS)
            }
        }
    }
}
