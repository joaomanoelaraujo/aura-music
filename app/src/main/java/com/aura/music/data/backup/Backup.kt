package com.aura.music.data.backup

import com.aura.music.core.model.Track
import com.aura.music.data.local.TypedValue
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.sync.TrackMatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything personal in AURA, in one JSON file: playlists, favourites, lyrics, listening history,
 * library corrections, settings and the equalizer. Songs are stored by their tags and duration (not
 * by the phone's internal ids), so a backup restores onto another phone that has the same music.
 */
@Serializable
data class BackupFile(
    val format: Int = FORMAT,
    val createdAt: Long,
    /** Every song mentioned below; other entries point here by index. */
    val songs: List<BackupSong> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val favorites: List<BackupFavorite> = emptyList(),
    val lyrics: List<BackupLyrics> = emptyList(),
    val onlineLyrics: List<BackupOnlineLyrics> = emptyList(),
    val plays: List<BackupPlay> = emptyList(),
    val overrides: List<BackupOverride> = emptyList(),
    val folderRules: List<BackupFolderRule> = emptyList(),
    val settings: Map<String, TypedValue> = emptyMap(),
    val equalizer: Map<String, TypedValue> = emptyMap(),
) {
    companion object {
        const val FORMAT = 1
    }
}

@Serializable
data class BackupSong(val title: String, val artist: String? = null, val album: String? = null, val durationMs: Long)

@Serializable
data class BackupPlaylist(val name: String, val createdAt: Long, val songs: List<Int>)

@Serializable
data class BackupFavorite(val song: Int, val addedAt: Long)

@Serializable
data class BackupLyrics(val song: Int, val content: String? = null, val offsetMs: Long = 0)

@Serializable
data class BackupOnlineLyrics(val song: Int, val content: String? = null, val synced: Boolean, val providerId: Long? = null, val checkedAt: Long, val userRemoved: Boolean = false)

@Serializable
data class BackupPlay(val song: Int, val at: Long)

@Serializable
data class BackupOverride(val song: Int, val kind: String? = null, val hidden: Boolean)

@Serializable
data class BackupFolderRule(val path: String, val kind: String? = null, val hidden: Boolean)

object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Null for files that aren't AURA backups (or come from a newer, unknown format). */
    fun decode(text: String): BackupFile? = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
        .getOrNull()?.takeIf { it.format <= BackupFile.FORMAT }
}

/** Builds the song table while exporting: each song once, referenced by index. */
class SongTable {
    private val indexById = HashMap<Long, Int>()
    val songs = ArrayList<BackupSong>()

    fun indexOf(track: Track): Int = indexById.getOrPut(track.id) {
        songs += BackupSong(track.title, track.artist, track.album, track.durationMs)
        songs.lastIndex
    }
}

/** Finds the backup's songs in this phone's library, by tags and duration. */
class SongResolver(backup: BackupFile, library: List<Track>) {
    private val resolved: List<Track?> = backup.songs.map { song ->
        TrackMatcher.match(TrackRef(song.title, song.artist, song.album, song.durationMs), library).track
    }

    fun track(index: Int): Track? = resolved.getOrNull(index)

    val missing: Int get() = resolved.count { it == null }
}
