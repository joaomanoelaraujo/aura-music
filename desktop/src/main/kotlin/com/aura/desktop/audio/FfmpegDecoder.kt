package com.aura.desktop.audio

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avformat_seek_file
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_CHANNEL_ORDER_UNSPEC
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_chlayout
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_int
import org.bytedeco.ffmpeg.global.avutil.av_opt_set_sample_fmt
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.ffmpeg.global.swresample.swr_alloc
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_out_samples
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.io.IOException

/**
 * Decodes one audio file with FFmpeg into interleaved signed 16-bit little-endian PCM, stereo, at
 * [outputRate]. Not thread-safe: one playback thread owns it.
 */
class FfmpegDecoder(path: String, val outputRate: Int = OUTPUT_RATE) : AutoCloseable {

    private val format = AVFormatContext(null)
    private var codec: AVCodecContext? = null
    private var resampler: SwrContext? = null
    private val packet: AVPacket
    private val frame: AVFrame
    private val streamIndex: Int
    private val timeBase: Double
    private var outBuffer = BytePointer(0L)
    private var outCapacitySamples = 0

    // swr_convert takes an array of plane pointers; packed stereo has one plane. Note that
    // PointerPointer(somePointer) would reinterpret the buffer itself as that array, so the array is
    // built explicitly.
    private val outPlanes = PointerPointer<BytePointer>(1L)
    private var endOfInput = false
    private var drained = false

    /** Output samples (per channel) to throw away after a seek, so playback starts exactly there. */
    private var dropSamples = 0L
    private var seekTargetMs = -1L

    /** Total length, or 0 when the file doesn't say. */
    val durationMs: Long

    init {
        FfmpegRuntime.init()
        check(avformat_open_input(format, BytePointer(path, "UTF-8"), null, null), "abrir o arquivo")
        try {
            check(avformat_find_stream_info(format, null as PointerPointer<*>?), "ler o arquivo")
            streamIndex = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0)
            if (streamIndex < 0) throw IOException("O arquivo não tem áudio")
            val stream = format.streams(streamIndex)
            timeBase = stream.time_base().num().toDouble() / stream.time_base().den()
            val decoder = avcodec_find_decoder(stream.codecpar().codec_id()) ?: throw IOException("Formato de áudio não suportado")
            val ctx = avcodec_alloc_context3(decoder)
            codec = ctx
            check(avcodec_parameters_to_context(ctx, stream.codecpar()), "preparar o decodificador")
            check(avcodec_open2(ctx, decoder, null as PointerPointer<*>?), "abrir o decodificador")
            durationMs = when {
                format.duration() != AV_NOPTS_VALUE && format.duration() > 0 -> format.duration() / 1000
                stream.duration() != AV_NOPTS_VALUE && stream.duration() > 0 -> (stream.duration() * timeBase * 1000).toLong()
                else -> 0
            }
            resampler = createResampler(ctx)
            packet = av_packet_alloc()
            frame = av_frame_alloc()
        } catch (e: Throwable) {
            codec?.let { avcodec_free_context(it) }
            resampler?.let { swr_free(it) }
            releaseFormat()
            throw e
        }
    }

    private fun createResampler(ctx: AVCodecContext): SwrContext {
        val swr = swr_alloc() ?: throw IOException("Sem memória para o áudio")
        val inLayout = AVChannelLayout()
        if (ctx.ch_layout().order() == AV_CHANNEL_ORDER_UNSPEC) {
            av_channel_layout_default(inLayout, ctx.ch_layout().nb_channels().coerceAtLeast(1))
        } else {
            org.bytedeco.ffmpeg.global.avutil.av_channel_layout_copy(inLayout, ctx.ch_layout())
        }
        val outLayout = AVChannelLayout().also { av_channel_layout_default(it, CHANNELS) }
        av_opt_set_chlayout(swr, "in_chlayout", inLayout, 0)
        av_opt_set_chlayout(swr, "out_chlayout", outLayout, 0)
        av_opt_set_int(swr, "in_sample_rate", ctx.sample_rate().toLong(), 0)
        av_opt_set_int(swr, "out_sample_rate", outputRate.toLong(), 0)
        av_opt_set_sample_fmt(swr, "in_sample_fmt", ctx.sample_fmt(), 0)
        av_opt_set_sample_fmt(swr, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0)
        av_channel_layout_uninit(inLayout)
        av_channel_layout_uninit(outLayout)
        check(swr_init(swr), "preparar a conversão de áudio")
        return swr
    }

    /**
     * Returns the next block of PCM, or null at the end of the file. A block may be empty while
     * samples are being skipped after a seek.
     */
    fun read(): ByteArray? {
        val ctx = codec ?: return null
        while (true) {
            if (drained) return null
            val received = avcodec_receive_frame(ctx, frame)
            if (received >= 0) {
                val pcm = convert(frame)
                av_frame_unref(frame)
                if (pcm.isNotEmpty()) return pcm
                continue
            }
            if (received == AVERROR_EOF) {
                drained = true
                return flushResampler().takeIf { it.isNotEmpty() }
            }
            // Needs more input.
            if (endOfInput) {
                avcodec_send_packet(ctx, null)
                continue
            }
            val read = av_read_frame(format, packet)
            if (read < 0) {
                endOfInput = true
                avcodec_send_packet(ctx, null)
                continue
            }
            if (packet.stream_index() == streamIndex) avcodec_send_packet(ctx, packet)
            av_packet_unref(packet)
        }
    }

    /** Moves to [positionMs]; the next [read] starts there, to the sample. */
    fun seek(positionMs: Long) {
        val ctx = codec ?: return
        val target = positionMs.coerceAtLeast(0)
        val ts = target * 1000 // AV_TIME_BASE units (microseconds)
        avformat_seek_file(format, -1, Long.MIN_VALUE, ts, ts, 0)
        avcodec_flush_buffers(ctx)
        resampler?.let { swr ->
            // Discard whatever the resampler still holds from before the seek.
            while (swr_convert(swr, planes(ensureOut(1024)), outCapacitySamples, null as PointerPointer<*>?, 0) > 0) Unit
        }
        endOfInput = false
        drained = false
        seekTargetMs = target
        dropSamples = 0
    }

    private fun convert(frame: AVFrame): ByteArray {
        val swr = resampler ?: return ByteArray(0)
        if (seekTargetMs >= 0) {
            // First frame after a seek: skip the part before the target.
            val pts = frame.best_effort_timestamp()
            if (pts != AV_NOPTS_VALUE) {
                val frameStartMs = (pts * timeBase * 1000).toLong()
                dropSamples = ((seekTargetMs - frameStartMs).coerceAtLeast(0) * outputRate / 1000)
            }
            seekTargetMs = -1
        }
        val maxOut = swr_get_out_samples(swr, frame.nb_samples()).coerceAtLeast(frame.nb_samples())
        val out = ensureOut(maxOut)
        val produced = swr_convert(swr, planes(out), maxOut, frame.extended_data(), frame.nb_samples())
        if (produced <= 0) return ByteArray(0)
        return takeOutput(out, produced)
    }

    private fun flushResampler(): ByteArray {
        val swr = resampler ?: return ByteArray(0)
        val out = ensureOut(4096)
        val produced = swr_convert(swr, planes(out), outCapacitySamples, null as PointerPointer<*>?, 0)
        return if (produced > 0) takeOutput(out, produced) else ByteArray(0)
    }

    private fun takeOutput(out: BytePointer, produced: Int): ByteArray {
        var skip = 0
        if (dropSamples > 0) {
            skip = minOf(dropSamples, produced.toLong()).toInt()
            dropSamples -= skip
        }
        val samples = produced - skip
        if (samples <= 0) return ByteArray(0)
        val bytes = ByteArray(samples * FRAME_BYTES)
        out.position((skip * FRAME_BYTES).toLong()).get(bytes)
        out.position(0)
        return bytes
    }

    private fun planes(out: BytePointer): PointerPointer<BytePointer> = outPlanes.put(0L, out)

    private fun ensureOut(samples: Int): BytePointer {
        if (samples > outCapacitySamples) {
            outBuffer.close()
            outCapacitySamples = samples
            outBuffer = BytePointer((samples * FRAME_BYTES).toLong())
        }
        return outBuffer
    }

    private fun check(result: Int, action: String) {
        if (result < 0) throw IOException("Não foi possível $action (${describe(result)})")
    }

    private fun releaseFormat() {
        avformat_close_input(format)
    }

    override fun close() {
        av_frame_free(frame)
        av_packet_free(packet)
        codec?.let { avcodec_free_context(it) }
        codec = null
        resampler?.let { swr_free(it) }
        resampler = null
        releaseFormat()
        outBuffer.close()
        outPlanes.close()
    }

    companion object {
        const val OUTPUT_RATE = 48_000
        const val CHANNELS = 2
        const val FRAME_BYTES = 2 * CHANNELS

        fun describe(error: Int): String {
            val buffer = ByteArray(128)
            av_strerror(error, buffer, buffer.size.toLong())
            return String(buffer).substringBefore('\u0000')
        }
    }
}
