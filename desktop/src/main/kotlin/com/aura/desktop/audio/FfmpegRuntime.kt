package com.aura.desktop.audio

import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level

/** Loads FFmpeg's native libraries once and keeps its console logging quiet. */
object FfmpegRuntime {
    private val ready by lazy { av_log_set_level(AV_LOG_QUIET) }

    fun init() = ready
}
