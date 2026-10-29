package com.aura.music.data.discover

import java.net.URI
import java.net.URLDecoder

/** What a pasted link points to. Streaming services are never treated as downloadable. */
sealed interface LinkKind {
    /** An audio file served over HTTPS; still validated with a HEAD request before downloading. */
    data class DirectFile(val url: String, val fileName: String) : LinkKind

    data class ArchiveItem(val identifier: String) : LinkKind

    data class YouTube(val url: String, val videoId: String) : LinkKind

    data class Spotify(val url: String, val type: String, val id: String) : LinkKind

    /** HTTPS page of unknown type: may still be audio, decided by its Content-Type. */
    data class Unknown(val url: String) : LinkKind

    /** Not a link the app can use (plain http, malformed, other schemes). */
    data class Invalid(val reason: InvalidReason) : LinkKind
}

enum class InvalidReason { NotALink, InsecureHttp, Unsupported }

/** Recognises links offline, without any network request. */
object LinkAnalyzer {

    private val audioExtensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "aif", "aiff", "alac", "wma")
    private val youTubeId = Regex("^[A-Za-z0-9_-]{11}$")

    /** True when [input] looks like a link rather than words to search for. */
    fun looksLikeLink(input: String): Boolean {
        val t = input.trim()
        return t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("spotify:", true) ||
            (!t.contains(' ') && Regex("""^[\w.-]+\.(com|be|org|net|io|fm|app|br)(/|$)""", RegexOption.IGNORE_CASE).containsMatchIn(t))
    }

    fun analyze(input: String): LinkKind {
        val raw = input.trim()
        if (raw.startsWith("spotify:", true)) {
            val parts = raw.split(':')
            return if (parts.size >= 3) LinkKind.Spotify("https://open.spotify.com/${parts[1]}/${parts[2]}", parts[1], parts[2])
            else LinkKind.Invalid(InvalidReason.Unsupported)
        }
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return LinkKind.Invalid(InvalidReason.NotALink)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return LinkKind.Invalid(InvalidReason.NotALink)
        if (scheme != "https" && scheme != "http") return LinkKind.Invalid(InvalidReason.Unsupported)
        val path = uri.rawPath.orEmpty()
        val segments = path.split('/').filter(String::isNotEmpty)

        when (host) {
            "youtube.com", "music.youtube.com" -> {
                val id = query(uri, "v") ?: segments.getOrNull(1)?.takeIf { segments.firstOrNull() in setOf("shorts", "live", "embed") }
                return if (id != null && youTubeId.matches(id)) LinkKind.YouTube(canonicalYouTube(id), id) else LinkKind.Invalid(InvalidReason.Unsupported)
            }
            "youtu.be" -> {
                val id = segments.firstOrNull()
                return if (id != null && youTubeId.matches(id)) LinkKind.YouTube(canonicalYouTube(id), id) else LinkKind.Invalid(InvalidReason.Unsupported)
            }
            "open.spotify.com", "spotify.link" -> {
                val list = segments.dropWhile { it.startsWith("intl-") }
                val type = list.getOrNull(0)
                val id = list.getOrNull(1)
                return if (type in setOf("track", "album", "playlist", "artist", "episode") && id != null) {
                    LinkKind.Spotify("https://open.spotify.com/$type/$id", type!!, id)
                } else {
                    LinkKind.Invalid(InvalidReason.Unsupported)
                }
            }
            "archive.org" -> if (segments.firstOrNull() == "details" && segments.size >= 2) return LinkKind.ArchiveItem(decode(segments[1]))
        }

        if (scheme == "http") return LinkKind.Invalid(InvalidReason.InsecureHttp)
        val fileName = decode(segments.lastOrNull().orEmpty())
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return if (extension in audioExtensions) LinkKind.DirectFile(uri.toString(), fileName) else LinkKind.Unknown(uri.toString())
    }

    fun isAudioExtension(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in audioExtensions

    private fun canonicalYouTube(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun query(uri: URI, key: String): String? = uri.rawQuery?.split('&')
        ?.map { it.split('=', limit = 2) }
        ?.firstOrNull { it.firstOrNull() == key }
        ?.getOrNull(1)
        ?.let(::decode)

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
