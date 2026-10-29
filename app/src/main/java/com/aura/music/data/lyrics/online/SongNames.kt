package com.aura.music.data.lyrics.online

import com.aura.music.data.search.TextNormalizer

/** One way of reading a song's name: what the title and the artist probably are. */
data class SongGuess(val title: String, val artist: String?)

/**
 * Makes sense of song names as they come in real files: "Song - Band [Official Video]",
 * "01 - Band - Song (Lyric Video) ft. Someone", "band_-_song_(audio)", channel names like
 * "BandVEVO" or "Band - Topic". Produces clean title/artist guesses, most likely first.
 */
object SongNames {

    /** Bracketed or trailing parts that describe the upload, not the song. */
    private val noiseWords = listOf(
        "official", "oficial", "video", "vídeo", "videoclipe", "clipe", "clip", "lyric", "lyrics", "letra", "legendado",
        "legenda", "tradução", "traducao", "audio", "áudio", "visualizer", "visualiser", "hd", "hq", "4k", "1080p", "720p",
        "remaster", "remastered", "remasterizado", "explicit", "clean", "color coded", "mv", "m/v", "full song",
        "music video", "performance video", "live session", "karaoke", "instrumental version", "extended", "radio edit",
    )

    private val bracketed = Regex("""\s*[(\[{【]([^)\]}】]*)[)\]}】]""")
    private val featuring = Regex("""\s*[(\[]?\s*\b(?:ft\.?|feat\.?|featuring|part\.?|participação)\s+[^)\]\-–—|]*[)\]]?""", RegexOption.IGNORE_CASE)
    private val trackNumber = Regex("""^\s*(?:track\s*)?\d{1,3}\s*(?:[.\-–_)]|\s)\s*""", RegexOption.IGNORE_CASE)
    private val extension = Regex("""\.(mp3|m4a|flac|ogg|opus|wav|aac|wma|webm|mp4)$""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""\s+[-–—|~]\s+|\s*[|]\s*|\s+-|-\s+""")
    private val trailingNoise = Regex(
        """\s*[-–—|:]?\s*\b(official\s+(music\s+)?video|official\s+audio|official\s+lyric\s+video|lyric\s+video|lyrics|clipe\s+oficial|video\s+oficial|áudio\s+oficial|audio\s+oficial|ao\s+vivo\s+em\s+.*)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** "Song (Official Video) [HD]" → "Song". Keeps meaningful brackets like "(Parte 2)" or "(Ao Vivo)". */
    fun clean(raw: String): String {
        var text = raw.replace('_', ' ').replace(extension, "")
        text = bracketed.replace(text) { m -> if (isNoise(m.groupValues[1])) " " else m.value }
        text = featuring.replace(text, " ")
        text = trailingNoise.replace(text, "")
        return text.replace(Regex("""\s{2,}"""), " ").trim(' ', '-', '–', '—', '|', '.', ',')
    }

    /** "BandVEVO", "Band - Topic", "Band Official" → "Band". */
    fun cleanArtist(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "<unknown>") return null
        val text = clean(raw)
            .replace(Regex("""(?i)\s*-\s*topic$"""), "")
            .replace(Regex("""(?i)vevo$"""), "")
            .replace(Regex("""(?i)\s+official$"""), "")
            .trim()
        return text.ifBlank { null }
    }

    private fun isNoise(inside: String): Boolean {
        val words = TextNormalizer.normalize(inside)
        if (words.isEmpty()) return true
        return noiseWords.any { noise -> words == noise || words.contains(noise) }
    }

    /**
     * Title/artist readings of a song, most likely first: the tags as they are, then the title split
     * at " - " both ways (the side matching the artist tag tells which is which).
     */
    fun guesses(title: String, artist: String?): List<SongGuess> {
        val tagArtist = cleanArtist(artist)
        val withoutNumber = trackNumber.replace(title.replace('_', ' '), "")
        val cleaned = clean(withoutNumber)
        val result = LinkedHashSet<SongGuess>()
        val parts = cleaned.split(separators).map { clean(it) }.filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val first = parts.first()
            val rest = parts.drop(1).joinToString(" ")
            val last = parts.last()
            val head = parts.dropLast(1).joinToString(" ")
            val artistKey = TextNormalizer.normalize(tagArtist)
            when {
                // The artist tag says which side is the band.
                artistKey.isNotEmpty() && similar(first, artistKey) -> result += SongGuess(rest, tagArtist)
                artistKey.isNotEmpty() && similar(last, artistKey) -> result += SongGuess(head, tagArtist)
                else -> {
                    // "Band - Song" is the usual way; "Song - Band" the other common one.
                    result += SongGuess(rest, first)
                    result += SongGuess(head, last)
                }
            }
        }
        result += SongGuess(cleaned, tagArtist)
        if (tagArtist != null) result += SongGuess(cleaned, null)
        return result.filter { it.title.isNotBlank() }.distinctBy { TextNormalizer.normalize(it.title) to TextNormalizer.normalize(it.artist) }
    }

    private fun similar(text: String, normalizedArtist: String): Boolean {
        val side = TextNormalizer.normalize(text)
        return side == normalizedArtist || side.contains(normalizedArtist) || normalizedArtist.contains(side)
    }
}
