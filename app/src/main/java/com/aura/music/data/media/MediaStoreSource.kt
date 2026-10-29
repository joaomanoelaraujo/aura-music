package com.aura.music.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.aura.music.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/** A file as read from MediaStore, before classification. */
data class ScannedAudio(val track: Track, val signals: AudioSignals)

/** Reads audio files exclusively through MediaStore, the scoped-storage friendly source of truth. */
@Singleton
class MediaStoreSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver
    private val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    /**
     * Reads every audio file (not only those MediaStore flags as music) together with the signals
     * the [AudioClassifier] needs to tell songs from voice notes and recordings.
     */
    suspend fun queryAudio(minDurationMs: Long): List<ScannedAudio> = withContext(Dispatchers.IO) {
        val selection = "${MediaStore.Audio.Media.DURATION} >= ?"
        val genres = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) queryLegacyGenres() else emptyMap()
        resolver.query(collection, projection(), selection, arrayOf(minDurationMs.toString()), null)
            ?.use { cursor -> readTracks(cursor, genres) }
            .orEmpty()
    }

    /** Emits whenever the audio collection changes (files added, removed or re-tagged). */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private fun projection(): Array<String> = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ARTIST_ID)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.ALBUM_ID)
        add(MediaStore.Audio.Media.ALBUM_ARTIST)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.TRACK)
        add(MediaStore.Audio.Media.YEAR)
        add(MediaStore.Audio.Media.DATE_ADDED)
        add(MediaStore.Audio.Media.RELATIVE_PATH)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.MIME_TYPE)
        add(MediaStore.Audio.Media.SIZE)
        add(MediaStore.Audio.Media.IS_MUSIC)
        add(MediaStore.Audio.Media.IS_PODCAST)
        add(MediaStore.Audio.Media.IS_AUDIOBOOK)
        add(MediaStore.Audio.Media.IS_ALARM)
        add(MediaStore.Audio.Media.IS_NOTIFICATION)
        add(MediaStore.Audio.Media.IS_RINGTONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(MediaStore.Audio.Media.IS_RECORDING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(MediaStore.Audio.Media.GENRE)
            add(MediaStore.Audio.Media.BITRATE)
            add(MediaStore.Audio.Media.DISC_NUMBER)
        }
    }.toTypedArray()

    private fun readTracks(cursor: Cursor, legacyGenres: Map<Long, String>): List<ScannedAudio> {
        val columns = Columns(cursor)
        val tracks = ArrayList<ScannedAudio>(cursor.count)
        while (cursor.moveToNext()) {
            runCatching { columns.read(cursor, legacyGenres) }
                .onSuccess(tracks::add)
                .onFailure { Log.w(TAG, "Skipping unreadable row", it) }
        }
        return tracks
    }

    /** Android 10 has no GENRE column, so genres are resolved through the Genres.Members tables. */
    private fun queryLegacyGenres(): Map<Long, String> {
        val result = HashMap<Long, String>()
        val genreProjection = arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME)
        runCatching {
            resolver.query(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI, genreProjection, null, null, null)?.use { genres ->
                while (genres.moveToNext()) {
                    val genreId = genres.getLong(0)
                    val name = genres.getString(1)?.takeIf(String::isNotBlank) ?: continue
                    val members = MediaStore.Audio.Genres.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, genreId)
                    resolver.query(members, arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null)
                        ?.use { while (it.moveToNext()) result[it.getLong(0)] = name }
                }
            }
        }.onFailure { Log.w(TAG, "Legacy genre lookup failed", it) }
        return result
    }

    private inner class Columns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        private val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        private val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        private val artistId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
        private val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        private val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        private val albumArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
        private val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        private val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        private val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        private val dateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
        private val relativePath = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
        private val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        private val mimeType = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        private val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        private val genre = cursor.getColumnIndex("genre")
        private val bitrate = cursor.getColumnIndex("bitrate")
        private val discNumber = cursor.getColumnIndex("disc_number")
        private val isMusic = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)
        private val isPodcast = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PODCAST)
        private val isAudiobook = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_AUDIOBOOK)
        private val isAlarm = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM)
        private val isNotification = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION)
        private val isRingtone = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE)
        private val isRecording = cursor.getColumnIndex("is_recording")

        fun read(cursor: Cursor, legacyGenres: Map<Long, String>): ScannedAudio {
            val trackId = cursor.getLong(id)
            val displayName = cursor.getString(displayName).orEmpty()
            val rawTrack = cursor.getInt(track)
            val track = Track(
                id = trackId,
                uri = ContentUris.withAppendedId(collection, trackId).toString(),
                title = cursor.getString(title)?.takeIf(String::isNotBlank) ?: displayName.substringBeforeLast('.'),
                artist = cursor.getString(artist).knownOrNull(),
                artistId = cursor.getLong(artistId),
                album = cursor.getString(album).knownOrNull(),
                albumId = cursor.getLong(albumId),
                albumArtist = cursor.getString(albumArtist).knownOrNull(),
                durationMs = cursor.getLong(duration),
                // Legacy TRACK encodes disc * 1000 + track number.
                trackNumber = rawTrack % 1000,
                discNumber = discNumberFrom(cursor, rawTrack),
                year = cursor.getInt(year).takeIf { it > 0 },
                dateAddedSec = cursor.getLong(dateAdded),
                genre = if (genre >= 0) cursor.getString(genre).knownOrNull() else legacyGenres[trackId],
                folder = cursor.getString(relativePath).orEmpty(),
                displayName = displayName,
                mimeType = cursor.getString(mimeType),
                sizeBytes = cursor.getLong(size),
                bitrate = if (bitrate >= 0 && !cursor.isNull(bitrate)) cursor.getInt(bitrate) else null,
            )
            val signals = AudioSignals(
                relativePath = track.folder,
                displayName = displayName,
                mimeType = track.mimeType,
                durationMs = track.durationMs,
                title = cursor.getString(title),
                artist = track.artist,
                album = track.album,
                albumArtist = track.albumArtist,
                genre = track.genre,
                year = track.year,
                trackNumber = track.trackNumber,
                bitrate = track.bitrate,
                isMusic = cursor.flag(isMusic),
                isPodcast = cursor.flag(isPodcast),
                isRecording = isRecording >= 0 && cursor.flag(isRecording),
                isAudiobook = cursor.flag(isAudiobook),
                isSystemSound = cursor.flag(isAlarm) || cursor.flag(isNotification) || cursor.flag(isRingtone),
            )
            return ScannedAudio(track, signals)
        }

        private fun Cursor.flag(index: Int): Boolean = !isNull(index) && getInt(index) != 0

        private fun discNumberFrom(cursor: Cursor, rawTrack: Int): Int {
            val explicit = if (discNumber >= 0) cursor.getString(discNumber)?.substringBefore('/')?.trim()?.toIntOrNull() else null
            return explicit ?: (rawTrack / 1000).coerceAtLeast(1)
        }
    }

    private fun String?.knownOrNull(): String? =
        this?.trim()?.takeUnless { it.isEmpty() || it == MediaStore.UNKNOWN_STRING }

    private companion object {
        const val TAG = "MediaStoreSource"
    }
}
