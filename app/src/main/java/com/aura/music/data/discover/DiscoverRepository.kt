package com.aura.music.data.discover

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The result of analysing a pasted link. */
sealed interface LinkResult {
    data class Downloadable(val detail: ItemDetail) : LinkResult

    /** A streaming page (YouTube, Spotify): shown with official metadata and opened in its own app. */
    data class Streaming(val item: DiscoverItem) : LinkResult
}

/**
 * Online part of AURA Discover. Everything here is optional: without a connection the player and
 * library keep working, and these calls simply report [DiscoverException.Reason.Offline].
 */
@Singleton
class DiscoverRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isMetered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    suspend fun search(query: String): List<DiscoverItem> {
        requireOnline()
        return InternetArchive.parseSearch(Http.getText(InternetArchive.searchUrl(query)))
    }

    suspend fun detail(item: DiscoverItem): ItemDetail = when (item.source) {
        SourceKind.InternetArchive -> archiveItem(item.key.removePrefix("ia:"))
        else -> throw DiscoverException(DiscoverException.Reason.NotAudio)
    }

    suspend fun analyze(input: String): LinkResult {
        requireOnline()
        return when (val link = LinkAnalyzer.analyze(input)) {
            is LinkKind.ArchiveItem -> LinkResult.Downloadable(archiveItem(link.identifier))
            is LinkKind.YouTube -> LinkResult.Streaming(oEmbed("https://www.youtube.com/oembed?format=json&url=", link.url, SourceKind.YouTube))
            is LinkKind.Spotify -> LinkResult.Streaming(oEmbed("https://open.spotify.com/oembed?url=", link.url, SourceKind.Spotify))
            is LinkKind.DirectFile -> LinkResult.Downloadable(directFile(link.url, link.fileName))
            is LinkKind.Unknown -> LinkResult.Downloadable(directFile(link.url, null))
            is LinkKind.Invalid -> throw DiscoverException(
                if (link.reason == InvalidReason.InsecureHttp) DiscoverException.Reason.Insecure else DiscoverException.Reason.NotAudio,
            )
        }
    }

    private suspend fun archiveItem(identifier: String): ItemDetail {
        val detail = InternetArchive.parseItem(identifier, Http.getText(InternetArchive.metadataUrl(identifier)))
            ?: throw DiscoverException(DiscoverException.Reason.NoLicense)
        if (detail.tracks.isEmpty()) throw DiscoverException(DiscoverException.Reason.NotAudio)
        return detail
    }

    /** Validates a direct link by its real Content-Type and size before offering it. */
    private suspend fun directFile(url: String, nameFromUrl: String?): ItemDetail {
        val head = Http.head(url)
        val name = head.fileName ?: nameFromUrl ?: head.finalUrl.substringAfterLast('/').substringBefore('?')
        val isAudio = head.contentType?.startsWith("audio/") == true ||
            (head.contentType in setOf("application/octet-stream", "binary/octet-stream", null) && LinkAnalyzer.isAudioExtension(name))
        if (!isAudio) throw DiscoverException(DiscoverException.Reason.NotAudio)
        if ((head.contentLength ?: 0) > MAX_FILE_BYTES) throw DiscoverException(DiscoverException.Reason.TooLarge)
        val extension = name.substringAfterLast('.', "").lowercase().ifEmpty { "mp3" }
        val option = AudioOption(
            formatLabel = extension.uppercase(),
            extension = extension,
            bitrateKbps = null,
            lossless = extension in setOf("flac", "wav", "aiff", "alac"),
            original = true,
            sizeBytes = head.contentLength,
            url = head.finalUrl,
            fileName = name,
            mimeType = head.contentType?.takeIf { it.startsWith("audio/") } ?: "audio/$extension",
        )
        val title = name.substringBeforeLast('.')
        val item = DiscoverItem("url:${head.finalUrl}", title, artist = null, coverUrl = null, source = SourceKind.DirectLink, license = null, pageUrl = url)
        return ItemDetail(item, listOf(DownloadableTrack(title, null, null, listOf(option))))
    }

    private suspend fun oEmbed(endpoint: String, url: String, source: SourceKind): DiscoverItem {
        val body = json.parseToJsonElement(Http.getText(endpoint + URLEncoder.encode(url, "UTF-8"))).jsonObject
        return DiscoverItem(
            key = "${source.name}:$url",
            title = body.text("title") ?: url,
            artist = body.text("author_name"),
            coverUrl = body.text("thumbnail_url"),
            source = source,
            license = null,
            pageUrl = url,
        )
    }

    private fun requireOnline() {
        if (!isOnline()) throw DiscoverException(DiscoverException.Reason.Offline)
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

        /** Search pages of the official apps; no key needed and no content leaves those apps. */
        fun youTubeMusicSearch(query: String) = "https://music.youtube.com/search?q=" + URLEncoder.encode(query, "UTF-8")
        fun spotifySearch(query: String) = "https://open.spotify.com/search/" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    }
}
