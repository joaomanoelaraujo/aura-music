package com.aura.music.data.search

import com.aura.music.core.model.Album
import com.aura.music.core.model.Artist
import com.aura.music.core.model.Playlist
import com.aura.music.core.model.Track
import com.aura.music.data.media.LibraryIndex
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.min

/** Case-, accent- and punctuation-insensitive text, split into words. */
object TextNormalizer {
    private val diacritics = "\\p{Mn}+".toRegex()
    private val separators = "[^\\p{L}\\p{N}]+".toRegex()

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(diacritics, "")
            .lowercase()
            .replace(separators, " ")
            .trim()
    }

    fun tokens(normalized: String): List<String> = if (normalized.isEmpty()) emptyList() else normalized.split(' ')
}

/** Listening signals used to order results that match equally well. Never sent anywhere. */
data class PersonalSignals(
    val plays: Map<Long, Int> = emptyMap(),
    val lastPlayed: Map<Long, Long> = emptyMap(),
    val favorites: Set<Long> = emptySet(),
    val now: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
) {
    companion object {
        val None = PersonalSignals(enabled = false)
    }
}

enum class SearchFilter { All, Songs, Artists, Albums, Playlists, Audios }

data class SmartResults(
    val query: String = "",
    val top: TopResult? = null,
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val audios: List<Track> = emptyList(),
    /** Words that complete or correct what the user is typing. */
    val suggestions: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty() && audios.isEmpty()
}

sealed interface TopResult {
    data class OfTrack(val track: Track) : TopResult
    data class OfArtist(val artist: Artist) : TopResult
    data class OfAlbum(val album: Album) : TopResult
}

/**
 * Precomputed, immutable search corpus for one library snapshot. Building it normalizes every
 * field once; each query then only touches the distinct-word vocabulary for typo tolerance and
 * compares integers, which keeps typing smooth on libraries with tens of thousands of tracks.
 *
 * Relevance tiers are spaced further apart than the maximum personalization boost, so a
 * frequently played song can overtake an equally good match but never an exact one.
 */
class SearchCorpus(index: LibraryIndex, playlists: List<Playlist>) {

    private class Field(val text: String, val tokens: List<String>)

    private class Entry<T>(val item: T, val id: Long, val fields: List<Pair<Field, Double>>, val all: List<String>)

    private val tracks = index.tracks.map { trackEntry(it) }
    private val audios = index.audios.map { trackEntry(it) }
    private val artists = index.artists.filter { it.name != null }.map { a -> entry(a, a.id, listOf(a.name to 1.0)) }
    private val albums = index.albums.filter { it.title != null }.map { a -> entry(a, a.id, listOf(a.title to 1.0, a.artist to ARTIST_WEIGHT)) }
    private val lists = playlists.map { p -> entry(p, p.id, listOf(p.name to 1.0)) }
    private val artistTrackIds: Map<Long, List<Long>> = index.tracks.groupBy({ it.artistId }, { it.id })
    private val albumTrackIds: Map<Long, List<Long>> = index.tracks.groupBy({ it.albumId }, { it.id })

    private val vocabulary: Array<String> = buildSet {
        (tracks + audios).forEach { addAll(it.all) }
        artists.forEach { addAll(it.all) }
        albums.forEach { addAll(it.all) }
        lists.forEach { addAll(it.all) }
    }.toTypedArray()

    private fun trackEntry(t: Track) = entry(t, t.id, listOf(t.title to 1.0, t.artist to ARTIST_WEIGHT, t.album to ALBUM_WEIGHT))

    private fun <T> entry(item: T, id: Long, raw: List<Pair<String?, Double>>): Entry<T> {
        val fields = raw.mapNotNull { (text, weight) ->
            val n = TextNormalizer.normalize(text)
            if (n.isEmpty()) null else Field(n, TextNormalizer.tokens(n)) to weight
        }
        return Entry(item, id, fields, fields.flatMap { it.first.tokens }.distinct())
    }

    fun search(rawQuery: String, signals: PersonalSignals, filter: SearchFilter = SearchFilter.All, history: List<String> = emptyList()): SmartResults {
        val q = TextNormalizer.normalize(rawQuery)
        if (q.isEmpty()) return SmartResults()
        val query = Query(q, TextNormalizer.tokens(q), matchVocabulary(TextNormalizer.tokens(q)))

        fun wants(f: SearchFilter) = filter == SearchFilter.All || filter == f

        val rankedTracks = if (wants(SearchFilter.Songs)) rank(tracks, query, MAX_TRACKS, crossField = true) { boost(signals, listOf(it)) } else emptyList()
        val rankedArtists = if (wants(SearchFilter.Artists)) {
            rank(artists, query, MAX_GROUPED) { boost(signals, artistTrackIds[it].orEmpty()) }
        } else {
            emptyList()
        }
        val rankedAlbums = if (wants(SearchFilter.Albums)) {
            rank(albums, query, MAX_GROUPED, crossField = true) { boost(signals, albumTrackIds[it].orEmpty()) }
        } else {
            emptyList()
        }
        val rankedLists = if (wants(SearchFilter.Playlists)) rank(lists, query, MAX_GROUPED) { 0.0 } else emptyList()
        val rankedAudios = if (wants(SearchFilter.Audios)) rank(audios, query, MAX_AUDIOS) { 0.0 } else emptyList()

        val top = listOfNotNull(
            rankedArtists.firstOrNull()?.let { TopResult.OfArtist(it.first) to it.second + TOP_ARTIST_BIAS },
            rankedTracks.firstOrNull()?.let { TopResult.OfTrack(it.first) to it.second },
            rankedAlbums.firstOrNull()?.let { TopResult.OfAlbum(it.first) to it.second },
        ).maxByOrNull { it.second }?.takeIf { it.second >= TOP_MIN_SCORE }?.first

        return SmartResults(
            query = rawQuery,
            top = if (filter == SearchFilter.All) top else null,
            tracks = rankedTracks.map { it.first },
            artists = rankedArtists.map { it.first },
            albums = rankedAlbums.map { it.first },
            playlists = rankedLists.map { it.first },
            audios = rankedAudios.map { it.first },
            suggestions = suggestions(q, query, history),
        )
    }

    /** Every music track matching [rawQuery], keeping library order (used by the track picker filter). */
    fun filterTracks(rawQuery: String): List<Track> {
        val q = TextNormalizer.normalize(rawQuery)
        if (q.isEmpty()) return tracks.map { it.item }
        val query = Query(q, TextNormalizer.tokens(q), matchVocabulary(TextNormalizer.tokens(q)))
        return tracks.filter { relevance(it, query, crossField = true) != null }.map { it.item }
    }

    private class Query(val text: String, val tokens: List<String>, val matches: List<Map<String, Int>>)

    /**
     * For each query word, the vocabulary words it can stand for and how well:
     * 0 exact, 1 prefix, 2 one typo, 3 two typos.
     */
    private fun matchVocabulary(tokens: List<String>): List<Map<String, Int>> = tokens.map { qt ->
        val allowed = allowedEdits(qt.length)
        val result = HashMap<String, Int>()
        for (word in vocabulary) {
            val quality = when {
                word == qt -> 0
                word.startsWith(qt) -> 1
                allowed == 0 -> -1
                else -> {
                    // Compare against the whole word and against its prefix of similar length, so
                    // "mendoca" finds "mendonca" and a half-typed "mendoc" does too.
                    val whole = if (kotlin.math.abs(word.length - qt.length) <= allowed) Fuzzy.distance(qt, word, allowed) else Int.MAX_VALUE
                    val prefix = if (word.length > qt.length) Fuzzy.distance(qt, word.substring(0, min(word.length, qt.length + 1)), allowed) else Int.MAX_VALUE
                    val d = min(whole, prefix)
                    if (d <= allowed) 1 + d else -1
                }
            }
            if (quality >= 0) result[word] = quality
        }
        result
    }

    private fun <T> rank(entries: List<Entry<T>>, query: Query, limit: Int, crossField: Boolean = false, boost: (Long) -> Double): List<Pair<T, Double>> {
        val scored = ArrayList<Triple<T, Double, Double>>()
        for (entry in entries) {
            val base = relevance(entry, query, crossField) ?: continue
            scored += Triple(entry.item, base + boost(entry.id), base)
        }
        scored.sortWith(compareByDescending<Triple<T, Double, Double>> { it.second }.thenByDescending { it.third })
        return scored.take(limit).map { it.first to it.second }
    }

    private fun relevance(entry: Entry<*>, query: Query, crossField: Boolean): Double? {
        var best: Double? = null
        for ((field, weight) in entry.fields) {
            val score = fieldScore(field, query) ?: continue
            val weighted = score * weight
            if (best == null || weighted > best) best = weighted
        }
        // Multi-word queries may mix fields: "belchior coracao" = artist + title.
        if (crossField && query.tokens.size > 1) {
            tokenScore(entry.all, query)?.let { (exactish, edits) ->
                val cross = if (exactish) CROSS_FIELD else FUZZY - edits * EDIT_PENALTY - 4
                if (best == null || cross > best) best = cross
            }
        }
        return best
    }

    private fun fieldScore(field: Field, query: Query): Double? {
        val q = query.text
        return when {
            field.text == q -> EXACT
            field.text.startsWith(q) -> PREFIX
            else -> {
                val tokens = tokenScore(field.tokens, query)
                when {
                    tokens != null && tokens.first -> WORDS
                    field.text.contains(q) -> SUBSTRING
                    tokens != null -> FUZZY - tokens.second * EDIT_PENALTY
                    else -> null
                }
            }
        }
    }

    /** Every query word must match a word of [tokens], in any order. Returns (no typos?, total typos). */
    private fun tokenScore(tokens: List<String>, query: Query): Pair<Boolean, Int>? {
        var edits = 0
        for (matches in query.matches) {
            var bestQuality = Int.MAX_VALUE
            for (token in tokens) {
                val quality = matches[token] ?: continue
                if (quality < bestQuality) bestQuality = quality
                if (bestQuality == 0) break
            }
            if (bestQuality == Int.MAX_VALUE) return null
            if (bestQuality >= 2) edits += bestQuality - 1
        }
        return (edits == 0) to edits
    }

    private fun boost(signals: PersonalSignals, trackIds: List<Long>): Double {
        if (!signals.enabled || trackIds.isEmpty()) return 0.0
        var plays = 0
        var last = 0L
        var favorite = false
        for (id in trackIds) {
            plays += signals.plays[id] ?: 0
            last = maxOf(last, signals.lastPlayed[id] ?: 0L)
            favorite = favorite || id in signals.favorites
        }
        val frequency = min(4.5, 1.6 * ln(1.0 + plays))
        val days = (signals.now - last) / DAY_MS.toDouble()
        val recency = if (last > 0 && days < RECENCY_DAYS) 1.5 * (1 - days / RECENCY_DAYS) else 0.0
        return min(MAX_BOOST, frequency + recency + if (favorite) 1.0 else 0.0)
    }

    private fun suggestions(q: String, query: Query, history: List<String>): List<String> {
        val fromHistory = history.filter { TextNormalizer.normalize(it).let { n -> n.startsWith(q) && n != q } }
        // Corrections/completions of the last word being typed.
        val last = query.matches.lastOrNull().orEmpty()
            .filter { (word, quality) -> quality in 1..3 && word != query.tokens.last() }
            .entries.sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
            .take(MAX_SUGGESTIONS)
            .map { (word, _) -> (query.tokens.dropLast(1) + word).joinToString(" ") }
        return (fromHistory + last).distinctBy { TextNormalizer.normalize(it) }.take(MAX_SUGGESTIONS)
    }

    companion object {
        const val EXACT = 100.0
        const val PREFIX = 91.0
        const val WORDS = 82.0
        const val CROSS_FIELD = 78.0
        const val SUBSTRING = 72.0
        const val FUZZY = 60.0
        const val EDIT_PENALTY = 5.0
        const val MAX_BOOST = 7.0
        private const val ARTIST_WEIGHT = 0.93
        private const val ALBUM_WEIGHT = 0.86
        private const val TOP_ARTIST_BIAS = 2.0
        private const val TOP_MIN_SCORE = 70.0
        private const val MAX_TRACKS = 60
        private const val MAX_GROUPED = 12
        private const val MAX_AUDIOS = 20
        private const val MAX_SUGGESTIONS = 4
        private const val RECENCY_DAYS = 14.0
        private const val DAY_MS = 86_400_000L

        /** Short words must match exactly (or as a prefix); longer ones tolerate one or two typos. */
        fun allowedEdits(length: Int): Int = when {
            length <= 3 -> 0
            length <= 6 -> 1
            else -> 2
        }
    }
}

/** Optimal-string-alignment distance (Damerau–Levenshtein with adjacent swaps), bounded for speed. */
object Fuzzy {
    fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return Int.MAX_VALUE
        val n = a.length
        val m = b.length
        var prevPrev = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, prevPrev[j - 2] + 1)
                curr[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return Int.MAX_VALUE
            val tmp = prevPrev
            prevPrev = prev
            prev = curr
            curr = tmp
        }
        return if (prev[m] <= max) prev[m] else Int.MAX_VALUE
    }
}
