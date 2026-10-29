package com.aura.music.data.media

/**
 * Recognises titles that aren't song names but a timestamp or a set of hashtags, typical of
 * statuses and clips saved from messengers: "#fry #algo", "08. 19. 12", "23:21",
 * "21:33 #whatsappstatus". A title counts only when nothing else is left once dates, times,
 * hashtags, plain numbers and separators are removed, so "Song #1" or "22 Acacia Avenue" stay.
 */
object TitlePatterns {

    fun isTimestampOrHashtags(title: String): Boolean {
        val text = title.trim()
        if (text.isEmpty()) return false
        // At least one real marker; a title made only of digits ("01") is just untitled.
        if (!HASHTAG.containsMatchIn(text) && !DATE.containsMatchIn(text) && !TIME.containsMatchIn(text)) return false
        val rest = text
            .replace(HASHTAG, " ")
            .replace(DATE, " ")
            .replace(TIME, " ")
            .replace(NUMBER, " ")
            .replace(FILLER, " ")
        return rest.none { it.isLetterOrDigit() }
    }

    private val HASHTAG = Regex("""#[\p{L}\p{N}_]+""")

    /** 08. 19. 12 · 19/08/2012 · 2024-05-01 · 20240501 (with a separator or as 8 digits). */
    private val DATE = Regex("""\b\d{1,4}\s*[./-]\s*\d{1,2}\s*[./-]\s*\d{1,4}\b|\b(19|20)\d{6}\b""")

    /** 23:21 · 21:33:05 · 7h30. */
    private val TIME = Regex("""\b\d{1,2}\s*:\s*\d{2}(\s*:\s*\d{2})?\b|\b\d{1,2}h\d{2}\b""", RegexOption.IGNORE_CASE)

    private val NUMBER = Regex("""\d+""")

    /** Separators and the words that usually come with them in saved clips' names. */
    private val FILLER = Regex("""[\s\p{P}\p{S}]+|\b(?i:audio|áudio|status|video|vídeo|wa|aud|ptt)\b""")
}
