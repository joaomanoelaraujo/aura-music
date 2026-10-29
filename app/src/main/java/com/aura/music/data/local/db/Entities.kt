package com.aura.music.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlist_id")],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "entry_id") val entryId: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "track_id") val trackId: Long,
    val position: Int,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "play_events", indices = [Index("track_id"), Index("played_at")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "track_id") val trackId: Long,
    @ColumnInfo(name = "played_at") val playedAt: Long,
)

data class TrackPlayCount(
    @ColumnInfo(name = "track_id") val trackId: Long,
    val plays: Int,
)

/** A user's correction of the automatic classification of one file. [kind] null with [hidden] = only hidden. */
@Entity(tableName = "audio_overrides")
data class AudioOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    val kind: String?,
    val hidden: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Forces every file inside a folder (relative path, e.g. "Download/Shows/") to a kind or hides it. */
@Entity(tableName = "folder_rules")
data class FolderRuleEntity(
    @PrimaryKey val path: String,
    val kind: String?,
    val hidden: Boolean,
)

@Entity(tableName = "search_history", indices = [Index("last_used")])
data class SearchHistoryEntity(
    /** Normalized query, so "Coração" and "coracao" are one entry. */
    @PrimaryKey val key: String,
    val text: String,
    @ColumnInfo(name = "last_used") val lastUsed: Long,
    val uses: Int,
)

/** Lyrics the user picked or edited, and the per-track sync offset (applies to any lyrics source). */
@Entity(tableName = "track_lyrics")
data class TrackLyricsEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    /** Custom LRC or plain text; null keeps using the automatic source. */
    val content: String?,
    @ColumnInfo(name = "offset_ms") val offsetMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Lyrics found online for a track, kept so they also show offline. A row with null [content]
 * remembers that nothing was found (or that the user removed what was found), so the internet
 * isn't asked again and again.
 */
@Entity(tableName = "online_lyrics")
data class OnlineLyricsEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    val content: String?,
    val synced: Boolean,
    /** Id of the record at the provider (LRCLIB), when one was used. */
    @ColumnInfo(name = "provider_id") val providerId: Long?,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
    /** The user removed or rejected the result: never fetch this track automatically again. */
    @ColumnInfo(name = "user_removed", defaultValue = "0") val userRemoved: Boolean = false,
)

/**
 * The summary of a song's audio analysis: how energetic it is and how loud it plays. [key] ties it
 * to the file's size and duration, so a replaced file is analyzed again.
 */
@Entity(tableName = "song_analysis")
data class SongAnalysisEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    val key: String,
    val energy: Float,
    @ColumnInfo(name = "loudness_db") val loudnessDb: Float,
    @ColumnInfo(name = "analyzed_at") val analyzedAt: Long,
    /** The audio couldn't be read: don't try again for this file. */
    @ColumnInfo(defaultValue = "0") val failed: Boolean = false,
)

/** Song details the user corrected (from MusicBrainz); the file itself is never modified. */
@Entity(tableName = "metadata_overrides")
data class MetadataOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: Long,
    val title: String?,
    val artist: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** An online cover lookup for an album, so albums without a cover online aren't asked again and again. */
@Entity(tableName = "cover_lookups")
data class CoverLookupEntity(
    @PrimaryKey @ColumnInfo(name = "album_id") val albumId: Long,
    val found: Boolean,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
)

data class TrackPlayStats(
    @ColumnInfo(name = "track_id") val trackId: Long,
    val plays: Int,
    @ColumnInfo(name = "last_played") val lastPlayed: Long,
)

/** One file requested in AURA Discover; the transfer itself runs in Android's DownloadManager. */
@Entity(tableName = "downloads", indices = [Index("system_id")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "system_id") val systemId: Long,
    val title: String,
    val artist: String?,
    val source: String,
    val license: String?,
    val url: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "format_label") val formatLabel: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    val status: String,
    @ColumnInfo(name = "local_uri") val localUri: String?,
    val error: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
