package com.aura.music.data.repository

import com.aura.music.core.model.Album
import com.aura.music.core.model.Artist
import com.aura.music.core.model.AudioKind
import com.aura.music.core.model.Track
import com.aura.music.data.local.db.PlayEventDao
import com.aura.music.data.local.db.PlayEventEntity
import com.aura.music.data.local.db.TrackPlayStats
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Singleton
class HistoryRepository @Inject constructor(
    private val dao: PlayEventDao,
    private val library: LibraryRepository,
) {
    private var insertsSinceTrim = 0

    suspend fun record(trackId: Long) {
        dao.insert(PlayEventEntity(trackId = trackId, playedAt = System.currentTimeMillis()))
        if (++insertsSinceTrim >= TRIM_EVERY) {
            insertsSinceTrim = 0
            dao.trimTo(MAX_EVENTS)
        }
    }

    fun recentTracks(limit: Int): Flow<List<Track>> =
        combine(dao.observeRecentTrackIds(limit), library.index) { ids, index -> index.tracks(ids) }

    fun topArtists(limit: Int): Flow<List<Artist>> =
        combine(dao.observePlayCounts(), library.index) { counts, index ->
            counts.mapNotNull { count -> index.track(count.trackId)?.let { it.artistId to count.plays } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, plays) -> plays.sum() }
                .entries
                .sortedByDescending { it.value }
                .mapNotNull { index.artist(it.key) }
                .filter { it.name != null }
                .take(limit)
        }

    /** Plays and last-played time per track, for search ranking and suggestions. */
    val trackStats: Flow<List<TrackPlayStats>> = dao.observeTrackStats()

    /** Most played tracks still in the library. */
    fun topTracks(limit: Int): Flow<List<Track>> =
        combine(dao.observeTrackStats(), library.index) { stats, index ->
            index.tracks(stats.sortedByDescending { it.plays }.map { it.trackId }).filter { it.kind == AudioKind.Music }.take(limit)
        }

    /** Albums of the most recently played tracks, in listening order. */
    fun recentAlbums(limit: Int): Flow<List<Album>> =
        combine(dao.observeRecentTrackIds(RECENT_WINDOW), library.index) { ids, index ->
            index.tracks(ids).map { it.albumId }.distinct().mapNotNull(index::album).filter { it.title != null }.take(limit)
        }

    suspend fun clear() = dao.clear()

    private companion object {
        const val RECENT_WINDOW = 200
        const val TRIM_EVERY = 50
        // Enough for a yearly look back even for heavy listeners (~1 MB).
        const val MAX_EVENTS = 50_000
    }
}
