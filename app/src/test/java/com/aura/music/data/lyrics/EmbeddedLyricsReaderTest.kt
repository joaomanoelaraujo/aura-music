package com.aura.music.data.lyrics

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedLyricsReaderTest {

    @Test
    fun `reads lyrics stored as TXXX, USLT text frames and Vorbis comments`() {
        assertEquals("[00:01.00]a", EmbeddedLyricsReader.read(Metadata(TextInformationFrame("TXXX", "lyrics", listOf("[00:01.00]a")))))
        assertEquals("ff", EmbeddedLyricsReader.read(Metadata(TextInformationFrame("TXXX", "USLT", listOf("ff")))))
        assertEquals("b", EmbeddedLyricsReader.read(Metadata(TextInformationFrame("USLT", null, listOf("b")))))
        assertEquals("c", EmbeddedLyricsReader.read(Metadata(VorbisComment("UNSYNCEDLYRICS", "c"))))
    }

    @Test
    fun `ignores unrelated tags`() {
        assertNull(EmbeddedLyricsReader.read(Metadata(TextInformationFrame("TXXX", "comment", listOf("x")), VorbisComment("ARTIST", "y"))))
    }
}
