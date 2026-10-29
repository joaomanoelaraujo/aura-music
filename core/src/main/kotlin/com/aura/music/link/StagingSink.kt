package com.aura.music.link

import java.io.File
import java.io.FileOutputStream

/**
 * Receives into `<staging>/<transfer>-<n>.part` files the app owns. A file is handed to [publish]
 * only after its SHA-256 matches, so the library never sees a partial or corrupted copy; a dropped
 * transfer resumes from the partial file's length.
 *
 * [publish] moves/copies the verified file to its final place and returns an error text or null.
 */
class StagingSink(
    private val staging: File,
    private val offer: TransferOffer,
    private val publish: (index: Int, verified: File) -> String?,
) : TransferSink {

    init {
        staging.mkdirs()
    }

    fun part(index: Int) = File(staging, "${offer.id}-$index.part")

    override fun received(index: Int): Long = part(index).takeIf(File::isFile)?.length() ?: 0

    override fun append(index: Int, bytes: ByteArray, length: Int) {
        FileOutputStream(part(index), true).use { it.write(bytes, 0, length) }
    }

    override fun complete(index: Int, expectedSha256: String): String? {
        val file = part(index)
        val actual = file.inputStream().use(TransferChannel::sha256)
        if (actual != expectedSha256) return "O arquivo chegou diferente do enviado (verificação falhou)"
        return publish(index, file)
    }

    override fun discard(index: Int) {
        part(index).delete()
    }

    /** Removes every partial file of this transfer (cancelled, or finished). */
    fun cleanup() {
        offer.files.indices.forEach { part(it).delete() }
    }
}
