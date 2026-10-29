package com.aura.music.data.discover

enum class SourceKind { InternetArchive, DirectLink, YouTube, Spotify }

/** One search result or analysed link. */
data class DiscoverItem(
    val key: String,
    val title: String,
    val artist: String?,
    val coverUrl: String?,
    val source: SourceKind,
    /** Human-readable license (e.g. "CC BY 4.0"), when the source declares one. */
    val license: String?,
    val pageUrl: String,
) {
    /** Only open catalogs and direct files can be saved; streaming links open in their own apps. */
    val downloadable: Boolean get() = source == SourceKind.InternetArchive || source == SourceKind.DirectLink
}

/** A real file offered by the source in one format. Nothing is ever transcoded. */
data class AudioOption(
    val formatLabel: String,
    val extension: String,
    val bitrateKbps: Int?,
    val lossless: Boolean,
    val original: Boolean,
    val sizeBytes: Long?,
    val url: String,
    val fileName: String,
    val mimeType: String,
)

data class DownloadableTrack(
    val title: String,
    val trackNumber: Int?,
    val durationSec: Double?,
    val options: List<AudioOption>,
)

data class ItemDetail(val item: DiscoverItem, val tracks: List<DownloadableTrack>)

class DiscoverException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { Offline, Network, NotFound, NotAudio, TooLarge, Insecure, NoLicense }
}
