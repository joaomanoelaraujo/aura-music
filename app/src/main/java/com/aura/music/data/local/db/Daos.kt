package com.aura.music.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updated_at DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_entries ORDER BY playlist_id, position")
    fun observeAllEntries(): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :playlistId ORDER BY position")
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlist_entries WHERE playlist_id = :playlistId ORDER BY position")
    suspend fun entries(playlistId: Long): List<PlaylistEntryEntity>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("UPDATE playlists SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_entries WHERE entry_id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("UPDATE playlist_entries SET position = :position WHERE entry_id = :entryId")
    suspend fun updatePosition(entryId: Long, position: Int)

    @Transaction
    suspend fun appendTracks(playlistId: Long, trackIds: List<Long>, now: Long) {
        val start = entries(playlistId).maxOfOrNull { it.position + 1 } ?: 0
        insertEntries(trackIds.mapIndexed { i, id -> PlaylistEntryEntity(playlistId = playlistId, trackId = id, position = start + i) })
        touch(playlistId, now)
    }

    @Transaction
    suspend fun reorder(playlistId: Long, orderedEntryIds: List<Long>, now: Long) {
        orderedEntryIds.forEachIndexed { position, entryId -> updatePosition(entryId, position) }
        touch(playlistId, now)
    }

    @Transaction
    suspend fun removeEntry(playlistId: Long, entryId: Long, now: Long) {
        deleteEntry(entryId)
        touch(playlistId, now)
    }

    @Transaction
    suspend fun createWithTracks(name: String, trackIds: List<Long>, now: Long): Long {
        val id = insertPlaylist(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
        insertEntries(trackIds.mapIndexed { i, trackId -> PlaylistEntryEntity(playlistId = id, trackId = trackId, position = i) })
        return id
    }
}

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY added_at DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE track_id = :trackId")
    suspend fun delete(trackId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE track_id = :trackId)")
    suspend fun isFavorite(trackId: Long): Boolean
}

@Dao
interface PlayEventDao {

    @Insert
    suspend fun insert(event: PlayEventEntity)

    @Query(
        "SELECT track_id FROM play_events GROUP BY track_id ORDER BY MAX(played_at) DESC LIMIT :limit",
    )
    fun observeRecentTrackIds(limit: Int): Flow<List<Long>>

    @Query("SELECT track_id, COUNT(*) AS plays FROM play_events GROUP BY track_id")
    fun observePlayCounts(): Flow<List<TrackPlayCount>>

    @Query("DELETE FROM play_events WHERE id NOT IN (SELECT id FROM play_events ORDER BY played_at DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("SELECT track_id, COUNT(*) AS plays, MAX(played_at) AS last_played FROM play_events GROUP BY track_id")
    fun observeTrackStats(): Flow<List<TrackPlayStats>>

    @Query("SELECT * FROM play_events WHERE played_at >= :from")
    suspend fun eventsSince(from: Long): List<PlayEventEntity>

    @Insert
    suspend fun insertAll(events: List<PlayEventEntity>)

    @Query("DELETE FROM play_events")
    suspend fun clear()
}

@Dao
interface ClassificationDao {

    @Query("SELECT * FROM audio_overrides")
    fun observeOverrides(): Flow<List<AudioOverrideEntity>>

    @Query("SELECT * FROM folder_rules ORDER BY path")
    fun observeFolderRules(): Flow<List<FolderRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(entity: AudioOverrideEntity)

    @Query("DELETE FROM audio_overrides WHERE track_id = :trackId")
    suspend fun deleteOverride(trackId: Long)

    @Query("UPDATE audio_overrides SET hidden = 0 WHERE track_id = :trackId")
    suspend fun unhide(trackId: Long)

    @Query("DELETE FROM audio_overrides WHERE kind IS NULL AND hidden = 0")
    suspend fun pruneEmptyOverrides()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolderRule(entity: FolderRuleEntity)

    @Query("DELETE FROM folder_rules WHERE path = :path")
    suspend fun deleteFolderRule(path: String)
}

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY last_used DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history WHERE `key` = :key")
    suspend fun find(key: String): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    @Query("DELETE FROM search_history WHERE `key` NOT IN (SELECT `key` FROM search_history ORDER BY last_used DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}

@Dao
interface LyricsDao {

    @Query("SELECT * FROM track_lyrics WHERE track_id = :trackId")
    fun observe(trackId: Long): Flow<TrackLyricsEntity?>

    @Query("SELECT * FROM track_lyrics WHERE track_id = :trackId")
    suspend fun get(trackId: Long): TrackLyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackLyricsEntity)

    @Query("DELETE FROM track_lyrics WHERE content IS NULL AND offset_ms = 0")
    suspend fun pruneDefaults()

    @Query("SELECT * FROM online_lyrics WHERE track_id = :trackId")
    fun observeOnline(trackId: Long): Flow<OnlineLyricsEntity?>

    @Query("SELECT * FROM online_lyrics WHERE track_id = :trackId")
    suspend fun getOnline(trackId: Long): OnlineLyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOnline(entity: OnlineLyricsEntity)

    @Query("SELECT * FROM track_lyrics")
    suspend fun allCustom(): List<TrackLyricsEntity>

    /** Forgets "not found" results (not the user's removals), so a better search tries them again. */
    @Query("DELETE FROM online_lyrics WHERE content IS NULL AND user_removed = 0")
    suspend fun forgetNotFound()

    @Query("SELECT * FROM online_lyrics")
    suspend fun allOnline(): List<OnlineLyricsEntity>
}

@Dao
interface MetadataDao {
    @Query("SELECT * FROM metadata_overrides")
    fun observeOverrides(): Flow<List<MetadataOverrideEntity>>

    @Query("SELECT * FROM metadata_overrides")
    suspend fun allOverrides(): List<MetadataOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(entity: MetadataOverrideEntity)

    @Query("DELETE FROM metadata_overrides WHERE track_id = :trackId")
    suspend fun deleteOverride(trackId: Long)

    @Query("SELECT * FROM cover_lookups WHERE album_id = :albumId")
    suspend fun coverLookup(albumId: Long): CoverLookupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCoverLookup(entity: CoverLookupEntity)
}

@Dao
interface SongAnalysisDao {
    @Query("SELECT * FROM song_analysis WHERE track_id = :trackId")
    suspend fun get(trackId: Long): SongAnalysisEntity?

    @Query("SELECT * FROM song_analysis")
    suspend fun all(): List<SongAnalysisEntity>

    @Query("SELECT * FROM song_analysis WHERE failed = 0")
    fun observeAll(): Flow<List<SongAnalysisEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SongAnalysisEntity)
}

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE system_id = :systemId")
    suspend fun bySystemId(systemId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('Queued', 'Running')")
    suspend fun active(): List<DownloadEntity>

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status, local_uri = :localUri, error = :error, updated_at = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, localUri: String?, error: String?, now: Long)

    @Query("UPDATE downloads SET system_id = :systemId, status = 'Queued', error = NULL, updated_at = :now WHERE id = :id")
    suspend fun requeue(id: Long, systemId: Long, now: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status IN ('Done', 'Failed', 'Cancelled')")
    suspend fun clearFinished()
}
