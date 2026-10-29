package com.aura.music.core.model

/**
 * Identifies where artwork for a track or album can be loaded from.
 * Resolved by the image pipeline using MediaStore thumbnails, never the network.
 */
data class ArtworkSource(val albumId: Long, val trackUri: String)

/** What an audio file is, decided locally by the classifier (or by the user). */
enum class AudioKind {
    Music,
    WhatsAppVoice,
    WhatsAppAudio,
    VoiceMessage,
    Recording,
    Podcast,
    Other,
    ;

    val isWhatsApp: Boolean get() = this == WhatsAppVoice || this == WhatsAppAudio
}

data class Track(
    val id: Long,
    val uri: String,
    val title: String,
    val artist: String?,
    val artistId: Long,
    val album: String?,
    val albumId: Long,
    val albumArtist: String?,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val year: Int?,
    val dateAddedSec: Long,
    val genre: String?,
    val folder: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val bitrate: Int?,
    val kind: AudioKind = AudioKind.Music,
    /** False when the file carries neither an artist nor an album tag. */
    val hasTags: Boolean = true,
) {
    val artwork: ArtworkSource get() = ArtworkSource(albumId, uri)
}

data class Album(
    val id: Long,
    val title: String?,
    val artist: String?,
    val artistId: Long,
    val year: Int?,
    val trackCount: Int,
    val durationMs: Long,
    val dateAddedSec: Long,
    val artwork: ArtworkSource,
)

data class Artist(
    val id: Long,
    val name: String?,
    val trackCount: Int,
    val albumCount: Int,
    val artwork: ArtworkSource,
)

data class Genre(
    val name: String,
    val trackCount: Int,
    val artwork: ArtworkSource,
)

data class Folder(
    val path: String,
    val name: String,
    val trackCount: Int,
    val artwork: ArtworkSource,
)

data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val durationMs: Long,
    val artwork: ArtworkSource?,
    val updatedAt: Long,
)

data class PlaylistEntry(val entryId: Long, val track: Track)
