package com.aura.desktop

import com.aura.desktop.audio.FfmpegDecoder
import com.aura.desktop.audio.FfmpegRuntime
import com.aura.desktop.audio.JavaSoundSink
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat

/**
 * `AURA --self-test`: checks an installed copy without opening a window: FFmpeg's native libraries
 * load, the needed decoders exist, and the default audio output opens (it only receives silence).
 */
object SelfTest {
    fun run(): Int {
        var ok = true
        fun check(name: String, block: () -> String) {
            val result = runCatching(block)
            ok = ok && result.isSuccess
            println((if (result.isSuccess) "OK    " else "FALHA ") + name + ": " + (result.getOrNull() ?: result.exceptionOrNull()?.message))
        }
        check("FFmpeg") {
            FfmpegRuntime.init()
            val v = avformat.avformat_version()
            "libavformat ${v shr 16}.${(v shr 8) and 0xFF}.${v and 0xFF}"
        }
        check("Decodificadores") {
            val names = listOf("mp3", "flac", "aac", "vorbis", "opus", "alac", "pcm_s16le", "wmav2")
            val missing = names.filter { avcodec.avcodec_find_decoder_by_name(it) == null }
            require(missing.isEmpty()) { "faltando: $missing" }
            names.joinToString()
        }
        check("Saída de áudio") {
            val sink = JavaSoundSink()
            sink.open()
            val silence = ByteArray(FfmpegDecoder.OUTPUT_RATE / 10 * FfmpegDecoder.FRAME_BYTES)
            sink.start()
            sink.write(silence, 0, silence.size)
            sink.stop()
            sink.flush()
            sink.close()
            "${FfmpegDecoder.OUTPUT_RATE} Hz estéreo, 16 bits"
        }
        return if (ok) 0 else 1
    }
}
