package com.aura.desktop.lyrics

import com.aura.desktop.audio.FfmpegRuntime
import com.aura.desktop.data.JsonFiles
import com.aura.music.core.model.Track
import com.aura.music.data.lyrics.LrcParser
import com.aura.music.data.lyrics.Lyrics
import com.aura.music.data.lyrics.TextDecoding
import com.aura.music.data.lyrics.online.LrclibClient
import com.aura.music.data.lyrics.online.LrclibRecord
import com.aura.music.data.lyrics.online.LyricsLookup
import com.aura.music.data.lyrics.online.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AV_DICT_IGNORE_SUFFIX
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.javacpp.BytePointer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Where the lyrics being shown came from; the UI says it, and only some can be removed. */
enum class LyricsOrigin { Custom, Sidecar, Embedded, Online }

data class LoadedLyrics(val lyrics: Lyrics, val origin: LyricsOrigin)

/**
 * Finds lyrics for a song, most personal first: lyrics the user chose or imported, a `.lrc` file
 * next to the song, lyrics inside the file's tags, then lyrics saved from LRCLIB. Looking online
 * only happens when the user turned it on; the result is saved so it also works offline.
 *
 * Saved lyrics live in the app's folder (never written into the music files or folders).
 */
class LyricsService(
    private val dir: Path,
    private val source: LyricsSource = LrclibClient(userAgent = "AURA Desktop (offline-first music player)"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    internal data class Miss(val at: Long)

    private val missesFile = dir.resolve("misses.json")
    private val missSerializer = MapSerializer(String.serializer(), Miss.serializer())
    private val misses: MutableMap<String, Miss> by lazy { JsonFiles.read(missesFile, missSerializer)?.toMutableMap() ?: mutableMapOf() }

    init {
        Files.createDirectories(dir)
    }

    /** Lyrics available without the internet, or null. */
    suspend fun local(track: Track): LoadedLyrics? = withContext(Dispatchers.IO) {
        read(customFile(track))?.let { return@withContext LoadedLyrics(it, LyricsOrigin.Custom) }
        sidecar(track)?.let { return@withContext LoadedLyrics(it, LyricsOrigin.Sidecar) }
        embedded(track)?.let { return@withContext LoadedLyrics(it, LyricsOrigin.Embedded) }
        read(onlineFile(track))?.let { return@withContext LoadedLyrics(it, LyricsOrigin.Online) }
        null
    }

    /**
     * Looks the song up on LRCLIB (only called when the user allows it). A song recently not found
     * isn't asked again for a while, to spare the service and the user's connection.
     */
    suspend fun fetchOnline(track: Track): LoadedLyrics? = withContext(Dispatchers.IO) {
        val key = key(track)
        misses[key]?.let { if (now() - it.at < MISS_TTL_MS) return@withContext null }
        val record = runCatching { LyricsLookup.find(track.title, track.artist, track.album, track.durationMs, source) }
            .getOrElse { return@withContext null } // offline or service busy: try again next time
        val text = record?.bestText
        if (text == null) {
            synchronized(misses) {
                misses[key] = Miss(now())
                runCatching { JsonFiles.write(missesFile, missSerializer, misses.toMap()) }
            }
            return@withContext null
        }
        write(onlineFile(track), text)
        LoadedLyrics(LrcParser.parse(text), LyricsOrigin.Online)
    }

    /** Free search for the user to pick from. Throws when offline, so the UI can say so. */
    suspend fun search(text: String): List<LrclibRecord> = withContext(Dispatchers.IO) {
        source.search(text).filter { it.hasLyrics }
    }

    fun suggestedQuery(track: Track): String = LyricsLookup.suggestedQuery(track.title, track.artist)

    /** Keeps the user's pick; it wins over every other source. */
    suspend fun choose(track: Track, record: LrclibRecord) {
        withContext(Dispatchers.IO) { record.bestText?.let { write(customFile(track), it) } }
    }

    /** Imports a .lrc/.txt file chosen by the user. Returns false when it holds no lyrics. */
    suspend fun import(track: Track, file: Path): Boolean = withContext(Dispatchers.IO) {
        val text = runCatching { TextDecoding.decode(Files.readAllBytes(file)) }.getOrNull() ?: return@withContext false
        if (LrcParser.parse(text).lines.isEmpty()) return@withContext false
        write(customFile(track), text)
        true
    }

    /** Forgets lyrics the app saved (custom or online). Lyrics in the file or a sidecar stay. */
    suspend fun removeSaved(track: Track) {
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(customFile(track))
            Files.deleteIfExists(onlineFile(track))
            synchronized(misses) { misses.remove(key(track)) }
        }
    }

    private fun key(track: Track) = track.id.toString()

    private fun customFile(track: Track) = dir.resolve("${key(track)}.custom.lrc")

    private fun onlineFile(track: Track) = dir.resolve("${key(track)}.online.lrc")

    private fun read(file: Path): Lyrics? =
        if (!Files.isRegularFile(file)) null
        else runCatching { LrcParser.parse(TextDecoding.decode(Files.readAllBytes(file))) }.getOrNull()?.takeIf { it.lines.isNotEmpty() }

    private fun write(file: Path, text: String) {
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temp, text)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** `Song.lrc` (or `Song.txt`) beside `Song.mp3`. */
    private fun sidecar(track: Track): Lyrics? {
        val audio = runCatching { Path.of(track.uri) }.getOrNull() ?: return null
        val base = audio.fileName.toString().substringBeforeLast('.')
        return listOf("$base.lrc", "$base.LRC", "$base.txt").asSequence()
            .map { audio.resolveSibling(it) }
            .firstNotNullOfOrNull(::read)
    }

    /** Lyrics tags (ID3 USLT/TXXX, Vorbis LYRICS/UNSYNCEDLYRICS, MP4 ©lyr) as FFmpeg exposes them. */
    private fun embedded(track: Track): Lyrics? {
        FfmpegRuntime.init()
        val format = AVFormatContext(null)
        if (avformat_open_input(format, BytePointer(track.uri, "UTF-8"), null, null) < 0) return null
        try {
            val dictionaries = buildList {
                format.metadata()?.let(::add)
                for (i in 0 until format.nb_streams()) format.streams(i).metadata()?.let(::add)
            }
            for (dict in dictionaries) {
                var entry: AVDictionaryEntry? = null
                while (true) {
                    entry = av_dict_get(dict, "", entry, AV_DICT_IGNORE_SUFFIX) ?: break
                    val name = entry.key().getString("UTF-8").lowercase()
                    if (name.startsWith("lyrics") || name == "unsyncedlyrics" || name == "uslt" || name == "syncedlyrics") {
                        val text = entry.value().getString("UTF-8")
                        val parsed = LrcParser.parse(text)
                        if (parsed.lines.isNotEmpty()) return parsed
                    }
                }
            }
            return null
        } finally {
            avformat_close_input(format)
        }
    }

    companion object {
        /** A song LRCLIB didn't have is asked again after a week. */
        const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
