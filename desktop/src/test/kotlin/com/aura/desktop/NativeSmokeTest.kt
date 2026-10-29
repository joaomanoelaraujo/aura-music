package com.aura.desktop

import org.bytedeco.ffmpeg.global.avformat
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSmokeTest {
    @Test
    fun ffmpegLoads() {
        assertTrue(avformat.avformat_version() > 0)
    }
}
