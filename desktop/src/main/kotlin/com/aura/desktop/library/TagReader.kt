package com.aura.desktop.library

import com.aura.desktop.audio.FfmpegRuntime
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.file.Path

/** What a file says about itself. Missing tags stay null; nothing is guessed here. */
data class FileTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int,
    val discNumber: Int,
    val durationMs: Long,
    val bitrate: Int?,
    val codec: String?,
    /** Embedded cover (JPEG/PNG bytes), if any. */
    val cover: ByteArray?,
)

/**
 * Reads tags, duration and embedded cover with FFmpeg's demuxer only (no audio decoding), so a
 * scan stays cheap. Returns null when the file has no audio stream.
 */
object TagReader {

    fun read(file: Path): FileTags? {
        FfmpegRuntime.init()
        val format = AVFormatContext(null)
        if (avformat_open_input(format, BytePointer(file.toString(), "UTF-8"), null, null) < 0) return null
        try {
            var audio = (0 until format.nb_streams()).map { format.streams(it) }
                .firstOrNull { it.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO }
            var duration = format.duration()
            if (audio == null || duration == AV_NOPTS_VALUE || duration <= 0) {
                // Some containers only reveal streams/length after probing packets.
                if (avformat_find_stream_info(format, null as PointerPointer<*>?) < 0) return null
                audio = (0 until format.nb_streams()).map { format.streams(it) }
                    .firstOrNull { it.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO } ?: return null
                duration = format.duration()
            }
            val dictionaries = listOfNotNull(format.metadata(), audio.metadata())
            fun tag(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
                dictionaries.firstNotNullOfOrNull { dict -> value(dict, key) }
            }
            val cover = (0 until format.nb_streams()).map { format.streams(it) }
                .firstOrNull { (it.disposition() and AV_DISPOSITION_ATTACHED_PIC) != 0 && it.attached_pic().size() > 0 }
                ?.attached_pic()
                ?.let { pkt -> ByteArray(pkt.size()).also { pkt.data().capacity(pkt.size().toLong()).get(it) } }
            val codecName = org.bytedeco.ffmpeg.global.avcodec.avcodec_get_name(audio.codecpar().codec_id())?.string
            return FileTags(
                title = tag("title"),
                artist = tag("artist"),
                album = tag("album"),
                albumArtist = tag("album_artist", "albumartist", "album artist"),
                genre = tag("genre"),
                year = tag("date", "year", "originaldate")?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() },
                trackNumber = leadingNumber(tag("track", "tracknumber")),
                discNumber = leadingNumber(tag("disc", "discnumber")).coerceAtLeast(1),
                durationMs = if (duration != AV_NOPTS_VALUE && duration > 0) duration / 1000 else 0,
                bitrate = format.bit_rate().takeIf { it > 0 }?.toInt(),
                codec = codecName,
                cover = cover,
            )
        } finally {
            avformat_close_input(format)
        }
    }

    private fun value(dict: AVDictionary, key: String): String? =
        av_dict_get(dict, key, null, 0)?.value()?.getString("UTF-8")?.trim()?.takeIf { it.isNotEmpty() }

    /** "3/12" → 3. */
    private fun leadingNumber(raw: String?): Int = raw?.let { Regex("^\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
}
