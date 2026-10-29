package com.aura.desktop.library

import com.aura.music.core.model.Track
import com.aura.music.data.search.TextNormalizer
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One audio file as last read; reused while its size and modification time don't change. */
@Serializable
data class ScannedFile(
    val path: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val durationMs: Long = 0,
    val bitrate: Int? = null,
    val codec: String? = null,
    val hasEmbeddedCover: Boolean = false,
)

data class ScanProgress(val done: Int, val total: Int)

/**
 * Walks the folders the user chose, reads new or changed audio files and keeps the rest from the
 * previous scan. Runs on background threads only; never follows links outside the chosen folders.
 */
class LibraryScanner(private val artwork: ArtworkStore, private val threads: Int = 4) {

    fun scan(
        folders: List<Path>,
        previous: Map<String, ScannedFile>,
        onProgress: (ScanProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<ScannedFile> {
        val candidates = folders.flatMap { listAudioFiles(it, isCancelled) }.distinctBy { it.first }
        val done = AtomicInteger(0)
        onProgress(ScanProgress(0, candidates.size))
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "aura-scan").apply { isDaemon = true } }
        try {
            val tasks = candidates.map { (path, attrs) ->
                Callable {
                    if (isCancelled()) return@Callable null
                    val key = path.toString()
                    val size = attrs.size()
                    val modified = attrs.lastModifiedTime().toMillis()
                    val cached = previous[key]
                    val result = if (cached != null && cached.sizeBytes == size && cached.modifiedMs == modified) {
                        cached
                    } else {
                        read(path, size, modified)
                    }
                    val n = done.incrementAndGet()
                    if (n % 25 == 0 || n == candidates.size) onProgress(ScanProgress(n, candidates.size))
                    result
                }
            }
            return pool.invokeAll(tasks).mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun read(path: Path, size: Long, modified: Long): ScannedFile? {
        val tags = runCatching { TagReader.read(path) }.getOrNull() ?: return null
        val scanned = ScannedFile(
            path = path.toString(),
            sizeBytes = size,
            modifiedMs = modified,
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            albumArtist = tags.albumArtist,
            genre = tags.genre,
            year = tags.year,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
            durationMs = tags.durationMs,
            bitrate = tags.bitrate,
            codec = tags.codec,
            hasEmbeddedCover = tags.cover != null,
        )
        tags.cover?.let { artwork.storeEmbedded(LibraryMapper.albumId(scanned), it) }
        return scanned
    }

    private fun listAudioFiles(root: Path, isCancelled: () -> Boolean): List<Pair<Path, BasicFileAttributes>> {
        if (!Files.isDirectory(root)) return emptyList()
        val found = mutableListOf<Pair<Path, BasicFileAttributes>>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isCancelled()) return FileVisitResult.TERMINATE
                val name = dir.fileName?.toString().orEmpty()
                val hidden = dir != root && (name.startsWith(".") || name.equals("\$RECYCLE.BIN", true) || runCatching { Files.isHidden(dir) }.getOrDefault(false))
                return if (hidden) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && attrs.size() > 0 && file.extension() in AUDIO_EXTENSIONS) found += file to attrs
                return FileVisitResult.CONTINUE
            }

            // Unreadable folders (permissions, disconnected drives) are skipped, not fatal.
            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return found
    }

    companion object {
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "ogg", "oga", "opus", "m4a", "m4b", "mp4", "aac", "wav", "aif", "aiff",
            "wma", "ape", "wv", "mka", "webm", "alac", "dsf", "mpc",
        )

        fun Path.extension(): String = fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
    }
}

/** Turns scanned files into the shared [Track] model with ids that stay stable between runs. */
object LibraryMapper {

    fun toTrack(file: ScannedFile): Track {
        val path = Path.of(file.path)
        val fileName = path.fileName.toString()
        return Track(
            id = trackId(file.path),
            uri = file.path,
            title = file.title ?: fileName.substringBeforeLast('.'),
            artist = file.artist,
            artistId = stableId("artist:" + TextNormalizer.normalize(file.artist)),
            album = file.album,
            albumId = albumId(file),
            albumArtist = file.albumArtist,
            durationMs = file.durationMs,
            trackNumber = file.trackNumber,
            discNumber = file.discNumber,
            year = file.year,
            dateAddedSec = file.modifiedMs / 1000,
            genre = file.genre,
            folder = folderOf(file.path),
            displayName = fileName,
            mimeType = mimeType(fileName),
            sizeBytes = file.sizeBytes,
            bitrate = file.bitrate,
            hasTags = file.artist != null || file.album != null,
        )
    }

    fun trackId(path: String): Long = stableId("file:" + normalizePath(path))

    /**
     * Albums group by name and album artist; without an album artist, by name and folder (so
     * compilations in one folder stay together). Files with no album group by folder.
     */
    fun albumId(file: ScannedFile): Long {
        val album = TextNormalizer.normalize(file.album)
        val folder = normalizePath(folderOf(file.path))
        return when {
            album.isEmpty() -> stableId("folder:$folder")
            !file.albumArtist.isNullOrBlank() -> stableId("album:$album|" + TextNormalizer.normalize(file.albumArtist))
            else -> stableId("album:$album|$folder")
        }
    }

    /** Parent folder with forward slashes and a trailing slash, like the Android model. */
    fun folderOf(path: String): String = path.replace('\\', '/').substringBeforeLast('/') + "/"

    private fun normalizePath(path: String): String = path.replace('\\', '/').let {
        if (System.getProperty("os.name").lowercase().contains("win")) it.lowercase() else it
    }

    fun stableId(text: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        var id = 0L
        for (i in 0 until 8) id = (id shl 8) or (digest[i].toLong() and 0xFF)
        return id and Long.MAX_VALUE
    }

    private fun mimeType(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "m4a", "m4b", "mp4", "aac", "alac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "aif", "aiff" -> "audio/aiff"
        "wma" -> "audio/x-ms-wma"
        else -> null
    }
}
