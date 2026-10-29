package com.aura.music.data.lyrics.online

import com.aura.music.data.search.TextNormalizer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** One lyrics record from LRCLIB (https://lrclib.net), an open database of synced lyrics. */
@Serializable
data class LrclibRecord(
    val id: Long,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String? = null,
    /** Seconds. */
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) {
    val durationMs: Long get() = (duration * 1000).roundToLong()
    val hasLyrics: Boolean get() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()

    /** Synced (LRC) when available: that's what lets the lyrics follow the song. */
    val bestText: String? get() = syncedLyrics?.takeIf(String::isNotBlank) ?: plainLyrics?.takeIf(String::isNotBlank)
}

/** What is known about the song being looked up. */
data class LyricsQuery(val title: String, val artist: String?, val album: String?, val durationMs: Long)

/**
 * Talks to LRCLIB. Only the song's title, artist, album and duration are sent; no account or key
 * is needed, and nothing about the user or their files leaves the phone.
 */
class LrclibClient(
    private val baseUrl: String = "https://lrclib.net",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val userAgent: String = "AURA Music (Android; offline-first music player)",
) : LyricsSource {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The exact record for this song among what LRCLIB already has, or null when it doesn't know it.
     * The cached endpoint answers at once; the plain one would also query other sites, which is
     * slow and often "busy" for songs nobody has.
     */
    override suspend fun get(query: LyricsQuery): LrclibRecord? {
        val url = "$baseUrl/api/get-cached".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", query.title)
            .addQueryParameter("artist_name", query.artist.orEmpty())
            .apply { query.album?.takeIf(String::isNotBlank)?.let { addQueryParameter("album_name", it) } }
            .addQueryParameter("duration", (query.durationMs / 1000).toString())
            .build()
        val body = call(url.toString(), notFoundIsNull = true) ?: return null
        return json.decodeFromString(LrclibRecord.serializer(), body)
    }

    /** Free search, for the user to pick among results or for a looser automatic match. */
    override suspend fun search(text: String): List<LrclibRecord> {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder().addQueryParameter("q", text).build()
        return records(url.toString())
    }

    override suspend fun searchTitle(title: String, artist: String?): List<LrclibRecord> {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .apply { artist?.let { addQueryParameter("artist_name", it) } }
            .build()
        return records(url.toString())
    }

    private suspend fun records(url: String): List<LrclibRecord> {
        val body = call(url, notFoundIsNull = true) ?: return emptyList()
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(LrclibRecord.serializer()), body)
    }

    private suspend fun call(url: String, notFoundIsNull: Boolean): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404 && notFoundIsNull) return@withContext null
            if (!response.isSuccessful) throw IOException("LRCLIB HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Empty LRCLIB answer")
        }
    }
}

/** Where lyrics are looked up; [LrclibClient] in the app, a fake in tests. */
interface LyricsSource {
    /** The exact record among what's already known, or null. */
    suspend fun get(query: LyricsQuery): LrclibRecord?

    /** Free text search. */
    suspend fun search(text: String): List<LrclibRecord>

    /** Search by title (and artist, when known) fields. */
    suspend fun searchTitle(title: String, artist: String?): List<LrclibRecord>
}

/**
 * Decides whether a result really is the song, before assigning it without asking. Conservative
 * on purpose: wrong lyrics are worse than none, and the user can always search by hand. Titles are
 * compared by their words after cleaning ("Song (Official Video)" is "Song"), and the duration must
 * agree, more strictly when the artist is unknown.
 */
object LyricsMatcher {
    /** Different releases of the same recording differ by a second or two. */
    const val DURATION_TOLERANCE_MS = 3_000L
    private const val NO_ARTIST_TOLERANCE_MS = 2_000L

    fun isSameSong(query: LyricsQuery, record: LrclibRecord): Boolean {
        if (!record.hasLyrics) return false
        val tolerance = if (query.artist == null) NO_ARTIST_TOLERANCE_MS else DURATION_TOLERANCE_MS
        if (query.durationMs > 0 && abs(record.durationMs - query.durationMs) > tolerance) return false
        if (!sameTitle(query.title, record.trackName)) return false
        val artist = words(query.artist)
        if (artist.isEmpty()) return query.durationMs > 0
        return sameArtist(artist, words(record.artistName))
    }

    /** Of several results for the same song, the best: synced first, then the closest duration. */
    fun best(query: LyricsQuery, records: List<LrclibRecord>): LrclibRecord? = records
        .filter { isSameSong(query, it) }
        .minWithOrNull(compareBy<LrclibRecord> { it.syncedLyrics.isNullOrBlank() }.thenBy { abs(it.durationMs - query.durationMs) })

    fun sameTitle(a: String, b: String): Boolean {
        val x = words(core(a))
        val y = words(core(b))
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val common = x.intersect(y.toSet()).size
        val shorter = minOf(x.size, y.size)
        val extra = maxOf(x.size, y.size) - shorter
        // One-word titles must be equal ("Tempo" isn't "Tempo Perdido"); longer ones may carry a
        // couple of extra words ("Tempo Perdido Ao Vivo"), not a whole other title.
        if (shorter >= 2 && common == shorter && extra <= 2) return true
        return shorter >= 2 && common.toFloat() / (x.toSet() + y.toSet()).size >= 0.75f
    }

    private fun sameArtist(wanted: List<String>, found: List<String>): Boolean {
        if (found.isEmpty()) return false
        val a = wanted.joinToString(" ")
        val b = found.joinToString(" ")
        if (a == b || a.contains(b) || b.contains(a)) return true
        return wanted.any { it.length >= 3 && it !in STOP_WORDS && it in found }
    }

    /** "Song (Remastered 2011) - Live" → "Song": the part both sides tend to agree on. */
    private fun core(title: String): String = SongNames.clean(title.replace(Regex("""\s*[(\[].*?[)\]]"""), " ")).substringBefore(" - ")

    private fun words(text: String?): List<String> = TextNormalizer.normalize(text).split(' ').filter(String::isNotBlank)

    private val STOP_WORDS = setOf("the", "and", "los", "las", "das", "dos", "the", "band", "banda", "feat", "com")
}

/**
 * Finds a song's lyrics trying the ways a name can be read (see [SongNames]): exact lookups first,
 * then free search, then a title-only search where only a matching duration is trusted. Stops at the
 * first result that is surely the song, and never makes more than a handful of requests.
 */
object LyricsLookup {
    private const val MAX_GUESSES = 3

    suspend fun find(title: String, artist: String?, album: String?, durationMs: Long, source: LyricsSource): LrclibRecord? {
        val guesses = SongNames.guesses(title, artist).take(MAX_GUESSES)
        for (guess in guesses.filter { it.artist != null }) {
            val query = LyricsQuery(guess.title, guess.artist, album, durationMs)
            source.get(query)?.takeIf { LyricsMatcher.isSameSong(query, it) }?.let { return it }
        }
        for (guess in guesses) {
            val query = LyricsQuery(guess.title, guess.artist, album, durationMs)
            LyricsMatcher.best(query, source.search(listOfNotNull(guess.artist, guess.title).joinToString(" ")))?.let { return it }
        }
        // Last chance: the title alone, trusting only results with the same duration.
        val title0 = guesses.firstOrNull()?.title ?: return null
        val loose = LyricsQuery(title0, null, null, durationMs)
        return LyricsMatcher.best(loose, source.searchTitle(title0, null))
    }

    /** The search text to offer the user first: the most likely "artist title". */
    fun suggestedQuery(title: String, artist: String?): String =
        SongNames.guesses(title, artist).firstOrNull()?.let { listOfNotNull(it.artist, it.title).joinToString(" ") } ?: title
}
