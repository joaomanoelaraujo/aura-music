package com.aura.desktop

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.sound.sampled.AudioSystem

/** Runs the installed-copy self test here: native FFmpeg plus this machine's real audio output (silence only). */
class SelfTestTest {
    @Test
    fun passesOnThisMachine() {
        assumeTrue("no audio output on this machine", AudioSystem.getMixerInfo().isNotEmpty())
        assertEquals(0, SelfTest.run())
    }
}
