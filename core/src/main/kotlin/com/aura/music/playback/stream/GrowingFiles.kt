package com.aura.music.playback.stream

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Songs still arriving from another phone (AURA Together), so they can play while they download.
 * The writer appends to `<file>.part` and reports progress here; readers wait for the bytes they
 * need. Keyed by the final file path, which the part file is renamed to once complete.
 */
object GrowingFiles {

    private class Entry {
        var total = -1L
        var received = 0L
        var done = false
        var failed = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val lock = Object()

    fun partOf(file: File) = File(file.path + ".part")

    /** Size of the whole song, as soon as the sender tells it. */
    fun expect(file: File, totalBytes: Long) = update(file) { total = totalBytes }

    fun received(file: File, bytes: Long) = update(file) { received = bytes }

    fun complete(file: File) = update(file) { done = true }

    /** The transfer stopped for good: waiting readers fail instead of hanging. */
    fun fail(file: File) = update(file) { failed = true }

    /** Forgets (and fails) every song under [dir], e.g. when the jam ends. */
    fun forgetUnder(dir: File) = synchronized(lock) {
        val prefix = dir.path + File.separator
        entries.entries.removeIf { (path, entry) ->
            path.startsWith(prefix).also { if (it) entry.failed = true }
        }
        lock.notifyAll()
    }

    /** True once [file] is fully there (not being received any more). */
    fun isComplete(file: File): Boolean = file.isFile && entries[file.path]?.done != false

    /** True while [file] is being received and its size is known: it can be opened already. */
    fun isPlayable(file: File): Boolean {
        if (file.isFile) return true
        val entry = entries[file.path] ?: return false
        return synchronized(lock) { !entry.failed && entry.total > 0 }
    }

    /** The song's full size, waiting up to [timeoutMs] for the sender to say it. */
    fun awaitTotal(file: File, timeoutMs: Long): Long {
        if (file.isFile && entries[file.path]?.done != false) return file.length()
        return await(file, timeoutMs) { if (it.total > 0) it.total else null }
    }

    /** Waits until the byte at [offset] has arrived; returns how many bytes from there are available. */
    fun awaitAvailable(file: File, offset: Long, timeoutMs: Long): Long {
        if (file.isFile && entries[file.path]?.done != false) return (file.length() - offset).coerceAtLeast(0)
        return await(file, timeoutMs) { entry ->
            when {
                entry.received > offset -> entry.received - offset
                entry.done -> 0L
                else -> null
            }
        }
    }

    /**
     * Opens what there is to read: the finished file, or the part still growing. The part can be
     * renamed between the check and the open, so a miss falls back to the finished name.
     */
    fun open(file: File): RandomAccessFile {
        if (file.isFile) return RandomAccessFile(file, "r")
        return try {
            RandomAccessFile(partOf(file), "r")
        } catch (_: FileNotFoundException) {
            RandomAccessFile(file, "r")
        }
    }

    private inline fun update(file: File, change: Entry.() -> Unit) = synchronized(lock) {
        entries.getOrPut(file.path, ::Entry).change()
        lock.notifyAll()
    }

    private fun <T : Any> await(file: File, timeoutMs: Long, ready: (Entry) -> T?): T = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastReceived = -1L
        var stalledSince = System.currentTimeMillis()
        while (true) {
            val entry = entries[file.path] ?: throw IOException("Not being received: ${file.name}")
            if (entry.failed) throw IOException("Transfer failed: ${file.name}")
            ready(entry)?.let { return it }
            // The timeout counts from the last progress, not from the start of a long wait.
            val now = System.currentTimeMillis()
            if (entry.received != lastReceived) {
                lastReceived = entry.received
                stalledSince = now
            }
            val remaining = maxOf(deadline, stalledSince + timeoutMs) - now
            if (remaining <= 0) throw IOException("Transfer stalled: ${file.name}")
            try {
                lock.wait(minOf(remaining, 500))
            } catch (e: InterruptedException) {
                // The player cancels loads by interrupting the loading thread.
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Cancelled")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
