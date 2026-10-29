package com.aura.music.data.metadata

import com.aura.music.BuildConfig
import com.aura.music.data.search.TextNormalizer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class MbArtistCredit(val name: String = "")

@Serializable
data class MbReleaseGroup(val id: String = "")

@Serializable
data class MbRelease(
    val id: String,
    val title: String = "",
    val score: Int = 0,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    @SerialName("release-group") val releaseGroup: MbReleaseGroup? = null,
) {
    val artist: String get() = artistCredit.joinToString(" & ") { it.name }
}

@Serializable
data class MbRecording(
    val id: String,
    val title: String = "",
    val score: Int = 0,
    /** Milliseconds. */
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MbArtistCredit> = emptyList(),
    val releases: List<MbRelease> = emptyList(),
) {
    val artist: String get() = artistCredit.joinToString(" & ") { it.name }
}

@Serializable
private data class ReleaseSearch(val releases: List<MbRelease> = emptyList())

@Serializable
private data class RecordingSearch(val recordings: List<MbRecording> = emptyList())

/**
 * MusicBrainz (the open music encyclopedia) for song and album details, and the Cover Art Archive
 * for covers. Follows MusicBrainz's rules: an identifying User-Agent and at most one request per
 * second. Only names and durations are sent.
 */
class MusicBrainzClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://musicbrainz.org",
    private val coversUrl: String = "https://coverartarchive.org",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pace = Mutex()
    private var lastCall = 0L

    suspend fun searchReleases(album: String, artist: String?): List<MbRelease> {
        val query = buildString {
            append("release:\"").append(escape(album)).append('"')
            if (!artist.isNullOrBlank()) append(" AND artist:\"").append(escape(artist)).append('"')
        }
        val url = "$baseUrl/ws/2/release".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("fmt", "json").addQueryParameter("limit", "8").build()
        return json.decodeFromString(ReleaseSearch.serializer(), get(url.toString(), paced = true) ?: return emptyList()).releases
    }

    suspend fun searchRecordings(text: String): List<MbRecording> {
        val url = "$baseUrl/ws/2/recording".toHttpUrl().newBuilder()
            .addQueryParameter("query", text).addQueryParameter("fmt", "json").addQueryParameter("limit", "15").build()
        return json.decodeFromString(RecordingSearch.serializer(), get(url.toString(), paced = true) ?: return emptyList()).recordings
    }

    /** The front cover of a release (or of its release group), ~500 px; null when there's none. */
    suspend fun frontCover(release: MbRelease): ByteArray? =
        bytes("$coversUrl/release/${release.id}/front-500")
            ?: release.releaseGroup?.id?.takeIf(String::isNotBlank)?.let { bytes("$coversUrl/release-group/$it/front-500") }

    private suspend fun get(url: String, paced: Boolean): String? {
        if (paced) {
            pace.withLock {
                val wait = lastCall + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastCall = System.currentTimeMillis()
            }
        }
        return withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()).execute().use { r ->
                when {
                    r.code == 404 -> null
                    !r.isSuccessful -> throw IOException("MusicBrainz HTTP ${r.code}")
                    else -> r.body?.string()
                }
            }
        }
    }

    private suspend fun bytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            when {
                r.code == 404 -> null
                !r.isSuccessful -> throw IOException("Cover Art Archive HTTP ${r.code}")
                else -> r.body?.bytes()?.takeIf { it.size in 1..MAX_COVER_BYTES }
            }
        }
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val MIN_INTERVAL_MS = 1_100L
        private const val MAX_COVER_BYTES = 4 * 1024 * 1024
        private val USER_AGENT = "AURA Music/${BuildConfig.VERSION_NAME} (offline-first Android music player)"
    }
}

/** Conservative matching: a cover or a correction is applied by itself only when it clearly fits. */
object MetadataMatcher {
    /** The release that surely is this album, or null. */
    fun album(album: String, artist: String?, releases: List<MbRelease>): MbRelease? {
        val title = core(album)
        if (title.isEmpty()) return null
        val wanted = TextNormalizer.normalize(artist)
        return releases.filter { it.score >= MIN_SCORE }.firstOrNull { release ->
            core(release.title) == title && (wanted.isEmpty() || wanted == UNKNOWN || sameArtist(wanted, TextNormalizer.normalize(release.artist)))
        }
    }

    private fun sameArtist(a: String, b: String) = a == b || a.contains(b) || b.contains(a)

    /** "Album (Deluxe Edition) [Remastered]" → "album". */
    private fun core(title: String) = TextNormalizer.normalize(title.replace(Regex("""\s*[(\[].*?[)\]]"""), " "))

    private const val MIN_SCORE = 90
    private const val UNKNOWN = "unknown"
}
