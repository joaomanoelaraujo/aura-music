package com.aura.music.data.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes lyric files whose encoding is unknown: BOM-marked UTF-8/UTF-16 first, then strict
 * UTF-8, then UTF-16 without BOM (detected by its zero bytes), and finally Windows-1252, the
 * usual encoding of older LRC files in Portuguese and other Western languages.
 */
object TextDecoding {

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        strictUtf8(bytes)?.let { return it }
        utf16WithoutBom(bytes)?.let { return it }
        return String(bytes, Charset.forName("windows-1252"))
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** ASCII-heavy UTF-16 text has a zero in every other byte. */
    private fun utf16WithoutBom(bytes: ByteArray): String? {
        if (bytes.size < 4 || bytes.size % 2 != 0) return null
        val sample = minOf(bytes.size, 400)
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until sample) if (bytes[i] == 0.toByte()) if (i % 2 == 0) evenZeros++ else oddZeros++
        val half = sample / 2
        return when {
            oddZeros > half * 0.6 && evenZeros < half * 0.1 -> String(bytes, Charsets.UTF_16LE)
            evenZeros > half * 0.6 && oddZeros < half * 0.1 -> String(bytes, Charsets.UTF_16BE)
            else -> null
        }
    }
}
