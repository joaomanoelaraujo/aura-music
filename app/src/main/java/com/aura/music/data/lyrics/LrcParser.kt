package com.aura.music.data.lyrics

/** One lyric line. [timeMs] is null for unsynchronized lyrics. */
data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    /** Offset declared inside the file with the `[offset:±ms]` tag (positive = lyrics earlier). */
    val fileOffsetMs: Long = 0,
) {
    val isEmpty: Boolean get() = lines.none { it.text.isNotBlank() }

    companion object {
        val Empty = Lyrics(emptyList(), synced = false)
    }
}

/**
 * Tolerant parser for LRC and plain-text lyrics.
 *
 * Handles `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss:xx]` and `[h:mm:ss.xx]`
 * stamps, several stamps on one line (repeated choruses), the `[offset:]` tag, enhanced-LRC word
 * stamps (`<mm:ss.xx>`, stripped), metadata tags (`[ar:]`, `[ti:]`…, ignored), BOMs, CR/LF mixes,
 * blank lines (kept as instrumental breaks) and garbage lines. Lines with the same stamp keep
 * their file order. When no line carries a valid stamp, the text is treated as unsynchronized.
 */
object LrcParser {

    private const val BOM = "\uFEFF"

    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    // Only "[h:mm:ss.xx]" (with a dot) is read as hours; "[mm:ss:xx]" is the common centisecond form.
    private val hourStamp = Regex("""\[(\d{1,2}):(\d{1,2}):(\d{1,2})\.(\d{1,3})]""")
    private val leadingStamps = Regex("""^(\s*\[\d{1,3}:\d{1,2}(?::\d{1,2})?(?:[.:]\d{1,3})?])+""")
    private val metaTag = Regex("""^\s*\[([a-zA-Z#]+):(.*)]\s*$""")
    private val wordStamp = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(raw: String): Lyrics {
        val text = raw.removePrefix(BOM).replace("\r\n", "\n").replace('\r', '\n')
        val timed = ArrayList<Triple<Long, Int, String>>()
        val plain = ArrayList<String>()
        var offset = 0L
        var order = 0

        for (line in text.split('\n')) {
            val prefix = leadingStamps.find(line)
            if (prefix != null) {
                val stamps = parseStamps(prefix.value)
                val body = clean(line.substring(prefix.range.last + 1))
                stamps.forEach { timed += Triple(it, order++, body) }
                continue
            }
            val meta = metaTag.matchEntire(line)
            if (meta != null) {
                if (meta.groupValues[1].equals("offset", ignoreCase = true)) {
                    offset = meta.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: offset
                }
                continue
            }
            plain += clean(line)
        }

        if (timed.isNotEmpty()) {
            val lines = timed
                .sortedWith(compareBy<Triple<Long, Int, String>> { it.first }.thenBy { it.second })
                .map { LyricLine(it.first, it.third) }
            return Lyrics(collapseBlankRuns(lines), synced = true, fileOffsetMs = offset)
        }
        val lines = plain.dropWhile(String::isBlank).dropLastWhile(String::isBlank).map { LyricLine(null, it) }
        return Lyrics(collapseBlankRuns(lines), synced = false)
    }

    /** Index of the line sung at [positionMs] (already offset-adjusted), or -1 before the first line. */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val time = lines[mid].timeMs ?: return -1
            if (time <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /** Formats [ms] as an LRC stamp, e.g. `[01:02.34]`. */
    fun formatStamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        val minutes = safe / 60_000
        val seconds = (safe % 60_000) / 1000
        val hundredths = (safe % 1000) / 10
        return String.format(java.util.Locale.ROOT, "[%02d:%02d.%02d]", minutes, seconds, hundredths)
    }

    /** Serializes lines back to LRC (synced) or plain text. */
    fun toText(lyrics: Lyrics): String = lyrics.lines.joinToString("\n") { line ->
        if (line.timeMs != null) formatStamp(line.timeMs) + line.text else line.text
    }

    private fun parseStamps(prefix: String): List<Long> {
        val result = ArrayList<Long>(2)
        var rest = prefix
        while (rest.isNotBlank()) {
            rest = rest.trimStart()
            val hour = hourStamp.matchAt(rest, 0)
            if (hour != null) {
                val (h, m, s) = hour.destructured.let { Triple(it.component1(), it.component2(), it.component3()) }
                result += h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + fraction(hour.groupValues[4])
                rest = rest.substring(hour.value.length)
                continue
            }
            val match = stamp.matchAt(rest, 0) ?: break
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            if (seconds < 60) result += minutes * 60_000 + seconds * 1000 + fraction(match.groupValues[3])
            rest = rest.substring(match.value.length)
        }
        return result
    }

    /** ".5" = 500 ms, ".05" = 50 ms, ".005" = 5 ms. */
    private fun fraction(digits: String): Long = when (digits.length) {
        0 -> 0
        1 -> digits.toLong() * 100
        2 -> digits.toLong() * 10
        else -> digits.take(3).toLong()
    }

    private fun clean(text: String): String = text.replace(wordStamp, "").trim()

    /** Keeps single blank lines as breaks but never several in a row. */
    private fun collapseBlankRuns(lines: List<LyricLine>): List<LyricLine> {
        val out = ArrayList<LyricLine>(lines.size)
        for (line in lines) {
            if (line.text.isBlank() && out.lastOrNull()?.text?.isBlank() == true) continue
            out += line
        }
        return out
    }
}
