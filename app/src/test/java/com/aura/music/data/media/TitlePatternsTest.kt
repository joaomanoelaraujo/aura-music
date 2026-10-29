package com.aura.music.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlePatternsTest {

    @Test
    fun `dates, times and hashtags are recognised, alone or together`() {
        listOf(
            "#fry #algo",
            "#whatsappstatus",
            "08. 19. 12",
            "08.19.12",
            "19/08/2012",
            "2024-05-01",
            "20240501",
            "23:21",
            "21:33:05",
            "7h30",
            "21:33 #whatsappstatus",
            "08. 19. 12 23:21",
            "#status 08.19.12 - 23:21",
            "audio 23:21",
            "Status 21:33 #fyp",
        ).forEach { assertTrue("'$it' should be recognised", TitlePatterns.isTimestampOrHashtags(it)) }
    }

    @Test
    fun `real song names stay, even with numbers or a hashtag inside`() {
        listOf(
            "Song #1",
            "22 Acacia Avenue",
            "Águas de Março",
            "1999",
            "01",
            "Track 03",
            "7 Rings",
            "Love Story (Taylor's Version)",
            "Mambo No. 5",
            "Noite 23:21 de verão",
            "",
        ).forEach { assertFalse("'$it' should stay", TitlePatterns.isTimestampOrHashtags(it)) }
    }
}
