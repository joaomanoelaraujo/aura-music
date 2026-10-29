package com.aura.music.data.lyrics

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lyrics stored inside audio files, as exposed by Media3's extractors:
 * ID3 `SYLT` (synced) and `USLT` (unsynced) frames in MP3, the `©lyr` atom in MP4/M4A and
 * `LYRICS`/`UNSYNCEDLYRICS` Vorbis comments in FLAC, Ogg and Opus.
 */
@OptIn(UnstableApi::class)
object EmbeddedLyricsReader {

    fun read(metadata: Metadata): String? {
        var synced: String? = null
        var unsynced: String? = null
        for (i in 0 until metadata.length()) {
            when (val entry = metadata[i]) {
                is BinaryFrame -> when (entry.id) {
                    "SYLT" -> synced = synced ?: decodeSylt(entry.data)
                    "USLT" -> unsynced = unsynced ?: decodeUslt(entry.data)
                }
                is TextInformationFrame -> when {
                    entry.id == "USLT" -> unsynced = unsynced ?: entry.value
                    // Many taggers write lyrics as TXXX:LYRICS (Mp3tag, MusicBee) or TXXX:USLT (ffmpeg) instead of USLT.
                    entry.id == "TXXX" && entry.description?.uppercase() in LYRICS_KEYS -> unsynced = unsynced ?: entry.value
                }
                is VorbisComment -> if (entry.key.uppercase() in LYRICS_KEYS) {
                    unsynced = unsynced ?: entry.value
                }
            }
        }
        return (synced ?: unsynced)?.takeIf { it.isNotBlank() }
    }

    private val LYRICS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS", "USLT")

    /** USLT: encoding(1) language(3) descriptor(terminated) text. */
    internal fun decodeUslt(data: ByteArray): String? {
        if (data.size < 4) return null
        val charset = charsetFor(data[0]) ?: return null
        val descriptorEnd = terminator(data, 4, data[0])
        if (descriptorEnd < 0) return null
        val start = descriptorEnd + terminatorLength(data[0])
        return String(data, start, data.size - start, charset).trimEnd('\u0000').ifBlank { null }
    }

    /**
     * SYLT: encoding(1) language(3) stampFormat(1) contentType(1) descriptor(terminated), then
     * repeated (text, terminator, 4-byte timestamp). Only millisecond stamps (format 2) are synced
     * in time; MPEG-frame stamps are rare and are skipped.
     */
    internal fun decodeSylt(data: ByteArray): String? {
        if (data.size < 6 || data[4].toInt() != 2) return null
        val encoding = data[0]
        val charset = charsetFor(encoding) ?: return null
        val step = terminatorLength(encoding)
        var pos = terminator(data, 6, encoding).takeIf { it >= 0 }?.plus(step) ?: return null
        val out = StringBuilder()
        while (pos < data.size) {
            val end = terminator(data, pos, encoding)
            if (end < 0 || end + step + 4 > data.size) break
            val text = String(data, pos, end - pos, charset).trim()
            val stampPos = end + step
            val time = ((data[stampPos].toLong() and 0xFF) shl 24) or ((data[stampPos + 1].toLong() and 0xFF) shl 16) or
                ((data[stampPos + 2].toLong() and 0xFF) shl 8) or (data[stampPos + 3].toLong() and 0xFF)
            out.append(LrcParser.formatStamp(time)).append(text).append('\n')
            pos = stampPos + 4
        }
        return out.toString().ifBlank { null }
    }

    private fun charsetFor(encoding: Byte): Charset? = when (encoding.toInt()) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> null
    }

    private fun terminatorLength(encoding: Byte) = if (encoding.toInt() == 1 || encoding.toInt() == 2) 2 else 1

    private fun terminator(data: ByteArray, from: Int, encoding: Byte): Int {
        if (terminatorLength(encoding) == 1) {
            for (i in from until data.size) if (data[i] == 0.toByte()) return i
            return -1
        }
        var i = from
        while (i + 1 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
            i += 2
        }
        return -1
    }
}

/**
 * Embedded lyrics found by the playback service for recently played tracks. The service owns the
 * extractor output, so it publishes what it finds here for the UI (same process, no I/O).
 */
@Singleton
class EmbeddedLyricsStore @Inject constructor() {
    private val _lyrics = MutableStateFlow<Map<Long, String>>(emptyMap())
    val lyrics: StateFlow<Map<Long, String>> = _lyrics.asStateFlow()

    fun put(trackId: Long, text: String) = _lyrics.update { current ->
        val trimmed = if (current.size >= MAX_ENTRIES) current.entries.drop(current.size - MAX_ENTRIES + 1).associate { it.toPair() } else current
        trimmed + (trackId to text)
    }

    private companion object {
        const val MAX_ENTRIES = 32
    }
}
