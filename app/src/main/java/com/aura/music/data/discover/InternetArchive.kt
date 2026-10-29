package com.aura.music.data.discover

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Internet Archive: a public catalog whose API needs no key. Only items whose uploader declared a
 * Creative Commons or public-domain license are shown, and only files the archive really serves
 * are offered; their formats, sizes and the "original" flag come straight from the item metadata.
 */
object InternetArchive {

    private val json = Json { ignoreUnknownKeys = true }

    /** Formats the archive labels on audio files, mapped to what the user sees. */
    private data class Format(val label: String, val extension: String, val mime: String, val bitrate: Int?, val lossless: Boolean, val rank: Int)

    private val formats = mapOf(
        "flac" to Format("FLAC", "flac", "audio/flac", null, true, 0),
        "24bit flac" to Format("FLAC 24-bit", "flac", "audio/flac", null, true, 0),
        "apple lossless audio" to Format("ALAC", "m4a", "audio/mp4", null, true, 1),
        "wave" to Format("WAV", "wav", "audio/wav", null, true, 2),
        "aiff" to Format("AIFF", "aiff", "audio/aiff", null, true, 2),
        "vbr mp3" to Format("MP3 VBR", "mp3", "audio/mpeg", null, false, 3),
        "320kbps mp3" to Format("MP3 320 kbps", "mp3", "audio/mpeg", 320, false, 3),
        "256kbps mp3" to Format("MP3 256 kbps", "mp3", "audio/mpeg", 256, false, 4),
        "192kbps mp3" to Format("MP3 192 kbps", "mp3", "audio/mpeg", 192, false, 5),
        "160kbps mp3" to Format("MP3 160 kbps", "mp3", "audio/mpeg", 160, false, 6),
        "128kbps mp3" to Format("MP3 128 kbps", "mp3", "audio/mpeg", 128, false, 7),
        "64kbps mp3" to Format("MP3 64 kbps", "mp3", "audio/mpeg", 64, false, 8),
        "aac" to Format("AAC", "m4a", "audio/mp4", null, false, 6),
        "ogg vorbis" to Format("Ogg Vorbis", "ogg", "audio/ogg", null, false, 6),
        "opus" to Format("Opus", "opus", "audio/ogg", null, false, 6),
    )

    fun searchUrl(query: String, rows: Int = 30): String {
        val q = "(${query.trim()}) AND mediatype:audio AND (licenseurl:*creativecommons* OR licenseurl:*publicdomain*) " +
            "AND format:(\"VBR MP3\" OR Flac OR \"128Kbps MP3\" OR \"Ogg Vorbis\")"
        return "https://archive.org/advancedsearch.php?q=${enc(q)}" +
            listOf("identifier", "title", "creator", "licenseurl").joinToString("") { "&fl%5B%5D=$it" } +
            "&sort%5B%5D=downloads+desc&rows=$rows&output=json"
    }

    fun metadataUrl(identifier: String) = "https://archive.org/metadata/${enc(identifier)}"

    fun coverUrl(identifier: String) = "https://archive.org/services/img/${enc(identifier)}"

    fun pageUrl(identifier: String) = "https://archive.org/details/${enc(identifier)}"

    fun parseSearch(body: String): List<DiscoverItem> {
        val docs = json.parseToJsonElement(body).jsonObject["response"]?.jsonObject?.get("docs") as? JsonArray ?: return emptyList()
        return docs.mapNotNull { element ->
            val doc = element as? JsonObject ?: return@mapNotNull null
            val id = doc.text("identifier") ?: return@mapNotNull null
            val license = doc.text("licenseurl")?.let(::licenseLabel) ?: return@mapNotNull null
            DiscoverItem(
                key = "ia:$id",
                title = doc.text("title") ?: id,
                artist = doc.text("creator"),
                coverUrl = coverUrl(id),
                source = SourceKind.InternetArchive,
                license = license,
                pageUrl = pageUrl(id),
            )
        }
    }

    /** Parses /metadata/{id}; returns null when the item declares no open license. */
    fun parseItem(identifier: String, body: String): ItemDetail? {
        val root = json.parseToJsonElement(body).jsonObject
        val metadata = root["metadata"] as? JsonObject ?: return null
        val license = metadata.text("licenseurl")?.let(::licenseLabel) ?: return null
        val files = (root["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val item = DiscoverItem(
            key = "ia:$identifier",
            title = metadata.text("title") ?: identifier,
            artist = metadata.text("creator"),
            coverUrl = coverUrl(identifier),
            source = SourceKind.InternetArchive,
            license = license,
            pageUrl = pageUrl(identifier),
        )

        // Every audio file, grouped under the original it was derived from.
        val audio = files.mapNotNull { f ->
            val name = f.text("name") ?: return@mapNotNull null
            val format = formats[f.text("format")?.lowercase()] ?: return@mapNotNull null
            val isOriginal = f.text("source") == "original"
            Triple(if (isOriginal) name else f.text("original") ?: name, f, AudioOption(
                formatLabel = format.label,
                extension = format.extension,
                bitrateKbps = format.bitrate,
                lossless = format.lossless,
                original = isOriginal,
                sizeBytes = f.text("size")?.toLongOrNull(),
                url = "https://archive.org/download/${enc(identifier)}/${encPath(name)}",
                fileName = name.substringAfterLast('/'),
                mimeType = format.mime,
            ) to format.rank)
        }
        val tracks = audio.groupBy { it.first }.map { (root, group) ->
            // Tags often live on derivatives (e.g. the MP3 made from a WAV original), so look at all.
            val files = group.sortedByDescending { it.third.first.original }.map { it.second }
            DownloadableTrack(
                title = files.firstNotNullOfOrNull { it.text("title") } ?: root.substringAfterLast('/').substringBeforeLast('.'),
                trackNumber = files.firstNotNullOfOrNull { it.text("track")?.substringBefore('/')?.trim()?.toIntOrNull() },
                durationSec = group.firstNotNullOfOrNull { it.second.text("length")?.toDoubleOrNull() },
                options = group.map { it.third }
                    .distinctBy { it.first.url }
                    .sortedWith(compareByDescending<Pair<AudioOption, Int>> { it.first.original }.thenBy { it.second })
                    .map { it.first },
            )
        }.sortedWith(compareBy<DownloadableTrack> { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title })
        return ItemDetail(item, tracks)
    }

    /** "https://creativecommons.org/licenses/by-sa/4.0/" → "CC BY-SA 4.0"; null when not open. */
    fun licenseLabel(url: String): String? {
        val u = url.lowercase()
        return when {
            "publicdomain/zero" in u -> "CC0"
            "publicdomain" in u -> "Domínio público"
            "creativecommons.org/licenses/" in u -> {
                val parts = u.substringAfter("/licenses/").trim('/').split('/')
                "CC " + parts.first().uppercase() + (parts.getOrNull(1)?.let { " $it" } ?: "")
            }
            else -> null
        }
    }

    private fun JsonObject.text(key: String): String? = when (val v: JsonElement? = this[key]) {
        is JsonPrimitive -> v.contentOrNull?.takeIf(String::isNotBlank)
        is JsonArray -> v.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        else -> null
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encPath(path: String) = path.split('/').joinToString("/") { enc(it) }
}
