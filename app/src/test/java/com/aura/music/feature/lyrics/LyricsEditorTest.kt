package com.aura.music.feature.lyrics

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsEditorTest {

    @Test
    fun `stamping adds a time to the cursor line and moves to the next line`() {
        val start = TextFieldValue("first\nsecond", TextRange(0))
        val once = stampCurrentLine(start, 1_250)
        assertEquals("[00:01.25]first\nsecond", once.text)
        val twice = stampCurrentLine(once, 4_000)
        assertEquals("[00:01.25]first\n[00:04.00]second", twice.text)
    }

    @Test
    fun `stamping replaces an existing time`() {
        val value = TextFieldValue("[00:09.00][00:20.00]chorus", TextRange(5))
        assertEquals("[00:10.50]chorus", stampCurrentLine(value, 10_500).text)
    }

    @Test
    fun `offset is shown with sign and tenths`() {
        assertEquals("+0,3", formatOffset(300))
        assertEquals("−1,2", formatOffset(-1_200))
        assertEquals("0,0", formatOffset(0))
    }
}
