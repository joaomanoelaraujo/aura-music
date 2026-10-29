package com.aura.music.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.aura.music.core.model.Track
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.local.db.LyricsDao
import com.aura.music.data.local.db.TrackLyricsEntity
import com.aura.music.data.lyrics.online.LrclibRecord
import com.aura.music.data.lyrics.online.OnlineLyrics
import com.aura.music.data.search.TextNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LyricsSource { Custom, File, Embedded, Online, None }

data class LyricsState(
    val lyrics: Lyrics = Lyrics.Empty,
    val source: LyricsSource = LyricsSource.None,
    /** User offset for this track (positive = lyrics earlier), added to the file's own offset. */
    val userOffsetMs: Long = 0,
    /** The text the lyrics came from, for the editor. */
    val rawText: String = "",
) {
    val totalOffsetMs: Long get() = userOffsetMs + lyrics.fileOffsetMs
}

sealed interface ImportResult {
    data object Saved : ImportResult
    data object TooLarge : ImportResult
    data object NoLyrics : ImportResult
    data object Unreadable : ImportResult
}

/**
 * Resolves lyrics for a track. Priority: lyrics the user picked or edited > a matching `.lrc` file
 * in a folder the user granted access to > lyrics embedded in the file > lyrics found online
 * earlier (stored in the app, so they work offline too).
 */
@Singleton
class LyricsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: LyricsDao,
    private val embedded: EmbeddedLyricsStore,
    private val folders: LyricsFolderIndex,
    private val online: OnlineLyrics,
    settings: SettingsRepository,
) {
    private val lyricsFolders = settings.settings.map { it.lyricsFolders }.distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(track: Track): Flow<LyricsState> {
        val sidecar = lyricsFolders.mapLatest { uris -> folders.find(uris, track)?.let { readText(it) } }
        val inFile = embedded.lyrics.map { it[track.id] }.distinctUntilChanged()
        val found = dao.observeOnline(track.id).map { it?.content }.distinctUntilChanged()
        return combine(dao.observe(track.id), sidecar, inFile, found) { saved, fileText, embeddedText, onlineText ->
            val offset = saved?.offsetMs ?: 0L
            val (text, source) = when {
                !saved?.content.isNullOrBlank() -> saved!!.content!! to LyricsSource.Custom
                !fileText.isNullOrBlank() -> fileText to LyricsSource.File
                !embeddedText.isNullOrBlank() -> embeddedText to LyricsSource.Embedded
                !onlineText.isNullOrBlank() -> onlineText to LyricsSource.Online
                else -> "" to LyricsSource.None
            }
            LyricsState(if (text.isEmpty()) Lyrics.Empty else LrcParser.parse(text), source, offset, text)
        }
    }

    /**
     * Looks for this song's lyrics online when it has none of its own (and the user allows it).
     * Whatever is found is stored with the song and shows up through [observe], offline too.
     */
    suspend fun fetchOnlineIfMissing(track: Track): Boolean {
        if (observe(track).first().source != LyricsSource.None) return false
        return online.fetchIfNeeded(track)
    }

    suspend fun searchOnline(track: Track, text: String? = null) = online.search(track, text)

    /**
     * The user picked these lyrics for the song: they become its own, above every other source
     * (even wrong lyrics embedded in the file).
     */
    suspend fun chooseOnline(trackId: Long, record: LrclibRecord) {
        online.choose(trackId, record)
        record.bestText?.let { saveCustom(trackId, it) }
    }

    suspend fun removeOnline(trackId: Long) = online.remove(trackId)

    suspend fun setOffset(trackId: Long, offsetMs: Long) = update(trackId) { it.copy(offsetMs = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)) }

    suspend fun saveCustom(trackId: Long, text: String) = update(trackId) { it.copy(content = text.takeIf(String::isNotBlank)) }

    /** Forgets edited or imported lyrics; the automatic source is used again. The offset is kept. */
    suspend fun clearCustom(trackId: Long) = update(trackId) { it.copy(content = null) }

    /** Copies a user-picked LRC/TXT into the app so it keeps working without the original file. */
    suspend fun import(trackId: Long, uri: Uri): ImportResult {
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = input.readNBytesCompat(MAX_FILE_BYTES + 1)
                    buffer
                }
            }.onFailure { Log.w(TAG, "Could not read picked lyrics", it) }.getOrNull()
        } ?: return ImportResult.Unreadable
        if (bytes.size > MAX_FILE_BYTES) return ImportResult.TooLarge
        val text = TextDecoding.decode(bytes)
        if (LrcParser.parse(text).isEmpty) return ImportResult.NoLyrics
        saveCustom(trackId, text)
        return ImportResult.Saved
    }

    private val writeLock = Mutex()

    private suspend fun update(trackId: Long, change: (TrackLyricsEntity) -> TrackLyricsEntity) = writeLock.withLock {
        val current = dao.get(trackId) ?: TrackLyricsEntity(trackId, content = null, offsetMs = 0, updatedAt = 0)
        dao.upsert(change(current).copy(updatedAt = System.currentTimeMillis()))
        dao.pruneDefaults()
    }

    private suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(MAX_FILE_BYTES) }?.let(TextDecoding::decode)
        }.onFailure { Log.w(TAG, "Could not read lyrics file", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "LyricsRepository"
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_OFFSET_MS = 60_000L
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - total))
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}

/** Human-readable name for a granted folder, e.g. "Music/Lyrics". */
object LyricsFolderLabel {
    fun of(treeUri: String): String = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)).substringAfter(':').ifBlank { treeUri }
    }.getOrDefault(treeUri)
}

/**
 * Index of `.lrc` files inside folders the user granted through the system folder picker
 * (Storage Access Framework). Scoped storage does not let apps read non-media files such as
 * `.lrc` next to the songs, so access must be granted explicitly; nothing else is read.
 */
@Singleton
class LyricsFolderIndex @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val lock = Mutex()
    private var indexedFor: Set<String>? = null
    private var byKey: Map<String, Uri> = emptyMap()

    suspend fun find(treeUris: Set<String>, track: Track): Uri? {
        if (treeUris.isEmpty()) return null
        val index = lock.withLock {
            if (indexedFor != treeUris) {
                byKey = withContext(Dispatchers.IO) { build(treeUris) }
                indexedFor = treeUris
            }
            byKey
        }
        return keysFor(track).firstNotNullOfOrNull { index[it] }
    }

    /** Forces a rescan the next time lyrics are looked up (e.g. after adding files). */
    suspend fun invalidate() = lock.withLock { indexedFor = null }

    private fun keysFor(track: Track): List<String> = listOfNotNull(
        TextNormalizer.normalize(track.displayName.substringBeforeLast('.')),
        track.artist?.let { TextNormalizer.normalize("$it ${track.title}") },
        TextNormalizer.normalize(track.title),
    ).filter(String::isNotEmpty)

    private fun build(treeUris: Set<String>): Map<String, Uri> {
        val result = HashMap<String, Uri>()
        for (raw in treeUris) {
            val tree = Uri.parse(raw)
            runCatching {
                walk(tree, DocumentsContract.getTreeDocumentId(tree), depth = 0) { name, uri ->
                    val base = name.substringBeforeLast('.')
                    result.putIfAbsent(TextNormalizer.normalize(base), uri)
                }
            }.onFailure { Log.w("LyricsFolderIndex", "Cannot list $raw", it) }
        }
        return result
    }

    private fun walk(tree: Uri, documentId: String, depth: Int, onLrc: (String, Uri) -> Unit) {
        if (depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> walk(tree, id, depth + 1, onLrc)
                    name.endsWith(".lrc", ignoreCase = true) -> onLrc(name, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 6
    }
}
