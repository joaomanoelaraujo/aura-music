package com.aura.music.data.repository

import com.aura.music.core.model.Playlist
import com.aura.music.core.model.PlaylistEntry
import com.aura.music.data.local.db.PlaylistDao
import com.aura.music.data.local.db.PlaylistEntity
import com.aura.music.data.local.db.PlaylistEntryEntity
import com.aura.music.data.media.LibraryIndex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class PlaylistDetail(
    val playlist: Playlist,
    val entries: List<PlaylistEntry>,
    /** Entries whose files no longer exist on the device. */
    val unavailableCount: Int,
)

@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val library: LibraryRepository,
) {
    val playlists: Flow<List<Playlist>> = combine(
        dao.observePlaylists(),
        dao.observeAllEntries(),
        library.index,
    ) { playlists, entries, index ->
        val byPlaylist = entries.groupBy { it.playlistId }
        playlists.map { it.toModel(byPlaylist[it.id].orEmpty(), index) }
    }

    /** Track IDs of every playlist, used to rank playlists by how much they are listened to. */
    val playlistTrackIds: Flow<Map<Long, List<Long>>> = dao.observeAllEntries().map { entries ->
        entries.groupBy({ it.playlistId }, { it.trackId })
    }

    fun playlist(id: Long): Flow<PlaylistDetail?> = combine(
        dao.observePlaylist(id),
        dao.observeEntries(id),
        library.index,
    ) { playlist, entries, index ->
        playlist ?: return@combine null
        val resolved = entries.mapNotNull { entry -> index.track(entry.trackId)?.let { PlaylistEntry(entry.entryId, it) } }
        PlaylistDetail(
            playlist = playlist.toModel(entries, index),
            entries = resolved,
            unavailableCount = if (index.isEmpty) 0 else entries.size - resolved.size,
        )
    }

    suspend fun create(name: String, trackIds: List<Long> = emptyList()): Long =
        dao.createWithTracks(name.trim(), trackIds, now())

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim(), now())

    suspend fun delete(id: Long) = dao.deletePlaylist(id)

    /** Appends tracks not already in the playlist. Returns how many were added. */
    suspend fun addTracks(playlistId: Long, trackIds: List<Long>): Int {
        val existing = dao.entries(playlistId).mapTo(HashSet()) { it.trackId }
        val toAdd = trackIds.distinct().filterNot(existing::contains)
        if (toAdd.isNotEmpty()) dao.appendTracks(playlistId, toAdd, now())
        return toAdd.size
    }

    suspend fun removeEntry(playlistId: Long, entryId: Long) = dao.removeEntry(playlistId, entryId, now())

    suspend fun reorder(playlistId: Long, orderedEntryIds: List<Long>) = dao.reorder(playlistId, orderedEntryIds, now())

    private fun PlaylistEntity.toModel(entries: List<PlaylistEntryEntity>, index: LibraryIndex): Playlist {
        val tracks = index.tracks(entries.map { it.trackId })
        return Playlist(
            id = id,
            name = name,
            trackCount = tracks.size,
            durationMs = tracks.sumOf { it.durationMs },
            artwork = tracks.firstOrNull()?.artwork,
            updatedAt = updatedAt,
        )
    }

    private fun now() = System.currentTimeMillis()
}
