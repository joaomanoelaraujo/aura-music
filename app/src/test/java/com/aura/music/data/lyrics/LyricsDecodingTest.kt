package com.aura.music.data.lyrics

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsDecodingTest {

    private val sample = "[00:01.00]Coração, ação e emoção"

    @Test
    fun `decodes utf8 with and without bom`() {
        assertEquals(sample, TextDecoding.decode(sample.toByteArray(Charsets.UTF_8)))
        assertEquals(sample, TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `decodes utf16 with and without bom`() {
        assertEquals(sample, TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)))
        assertEquals(sample, TextDecoding.decode(sample.toByteArray(Charsets.UTF_16LE)))
        assertEquals(sample, TextDecoding.decode(sample.toByteArray(Charsets.UTF_16BE)))
    }

    @Test
    fun `falls back to windows-1252 for legacy files`() {
        assertEquals(sample, TextDecoding.decode(sample.toByteArray(Charset.forName("windows-1252"))))
    }

    @Test
    fun `decodes ID3 USLT frames`() {
        val text = "Primeira linha\nSegunda"
        val latin = byteArrayOf(0) + "por".toByteArray() + "desc".toByteArray() + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
        assertEquals(text, EmbeddedLyricsReader.decodeUslt(latin))
        val utf8 = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "Coração".toByteArray(Charsets.UTF_8)
        assertEquals("Coração", EmbeddedLyricsReader.decodeUslt(utf8))
        assertNull(EmbeddedLyricsReader.decodeUslt(byteArrayOf(9, 1, 2)))
    }

    @Test
    fun `decodes ID3 SYLT frames with millisecond stamps into LRC`() {
        fun stamp(ms: Int) = byteArrayOf((ms shr 24).toByte(), (ms shr 16).toByte(), (ms shr 8).toByte(), ms.toByte())
        val data = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(2, 1) + byteArrayOf(0) +
            "Olá".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + stamp(1_500) +
            "mundo".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + stamp(62_340)
        val lrc = EmbeddedLyricsReader.decodeSylt(data)!!
        val lines = LrcParser.parse(lrc).lines
        assertEquals(listOf(1_500L, 62_340L), lines.map { it.timeMs })
        assertEquals(listOf("Olá", "mundo"), lines.map { it.text })
    }
}
