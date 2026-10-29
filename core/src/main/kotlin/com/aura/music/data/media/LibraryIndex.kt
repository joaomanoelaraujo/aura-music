package com.aura.music.data.media

import com.aura.music.core.model.Album
import com.aura.music.core.model.Artist
import com.aura.music.core.model.Folder
import com.aura.music.core.model.Genre
import com.aura.music.core.model.Track
import com.aura.music.core.model.compareText

/**
 * Immutable, in-memory view of the device library derived from a single MediaStore scan.
 * All groupings are computed once so that screens can read them without further I/O.
 *
 * [tracks] holds music only. Voice notes, recordings and other non-music audio live in [audios]
 * and files the user hid in [hidden]; both stay resolvable by ID so queued items keep playing.
 */
class LibraryIndex(
    tracks: List<Track>,
    audios: List<Track> = emptyList(),
    hidden: List<Track> = emptyList(),
) {

    val tracks: List<Track> = tracks.sortedWith { a, b -> compareText(a.title, b.title) }

    /** Non-music audio, newest first (voice notes and recordings are browsed chronologically). */
    val audios: List<Track> = audios.sortedByDescending { it.dateAddedSec }

    val hidden: List<Track> = hidden.sortedWith { a, b -> compareText(a.displayName, b.displayName) }

    private val tracksById: Map<Long, Track> = HashMap<Long, Track>(tracks.size + audios.size + hidden.size).apply {
        hidden.forEach { put(it.id, it) }
        audios.forEach { put(it.id, it) }
        tracks.forEach { put(it.id, it) }
    }

    private val tracksByAlbum: Map<Long, List<Track>> = tracks
        .groupBy { it.albumId }
        .mapValues { (_, list) -> list.sortedWith(albumOrder) }

    private val tracksByArtist: Map<Long, List<Track>> = tracks.groupBy { it.artistId }

    private val tracksByGenre: Map<String, List<Track>> = tracks
        .filter { !it.genre.isNullOrBlank() }
        .groupBy { it.genre!!.trim() }

    private val tracksByFolder: Map<String, List<Track>> = tracks.groupBy { it.folder }

    val albums: List<Album> = tracksByAlbum.map { (id, list) -> list.toAlbum(id) }
        .sortedWith { a, b -> compareText(a.title, b.title) }

    private val albumsById: Map<Long, Album> = albums.associateBy { it.id }

    val artists: List<Artist> = tracksByArtist.map { (id, list) ->
        Artist(
            id = id,
            name = list.first().artist,
            trackCount = list.size,
            albumCount = list.distinctBy { it.albumId }.size,
            artwork = list.maxBy { it.dateAddedSec }.artwork,
        )
    }.sortedWith { a, b -> compareText(a.name, b.name) }

    private val artistsById: Map<Long, Artist> = artists.associateBy { it.id }

    val genres: List<Genre> = tracksByGenre.map { (name, list) ->
        Genre(name = name, trackCount = list.size, artwork = list.first().artwork)
    }.sortedWith { a, b -> compareText(a.name, b.name) }

    val folders: List<Folder> = tracksByFolder.map { (path, list) ->
        Folder(path = path, name = folderName(path), trackCount = list.size, artwork = list.first().artwork)
    }.sortedWith { a, b -> compareText(a.name, b.name) }

    val isEmpty: Boolean get() = tracks.isEmpty() && audios.isEmpty()

    fun track(id: Long): Track? = tracksById[id]

    fun tracks(ids: List<Long>): List<Track> = ids.mapNotNull(tracksById::get)

    fun album(id: Long): Album? = albumsById[id]

    fun albumTracks(id: Long): List<Track> = tracksByAlbum[id].orEmpty()

    fun artist(id: Long): Artist? = artistsById[id]

    fun artistTracks(id: Long): List<Track> = tracksByArtist[id].orEmpty()
        .sortedWith { a, b -> compareText(a.title, b.title) }

    fun artistAlbums(id: Long): List<Album> = tracksByArtist[id].orEmpty()
        .map { it.albumId }
        .distinct()
        .mapNotNull(albumsById::get)
        .sortedByDescending { it.year ?: 0 }

    fun genreTracks(name: String): List<Track> = tracksByGenre[name].orEmpty()
        .sortedWith { a, b -> compareText(a.title, b.title) }

    fun folderTracks(path: String): List<Track> = tracksByFolder[path].orEmpty()
        .sortedWith { a, b -> compareText(a.displayName, b.displayName) }

    fun recentlyAddedTracks(limit: Int): List<Track> = tracks.sortedByDescending { it.dateAddedSec }.take(limit)

    fun recentlyAddedAlbums(limit: Int): List<Album> =
        albums.sortedByDescending { it.dateAddedSec }.take(limit)

    companion object {
        val Empty = LibraryIndex(emptyList())

        private val albumOrder = compareBy<Track>({ it.discNumber }, { it.trackNumber })
            .then { a, b -> compareText(a.title, b.title) }

        fun folderName(path: String): String =
            path.trimEnd('/').substringAfterLast('/').ifBlank { path }

        private fun List<Track>.toAlbum(id: Long): Album {
            val first = first()
            val albumArtist = mapNotNull { it.albumArtist?.takeIf(String::isNotBlank) }.firstOrNull()
            val singleArtist = map { it.artist }.distinct().singleOrNull()
            return Album(
                id = id,
                title = first.album,
                artist = albumArtist ?: singleArtist ?: first.artist,
                artistId = first.artistId,
                year = mapNotNull { it.year }.maxOrNull(),
                trackCount = size,
                durationMs = sumOf { it.durationMs },
                dateAddedSec = maxOf { it.dateAddedSec },
                artwork = first.artwork,
            )
        }
    }
}
