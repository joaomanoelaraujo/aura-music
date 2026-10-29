package com.aura.desktop.discover

import com.aura.desktop.library.TagReader
import com.aura.music.data.discover.AudioOption
import com.aura.music.data.discover.DiscoverException
import com.aura.music.data.discover.DiscoverItem
import com.aura.music.data.discover.DownloadableTrack
import com.aura.music.data.discover.Http
import com.aura.music.data.discover.InternetArchive
import com.aura.music.data.discover.InvalidReason
import com.aura.music.data.discover.ItemDetail
import com.aura.music.data.discover.LinkAnalyzer
import com.aura.music.data.discover.LinkKind
import com.aura.music.data.discover.SourceKind
import com.aura.music.link.FileNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a pasted link turned out to be. */
sealed interface LinkResult {
    /** Files the source serves and allows to save. */
    data class Downloadable(val detail: ItemDetail) : LinkResult

    /** A streaming service: shown with its public metadata and opened in its own app/site. */
    data class Streaming(val item: DiscoverItem) : LinkResult

    data class Invalid(val message: String) : LinkResult
}

/**
 * Online discovery on the computer. Only sources that allow saving files are downloadable: the
 * Internet Archive (items whose uploader declared a Creative Commons or public-domain license) and
 * direct HTTPS links to audio files. YouTube and Spotify links only get their public metadata
 * (oEmbed, no keys) and open in the official app or site; nothing is extracted from them.
 *
 * Searching here sends the typed words to archive.org; the local library search never goes online.
 */
class DesktopDiscover {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String): List<DiscoverItem> = InternetArchive.parseSearch(Http.getText(InternetArchive.searchUrl(query)))

    suspend fun detail(item: DiscoverItem): ItemDetail = when (item.source) {
        SourceKind.InternetArchive -> archiveItem(item.key.removePrefix("ia:"))
        else -> throw DiscoverException(DiscoverException.Reason.NotFound)
    }

    suspend fun analyze(input: String): LinkResult = when (val kind = LinkAnalyzer.analyze(input)) {
        is LinkKind.ArchiveItem -> LinkResult.Downloadable(archiveItem(kind.identifier))
        is LinkKind.DirectFile -> LinkResult.Downloadable(directFile(kind.url, kind.fileName))
        is LinkKind.Unknown -> LinkResult.Downloadable(directFile(kind.url, null))
        is LinkKind.YouTube -> LinkResult.Streaming(oEmbed("https://www.youtube.com/oembed?format=json&url=", kind.url, SourceKind.YouTube))
        is LinkKind.Spotify -> LinkResult.Streaming(oEmbed("https://open.spotify.com/oembed?url=", kind.url, SourceKind.Spotify))
        is LinkKind.Invalid -> LinkResult.Invalid(
            when (kind.reason) {
                InvalidReason.InsecureHttp -> "Só links HTTPS são aceitos (o endereço começa com http://)."
                InvalidReason.NotALink -> "Isso não parece um link."
                InvalidReason.Unsupported -> "Esse tipo de link não é suportado."
            },
        )
    }

    private suspend fun archiveItem(identifier: String): ItemDetail =
        InternetArchive.parseItem(identifier, Http.getText(InternetArchive.metadataUrl(identifier)))
            ?: throw DiscoverException(DiscoverException.Reason.NoLicense)

    /** A direct HTTPS file: type and size come from the server; nothing is guessed beyond that. */
    private suspend fun directFile(url: String, nameFromUrl: String?): ItemDetail {
        val head = Http.head(url)
        val name = head.fileName ?: nameFromUrl ?: head.finalUrl.substringAfterLast('/').substringBefore('?')
        val type = head.contentType.orEmpty()
        val looksAudio = type.startsWith("audio/") || ((type.isEmpty() || type == "application/octet-stream" || type == "binary/octet-stream") && LinkAnalyzer.isAudioExtension(name))
        if (!looksAudio) throw DiscoverException(DiscoverException.Reason.NotAudio)
        val extension = name.substringAfterLast('.', "").lowercase().ifEmpty { "audio" }
        val option = AudioOption(
            formatLabel = extension.uppercase(),
            extension = extension,
            bitrateKbps = null,
            lossless = extension in setOf("flac", "wav", "aiff", "aif", "alac"),
            original = true,
            sizeBytes = head.contentLength,
            url = head.finalUrl,
            fileName = name,
            mimeType = type.ifEmpty { "application/octet-stream" },
        )
        val title = name.substringBeforeLast('.')
        val item = DiscoverItem("url:" + head.finalUrl, title, null, null, SourceKind.DirectLink, null, url)
        return ItemDetail(item, listOf(DownloadableTrack(title, null, null, listOf(option))))
    }

    private suspend fun oEmbed(endpoint: String, url: String, source: SourceKind): DiscoverItem {
        val body = json.parseToJsonElement(Http.getText(endpoint + URLEncoder.encode(url, "UTF-8"))).jsonObject
        fun text(key: String) = (body[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        return DiscoverItem("embed:$url", text("title") ?: url, text("author_name"), text("thumbnail_url"), source, null, url)
    }
}

enum class DownloadStatus { Queued, Running, Done, Failed, Cancelled }

data class DesktopDownload(
    val id: String,
    val title: String,
    val artist: String?,
    val source: SourceKind,
    val license: String?,
    val option: AudioOption,
    val status: DownloadStatus,
    val doneBytes: Long = 0,
    val totalBytes: Long? = option.sizeBytes,
    val error: String? = null,
    val savedPath: String? = null,
    /** Linked device to send the file to once saved (null: computer only). */
    val sendTo: String? = null,
) {
    val active: Boolean get() = status == DownloadStatus.Queued || status == DownloadStatus.Running
}

/**
 * Downloads one file at a time into a staging `.part` (resumable with HTTP Range), validates it,
 * then publishes it into the downloads folder (part of the library) under a name that never
 * overwrites anything.
 */
class DesktopDownloads(
    private val scope: CoroutineScope,
    private val staging: Path,
    private val folder: () -> Path,
    private val onSaved: (Path, DesktopDownload) -> Unit,
    private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    /** Opens the GET request; tests swap it for a local server. HTTPS is required otherwise. */
    private val open: (String) -> HttpURLConnection = { Http.open(it, "GET") },
    private val requireHttps: Boolean = true,
) {
    private val _downloads = MutableStateFlow<List<DesktopDownload>>(emptyList())
    val downloads: StateFlow<List<DesktopDownload>> = _downloads.asStateFlow()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private var worker: Job? = null

    init {
        Files.createDirectories(staging)
    }

    fun enqueue(item: DiscoverItem, track: DownloadableTrack, option: AudioOption, sendTo: String?) {
        val download = DesktopDownload(
            id = UUID.randomUUID().toString(),
            title = track.title,
            artist = item.artist,
            source = item.source,
            license = item.license,
            option = option,
            status = DownloadStatus.Queued,
            sendTo = sendTo,
        )
        _downloads.update { listOf(download) + it }
        pump()
    }

    fun cancel(id: String) {
        cancelled += id
        update(id) { if (it.active) it.copy(status = DownloadStatus.Cancelled) else it }
        Files.deleteIfExists(part(id))
    }

    fun retry(id: String) {
        cancelled -= id
        update(id) { if (it.status == DownloadStatus.Failed) it.copy(status = DownloadStatus.Queued, error = null) else it }
        pump()
    }

    fun clearFinished() = _downloads.update { list -> list.filter { it.active } }

    private fun pump() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val next = _downloads.value.lastOrNull { it.status == DownloadStatus.Queued } ?: break
                run(next)
            }
        }
    }

    private fun part(id: String): Path = staging.resolve("$id.part")

    private fun run(download: DesktopDownload) {
        val id = download.id
        update(id) { it.copy(status = DownloadStatus.Running, error = null) }
        try {
            val file = fetch(download)
            if (id in cancelled) return
            // The server's label isn't enough: the file must really contain audio.
            if (TagReader.read(file) == null) {
                Files.deleteIfExists(file)
                throw DownloadFailed("O arquivo baixado não é um áudio que o AURA consiga tocar.")
            }
            val saved = publish(file, download)
            update(id) { it.copy(status = DownloadStatus.Done, savedPath = saved.toString(), doneBytes = Files.size(saved), totalBytes = Files.size(saved)) }
            onSaved(saved, _downloads.value.first { it.id == id })
        } catch (e: DownloadFailed) {
            update(id) { if (it.status == DownloadStatus.Cancelled) it else it.copy(status = DownloadStatus.Failed, error = e.message) }
        } catch (e: DiscoverException) {
            update(id) { if (it.status == DownloadStatus.Cancelled) it else it.copy(status = DownloadStatus.Failed, error = reasonText(e.reason)) }
        } catch (e: IOException) {
            update(id) { if (it.status == DownloadStatus.Cancelled) it else it.copy(status = DownloadStatus.Failed, error = "A conexão caiu. Tente de novo: continua de onde parou.") }
        }
    }

    private class DownloadFailed(message: String) : Exception(message)

    /** Streams the file into its `.part`, resuming with Range when part of it is already there. */
    private fun fetch(download: DesktopDownload): Path {
        val target = part(download.id)
        var have = if (Files.exists(target)) Files.size(target) else 0L
        val connection = open(download.option.url)
        if (have > 0) connection.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = connection.responseCode
            if (requireHttps && !connection.url.toString().startsWith("https://")) throw DiscoverException(DiscoverException.Reason.Insecure)
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw DiscoverException(DiscoverException.Reason.NotFound)
            if (code == HttpURLConnection.HTTP_OK && have > 0) {
                // The server ignored the range: start over.
                Files.deleteIfExists(target)
                have = 0
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw DiscoverException(DiscoverException.Reason.Network)
            }
            val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (type.startsWith("text/") || type.contains("html") || type.contains("json")) throw DiscoverException(DiscoverException.Reason.NotAudio)
            val remaining = connection.contentLengthLong.takeIf { it >= 0 }
            val total = remaining?.let { it + have } ?: download.option.sizeBytes
            if (total != null && total > maxBytes) throw DiscoverException(DiscoverException.Reason.TooLarge)
            val dir = folder()
            Files.createDirectories(dir)
            if (total != null && Files.getFileStore(dir).usableSpace < (total - have) * 2 + SPACE_MARGIN) {
                throw DownloadFailed("Sem espaço suficiente no disco.")
            }
            update(download.id) { it.copy(totalBytes = total, doneBytes = have) }
            connection.inputStream.use { input -> copy(input, target, download.id, have, total) }
            if (download.id in cancelled) {
                Files.deleteIfExists(target)
                return target
            }
            if (total != null && Files.size(target) != total) throw IOException("Incomplete")
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun copy(input: InputStream, target: Path, id: String, start: Long, total: Long?) {
        var written = start
        Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { out ->
            val buffer = ByteArray(64 * 1024)
            var lastReport = 0L
            while (true) {
                if (id in cancelled) return
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                written += n
                if (written > maxBytes || (total != null && written > total)) throw DiscoverException(DiscoverException.Reason.TooLarge)
                if (written - lastReport > 256 * 1024) {
                    lastReport = written
                    update(id) { it.copy(doneBytes = written) }
                }
            }
        }
    }

    private fun publish(file: Path, download: DesktopDownload): Path {
        val dir = folder()
        Files.createDirectories(dir)
        val base = buildString {
            download.artist?.takeIf(String::isNotBlank)?.let { append(it).append(" - ") }
            append(download.title)
        }
        val ext = download.option.extension.ifEmpty { file.fileName.toString().substringAfterLast('.', "audio") }
        val name = FileNames.sanitize("$base.$ext").ifEmpty { "musica.$ext" }
        var n = 1
        while (true) {
            val candidate = dir.resolve(if (n == 1) name else FileNames.numbered(name, n))
            if (!Files.exists(candidate)) {
                Files.move(file, candidate, StandardCopyOption.ATOMIC_MOVE).also { return candidate }
            }
            n++
        }
    }

    private fun update(id: String, transform: (DesktopDownload) -> DesktopDownload) =
        _downloads.update { list -> list.map { if (it.id == id) transform(it) else it } }

    companion object {
        private const val SPACE_MARGIN = 64L * 1024 * 1024

        fun reasonText(reason: DiscoverException.Reason) = when (reason) {
            DiscoverException.Reason.Offline -> "Sem internet."
            DiscoverException.Reason.Network -> "O site não respondeu como esperado."
            DiscoverException.Reason.NotFound -> "Arquivo não encontrado na origem."
            DiscoverException.Reason.NotAudio -> "Esse link não leva a um arquivo de áudio."
            DiscoverException.Reason.TooLarge -> "Arquivo grande demais."
            DiscoverException.Reason.Insecure -> "A origem não usa HTTPS."
            DiscoverException.Reason.NoLicense -> "A origem não declara uma licença aberta para esse item."
        }
    }
}
