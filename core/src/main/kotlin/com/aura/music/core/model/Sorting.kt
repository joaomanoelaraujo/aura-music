package com.aura.music.core.model

enum class TrackSort { Title, Artist, Album, DateAdded, Duration }

enum class AlbumSort { Title, Artist, Year, DateAdded }

enum class ArtistSort { Name, TrackCount }

enum class LibraryLayout { List, Grid }

data class SortSpec<T : Enum<T>>(val key: T, val ascending: Boolean)

private val collator: java.text.Collator =
    java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }

/** Locale-aware, case and accent-insensitive comparison; blank values sort last. */
fun compareText(a: String?, b: String?): Int = when {
    a.isNullOrBlank() && b.isNullOrBlank() -> 0
    a.isNullOrBlank() -> 1
    b.isNullOrBlank() -> -1
    else -> collator.compare(a, b)
}

@JvmName("sortTracks")
fun List<Track>.applySort(spec: SortSpec<TrackSort>): List<Track> {
    val comparator: Comparator<Track> = when (spec.key) {
        TrackSort.Title -> Comparator { a, b -> compareText(a.title, b.title) }
        TrackSort.Artist -> Comparator<Track> { a, b -> compareText(a.artist, b.artist) }
            .then { a, b -> compareText(a.album, b.album) }
            .thenBy { it.discNumber * 1000 + it.trackNumber }
        TrackSort.Album -> Comparator<Track> { a, b -> compareText(a.album, b.album) }
            .thenBy { it.discNumber * 1000 + it.trackNumber }
        TrackSort.DateAdded -> compareBy { it.dateAddedSec }
        TrackSort.Duration -> compareBy { it.durationMs }
    }
    return sortedWith(if (spec.ascending) comparator else comparator.reversed())
}

@JvmName("sortAlbums")
fun List<Album>.applySort(spec: SortSpec<AlbumSort>): List<Album> {
    val comparator: Comparator<Album> = when (spec.key) {
        AlbumSort.Title -> Comparator { a, b -> compareText(a.title, b.title) }
        AlbumSort.Artist -> Comparator<Album> { a, b -> compareText(a.artist, b.artist) }
            .then { a, b -> compareText(a.title, b.title) }
        AlbumSort.Year -> compareBy<Album> { it.year ?: 0 }.then { a, b -> compareText(a.title, b.title) }
        AlbumSort.DateAdded -> compareBy { it.dateAddedSec }
    }
    return sortedWith(if (spec.ascending) comparator else comparator.reversed())
}

@JvmName("sortArtists")
fun List<Artist>.applySort(spec: SortSpec<ArtistSort>): List<Artist> {
    val comparator: Comparator<Artist> = when (spec.key) {
        ArtistSort.Name -> Comparator { a, b -> compareText(a.name, b.name) }
        ArtistSort.TrackCount -> compareBy<Artist> { it.trackCount }.then { a, b -> compareText(b.name, a.name) }
    }
    return sortedWith(if (spec.ascending) comparator else comparator.reversed())
}
