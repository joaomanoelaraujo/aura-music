package com.aura.music.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.aura.music.core.model.Track
import com.aura.music.data.local.PreferenceValues
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.local.db.AudioOverrideEntity
import com.aura.music.data.local.db.AuraDatabase
import com.aura.music.data.local.db.FavoriteEntity
import com.aura.music.data.local.db.FolderRuleEntity
import com.aura.music.data.local.db.OnlineLyricsEntity
import com.aura.music.data.local.db.PlayEventEntity
import com.aura.music.data.local.db.TrackLyricsEntity
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.playback.EqualizerController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

sealed interface BackupResult {
    data class Exported(val songs: Int, val playlists: Int) : BackupResult

    /** What came back, and how many songs of the backup this library doesn't have. */
    data class Restored(val playlists: Int, val favorites: Int, val lyrics: Int, val plays: Int, val missingSongs: Int) : BackupResult

    data object NotABackup : BackupResult
    data object LibraryNotReady : BackupResult
    data class Failed(val reason: String?) : BackupResult
}

@Singleton
class BackupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: AuraDatabase,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val equalizer: EqualizerController,
) {
    suspend fun export(target: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val index = library.awaitIndex() ?: return@withContext BackupResult.LibraryNotReady
            val tracks = (index.tracks + index.audios + index.hidden).associateBy(Track::id)
            val table = SongTable()
            fun song(id: Long): Int? = tracks[id]?.let(table::indexOf)

            val entries = db.playlistDao().observeAllEntries().first().groupBy { it.playlistId }
            val playlists = db.playlistDao().observePlaylists().first().map { p ->
                BackupPlaylist(p.name, p.createdAt, entries[p.id].orEmpty().sortedBy { it.position }.mapNotNull { song(it.trackId) })
            }
            val backup = BackupFile(
                createdAt = System.currentTimeMillis(),
                playlists = playlists,
                favorites = db.favoriteDao().observeFavorites().first().mapNotNull { f -> song(f.trackId)?.let { BackupFavorite(it, f.addedAt) } },
                lyrics = db.lyricsDao().allCustom().mapNotNull { l -> song(l.trackId)?.let { BackupLyrics(it, l.content, l.offsetMs) } },
                onlineLyrics = db.lyricsDao().allOnline().mapNotNull { o ->
                    song(o.trackId)?.let { BackupOnlineLyrics(it, o.content, o.synced, o.providerId, o.checkedAt, o.userRemoved) }
                },
                plays = db.playEventDao().eventsSince(0).mapNotNull { e -> song(e.trackId)?.let { BackupPlay(it, e.playedAt) } },
                overrides = db.classificationDao().observeOverrides().first().mapNotNull { o -> song(o.trackId)?.let { BackupOverride(it, o.kind, o.hidden) } },
                folderRules = db.classificationDao().observeFolderRules().first().map { BackupFolderRule(it.path, it.kind, it.hidden) },
                settings = PreferenceValues.encode(settings.exportAll() - NOT_PORTABLE),
                equalizer = PreferenceValues.encode(equalizer.exportAll()),
            ).copy(songs = table.songs)
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(BackupCodec.encode(backup).toByteArray()) }
                ?: return@withContext BackupResult.Failed("Can't write the file")
            BackupResult.Exported(backup.songs.size, playlists.size)
        }.getOrElse { BackupResult.Failed(it.message) }
    }

    /**
     * Brings a backup back, merging with what's here: playlists with a new name are created,
     * favourites and lyrics added, listening history added without duplicates, settings replaced.
     */
    suspend fun restore(source: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext BackupResult.Failed("Can't read the file")
            val backup = BackupCodec.decode(text) ?: return@withContext BackupResult.NotABackup
            val index = library.awaitIndex() ?: return@withContext BackupResult.LibraryNotReady
            val songs = SongResolver(backup, index.tracks + index.audios + index.hidden)
            var playlists = 0
            var favorites = 0
            var lyrics = 0
            var plays = 0
            db.withTransaction {
                val existingNames = db.playlistDao().observePlaylists().first().map { it.name }.toSet()
                for (p in backup.playlists) {
                    if (p.name in existingNames) continue
                    db.playlistDao().createWithTracks(p.name, p.songs.mapNotNull { songs.track(it)?.id }, p.createdAt)
                    playlists++
                }
                for (f in backup.favorites) {
                    val track = songs.track(f.song) ?: continue
                    if (!db.favoriteDao().isFavorite(track.id)) {
                        db.favoriteDao().insert(FavoriteEntity(track.id, f.addedAt))
                        favorites++
                    }
                }
                for (l in backup.lyrics) {
                    val track = songs.track(l.song) ?: continue
                    db.lyricsDao().upsert(TrackLyricsEntity(track.id, l.content, l.offsetMs, System.currentTimeMillis()))
                    lyrics++
                }
                for (o in backup.onlineLyrics) {
                    val track = songs.track(o.song) ?: continue
                    if (db.lyricsDao().getOnline(track.id)?.content == null) {
                        db.lyricsDao().upsertOnline(OnlineLyricsEntity(track.id, o.content, o.synced, o.providerId, o.checkedAt, o.userRemoved))
                    }
                }
                val known = db.playEventDao().eventsSince(0).map { it.trackId to it.playedAt }.toHashSet()
                val newPlays = backup.plays.mapNotNull { p ->
                    songs.track(p.song)?.let { t -> (t.id to p.at).takeIf { it !in known }?.let { PlayEventEntity(trackId = t.id, playedAt = p.at) } }
                }
                db.playEventDao().insertAll(newPlays)
                plays = newPlays.size
                for (o in backup.overrides) {
                    val track = songs.track(o.song) ?: continue
                    db.classificationDao().upsertOverride(AudioOverrideEntity(track.id, o.kind, o.hidden, System.currentTimeMillis()))
                }
                for (r in backup.folderRules) db.classificationDao().upsertFolderRule(FolderRuleEntity(r.path, r.kind, r.hidden))
            }
            settings.importAll(PreferenceValues.decode(backup.settings) - NOT_PORTABLE)
            equalizer.importAll(PreferenceValues.decode(backup.equalizer))
            BackupResult.Restored(playlists, favorites, lyrics, plays, songs.missing)
        }.getOrElse { BackupResult.Failed(it.message) }
    }

    private companion object {
        /** Folder access grants belong to the phone they were made on; first-run state too. */
        val NOT_PORTABLE = setOf("lyrics_folders", "onboarding_completed")
    }
}
