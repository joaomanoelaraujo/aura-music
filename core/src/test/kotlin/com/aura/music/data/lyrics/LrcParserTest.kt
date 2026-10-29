package com.aura.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses common stamp formats`() {
        val lyrics = LrcParser.parse(
            """
            [00:01]one
            [00:02.5]two
            [00:03.25]three
            [00:04.125]four
            [00:05:50]five
            [1:00:00.00]six
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        assertEquals(listOf(1_000L, 2_500L, 3_250L, 4_125L, 5_500L, 3_600_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `repeated chorus with several stamps is expanded and sorted`() {
        val lyrics = LrcParser.parse("[00:10.00][00:30.00]chorus\n[00:20.00]verse")
        assertEquals(listOf("chorus", "verse", "chorus"), lyrics.lines.map { it.text })
    }

    @Test
    fun `duplicate stamps keep file order`() {
        val lyrics = LrcParser.parse("[00:10.00]first\n[00:10.00]second")
        assertEquals(listOf("first", "second"), lyrics.lines.map { it.text })
    }

    @Test
    fun `metadata tags are ignored and offset is read`() {
        val lyrics = LrcParser.parse("[ar:Artist]\n[ti:Title]\n[offset:+250]\n[length: 03:20]\n[00:01.00]hello")
        assertEquals(250L, lyrics.fileOffsetMs)
        assertEquals(listOf("hello"), lyrics.lines.map { it.text })
    }

    @Test
    fun `enhanced word stamps, BOM and CRLF are cleaned`() {
        val lyrics = LrcParser.parse("\uFEFF[00:01.00]<00:01.00>hel<00:01.50>lo\r\n[00:02.00]world\r")
        assertEquals(listOf("hello", "world"), lyrics.lines.map { it.text })
    }

    @Test
    fun `garbage lines inside synced lyrics are dropped and blank runs collapsed`() {
        val lyrics = LrcParser.parse("[00:01.00]a\nnot a lyric\n[00:02.00]\n[00:03.00]\n[00:04.00]b\n[xx:yy]bad")
        assertEquals(listOf("a", "", "b"), lyrics.lines.map { it.text })
    }

    @Test
    fun `text without stamps is unsynchronized`() {
        val lyrics = LrcParser.parse("\n\nLine one\nLine two\n\n\nLine three\n")
        assertFalse(lyrics.synced)
        assertEquals(listOf("Line one", "Line two", "", "Line three"), lyrics.lines.map { it.text })
    }

    @Test
    fun `empty or blank input yields empty lyrics`() {
        assertTrue(LrcParser.parse("").isEmpty)
        assertTrue(LrcParser.parse("[ar:x]\n\n").isEmpty)
    }

    @Test
    fun `active index follows the position, including after a seek backwards`() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c").lines
        assertEquals(-1, LrcParser.activeIndex(lines, 500))
        assertEquals(0, LrcParser.activeIndex(lines, 1_000))
        assertEquals(1, LrcParser.activeIndex(lines, 8_999))
        assertEquals(2, LrcParser.activeIndex(lines, 60_000))
        assertEquals(0, LrcParser.activeIndex(lines, 4_000))
    }

    @Test
    fun `stamps round-trip through formatting`() {
        val original = LrcParser.parse("[01:02.34]x\n[00:00.05]y")
        val again = LrcParser.parse(LrcParser.toText(original))
        assertEquals(original.lines, again.lines)
        assertEquals("[61:01.00]", LrcParser.formatStamp(3_661_000))
    }
}
