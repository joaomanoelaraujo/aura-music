package com.aura.music.playback.queue

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aura.music.core.image.ArtworkFetcher
import com.aura.music.core.model.ArtworkSource
import com.aura.music.core.model.Track
import com.aura.music.playback.stream.GrowingFiles
import java.io.File

object MediaItemMapper {

    private const val EXTRA_ALBUM_ID = "aura.album_id"
    private const val EXTRA_TRACK_URI = "aura.track_uri"

    /** AURA Together: songs received from a jam's host live in `cacheDir/STREAM_DIR`, outside the library. */
    const val STREAM_DIR = "together"
    private const val STREAM_PREFIX = "together:"
    private val streamName = Regex("^[0-9a-f]{32}$")

    fun toMediaItem(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri(track.uri.toUri())
        .setMimeType(track.mimeType)
        .setMediaMetadata(metadata(track))
        .build()

    fun trackId(item: MediaItem): Long? = item.mediaId.toLongOrNull()

    /** A temporary copy of a song received in a jam, shown with the host's tags. */
    fun streamedItem(file: File, title: String, artist: String?, album: String?, durationMs: Long): MediaItem = MediaItem.Builder()
        .setMediaId(STREAM_PREFIX + file.name)
        .setUri(Uri.fromFile(file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setDurationMs(durationMs)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(
                    Bundle().apply {
                        putLong(EXTRA_ALBUM_ID, streamedId(STREAM_PREFIX + file.name))
                        putString(EXTRA_TRACK_URI, Uri.fromFile(file).toString())
                    },
                )
                .build(),
        )
        .build()

    /** Negative ids for streamed songs: never a library track or album, unique per song. */
    private fun streamedId(mediaId: String) = -1L - (mediaId.hashCode().toLong() and 0x7fffffff)

    /** The cached file behind a streamed item, if [item] is one and the file is still there. */
    fun streamedFile(item: MediaItem, cacheDir: File): File? {
        val name = item.mediaId.removePrefix(STREAM_PREFIX).takeIf { item.mediaId.startsWith(STREAM_PREFIX) } ?: return null
        if (!streamName.matches(name)) return null
        return File(File(cacheDir, STREAM_DIR), name).takeIf(GrowingFiles::isPlayable)
    }

    fun artworkSource(metadata: MediaMetadata): ArtworkSource? {
        val extras = metadata.extras ?: return null
        val trackUri = extras.getString(EXTRA_TRACK_URI) ?: return null
        return ArtworkSource(extras.getLong(EXTRA_ALBUM_ID), trackUri)
    }

    /** Minimal track for items whose file is no longer part of the scanned library. */
    fun fallbackTrack(item: MediaItem): Track? {
        // Streamed jam songs have no library id; a negative one keeps them apart from real tracks.
        val id = trackId(item) ?: item.mediaId.takeIf { it.startsWith(STREAM_PREFIX) }?.let(::streamedId) ?: return null
        val metadata = item.mediaMetadata
        val artwork = artworkSource(metadata)
        return Track(
            id = id,
            uri = artwork?.trackUri.orEmpty(),
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString(),
            artistId = -1,
            album = metadata.albumTitle?.toString(),
            albumId = artwork?.albumId ?: -1,
            albumArtist = null,
            durationMs = metadata.durationMs ?: 0,
            trackNumber = 0,
            discNumber = 1,
            year = metadata.recordingYear,
            dateAddedSec = 0,
            genre = null,
            folder = "",
            displayName = "",
            mimeType = null,
            sizeBytes = 0,
            bitrate = null,
        )
    }

    private fun metadata(track: Track): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album)
        .setAlbumArtist(track.albumArtist)
        .setGenre(track.genre)
        .setRecordingYear(track.year)
        .setTrackNumber(track.trackNumber.takeIf { it > 0 })
        .setDurationMs(track.durationMs)
        .setArtworkUri(ArtworkFetcher.albumUri(track.albumId))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(
            Bundle().apply {
                putLong(EXTRA_ALBUM_ID, track.albumId)
                putString(EXTRA_TRACK_URI, track.uri)
            },
        )
        .build()
}
