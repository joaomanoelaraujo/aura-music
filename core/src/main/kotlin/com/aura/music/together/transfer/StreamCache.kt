package com.aura.music.together.transfer

import com.aura.music.playback.stream.GrowingFiles
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.protocol.Wire
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Temporary copies of songs received from another phone in the jam, with their covers. They live
 * only in the app's cache and are deleted when the session ends; they never join the library.
 */
class StreamCache(val dir: File) {

    /** The finished copy of [ref], or null while it hasn't been fully received. */
    fun complete(ref: TrackRef): File? = file(ref).takeIf { it.exists() }

    /** A copy of [ref] the player can open already: finished, or arriving with a known size. */
    fun playable(ref: TrackRef): File? = file(ref).takeIf(GrowingFiles::isPlayable)

    /** The sender told the full size: the song can start playing as it arrives. */
    fun expect(ref: TrackRef, totalBytes: Long) = GrowingFiles.expect(file(ref), totalBytes)

    fun failed(ref: TrackRef) = GrowingFiles.fail(file(ref))

    fun receivedBytes(ref: TrackRef): Long = part(ref).length()

    /** Appends bytes at [offset]; false when they don't continue the partial file (stale or duplicate). */
    fun append(ref: TrackRef, offset: Long, data: ByteArray): Boolean {
        val part = part(ref)
        if (offset != part.length()) return false
        dir.mkdirs()
        FileOutputStream(part, true).use { it.write(data) }
        GrowingFiles.received(file(ref), part.length())
        return true
    }

    fun finish(ref: TrackRef): File {
        val target = file(ref)
        // Some systems refuse to rename a file another thread is reading; those reads are brief.
        repeat(RENAME_ATTEMPTS) {
            if (part(ref).renameTo(target)) return target.also(GrowingFiles::complete)
            Thread.sleep(RENAME_RETRY_MS)
        }
        part(ref).copyTo(target, overwrite = true)
        part(ref).delete()
        GrowingFiles.complete(target)
        return target
    }

    fun hasArt(ref: TrackRef) = artOf(file(ref)).exists()

    fun saveArt(ref: TrackRef, jpeg: ByteArray) {
        dir.mkdirs()
        artOf(file(ref)).writeBytes(jpeg)
    }

    fun clear() {
        GrowingFiles.forgetUnder(dir)
        dir.deleteRecursively()
    }

    private fun file(ref: TrackRef) = File(dir, name(ref))

    private fun part(ref: TrackRef) = GrowingFiles.partOf(file(ref))

    companion object {
        private const val RENAME_ATTEMPTS = 40
        private const val RENAME_RETRY_MS = 25L

        /** Stable, path-safe name for a song: hex only, so the playback service can trust it. */
        fun name(ref: TrackRef): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(Wire.json.encodeToString(TrackRef.serializer(), ref).toByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }

        /** The cover saved next to a received song (it may not exist). */
        fun artOf(song: File) = File(song.path + ".jpg")
    }
}
