package com.aura.music.link

import com.aura.music.link.crypto.LinkSecurityException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * File transfer between linked devices: an authorised copy goes into the receiver's own library.
 * This is not streaming (nothing plays from the other device) and not remote control.
 *
 * The offer travels on the control link; the bytes travel on a separate authenticated connection,
 * so big files never delay commands. The receiver resumes interrupted files from what it already
 * has, checks the SHA-256 the sender declared and only then publishes the file.
 */
object TransferLimits {
    const val MAX_FILES = 500
    const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
    const val CHUNK_BYTES = 128 * 1024
}

@Serializable
enum class TransferKind { Audio, Lyrics }

/** One file in an offer. [sha256] is hex, computed by the sender over the whole file. */
@Serializable
data class TransferFile(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val kind: TransferKind = TransferKind.Audio,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
)

@Serializable
data class TransferOffer(val id: String, val files: List<TransferFile>) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** Rejects offers no honest sender would make. */
    fun validate() {
        require(id.matches(Regex("^[A-Za-z0-9-]{8,64}$"))) { "bad id" }
        require(files.size in 1..TransferLimits.MAX_FILES) { "bad file count" }
        files.forEach { f ->
            require(f.sizeBytes in 0..TransferLimits.MAX_FILE_BYTES) { "bad size" }
            require(f.sha256.matches(Regex("^[0-9a-f]{64}$"))) { "bad hash" }
            require(FileNames.sanitize(f.name).isNotEmpty()) { "bad name" }
        }
    }
}

@Serializable
enum class TransferRefusal { Declined, NotAllowed, NoSpace, TooLarge, Invalid, Busy }

/** What a data connection is for, sent as the first encrypted frame after the handshake. */
@Serializable
sealed interface ChannelHello {
    @Serializable @SerialName("control") data object Control : ChannelHello

    /** [senderIsDialer]: true when the device that dialed is the one sending the files. */
    @Serializable @SerialName("data") data class Data(val transferId: String, val senderIsDialer: Boolean) : ChannelHello
}

object Channels {
    fun announce(link: AuthenticatedLink, hello: ChannelHello) =
        link.stream.write(link.cipher.seal(LinkProtocol.json.encodeToString(ChannelHello.serializer(), hello).toByteArray()))

    fun read(link: AuthenticatedLink): ChannelHello = try {
        LinkProtocol.json.decodeFromString(ChannelHello.serializer(), String(link.cipher.open(link.stream.read()), Charsets.UTF_8))
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw LinkSecurityException("Bad channel hello")
    }
}

/** Safe file names: no paths, no reserved characters or names, bounded length. */
object FileNames {
    private val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun sanitize(name: String): String {
        val base = name.replace('\\', '/').substringAfterLast('/')
            .filterNot { it < ' ' || it in "<>:\"|?*" }
            .trim().trimEnd('.', ' ')
            .take(150)
        if (base.isEmpty() || base == "." || base == "..") return ""
        val stem = base.substringBefore('.')
        return if (stem.uppercase() in reserved) "_$base" else base
    }

    /** "song.mp3" → "song (2).mp3", for when the name is taken. */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }
}

/** Where a sender reads files from. */
fun interface TransferSource {
    fun open(index: Int, offset: Long): InputStream
}

/** Where a receiver writes. Implementations keep partial data so a dropped transfer can resume. */
interface TransferSink {
    /** Bytes already received for [index] (0 when starting). */
    fun received(index: Int): Long

    fun append(index: Int, bytes: ByteArray, length: Int)

    /** Whole file received: verify [expectedSha256] and publish it. Returns an error text or null. */
    fun complete(index: Int, expectedSha256: String): String?

    /** Drops partial data for [index] (after a hash mismatch). */
    fun discard(index: Int)
}

data class TransferProgress(val fileIndex: Int, val fileBytes: Long, val totalBytes: Long, val doneBytes: Long)

class TransferAbortedException(val reason: String) : IOException(reason)

/**
 * The bytes of one transfer over an authenticated connection. Frames: `[type][payload]`, sealed.
 * The receiver drives: for each file it says where to resume; the sender streams the rest and marks
 * the end; the receiver verifies and answers. Either side can abort.
 */
object TransferChannel {
    private const val RESUME: Byte = 1
    private const val CHUNK: Byte = 2
    private const val FILE_END: Byte = 3
    private const val FILE_DONE: Byte = 4
    private const val ABORT: Byte = 5

    fun send(link: AuthenticatedLink, offer: TransferOffer, source: TransferSource, onProgress: (TransferProgress) -> Unit, isCancelled: () -> Boolean) {
        var done = 0L
        for ((index, file) in offer.files.withIndex()) {
            val (type, payload) = read(link)
            if (type == ABORT) throw TransferAbortedException(String(payload))
            if (type != RESUME) throw LinkSecurityException("Expected resume")
            val buffer = ByteBuffer.wrap(payload)
            val resumeIndex = buffer.int
            val offset = buffer.long
            if (resumeIndex != index || offset !in 0..file.sizeBytes) throw LinkSecurityException("Bad resume")
            done += offset
            source.open(index, offset).use { input ->
                val chunk = ByteArray(TransferLimits.CHUNK_BYTES)
                var sent = offset
                while (sent < file.sizeBytes) {
                    if (isCancelled()) {
                        write(link, ABORT, "cancelled".toByteArray())
                        throw TransferAbortedException("cancelled")
                    }
                    val n = input.read(chunk, 0, minOf(chunk.size.toLong(), file.sizeBytes - sent).toInt())
                    if (n < 0) throw IOException("File shorter than declared")
                    write(link, CHUNK, chunk.copyOf(n))
                    sent += n
                    done += n
                    onProgress(TransferProgress(index, sent, offer.totalBytes, done))
                }
            }
            write(link, FILE_END, ByteBuffer.allocate(4).putInt(index).array())
            val (answer, result) = read(link)
            if (answer == ABORT) throw TransferAbortedException(String(result))
            if (answer != FILE_DONE) throw LinkSecurityException("Expected done")
            val error = String(result, Charsets.UTF_8)
            if (error.isNotEmpty()) throw TransferAbortedException(error)
        }
    }

    fun receive(link: AuthenticatedLink, offer: TransferOffer, sink: TransferSink, onProgress: (TransferProgress) -> Unit, isCancelled: () -> Boolean) {
        var done = 0L
        for ((index, file) in offer.files.withIndex()) {
            var have = sink.received(index).coerceIn(0, file.sizeBytes)
            done += have
            write(link, RESUME, ByteBuffer.allocate(12).putInt(index).putLong(have).array())
            while (true) {
                if (isCancelled()) {
                    write(link, ABORT, "cancelled".toByteArray())
                    throw TransferAbortedException("cancelled")
                }
                val (type, payload) = read(link)
                when (type) {
                    CHUNK -> {
                        if (have + payload.size > file.sizeBytes) throw LinkSecurityException("More data than declared")
                        sink.append(index, payload, payload.size)
                        have += payload.size
                        done += payload.size
                        onProgress(TransferProgress(index, have, offer.totalBytes, done))
                    }
                    FILE_END -> {
                        if (have != file.sizeBytes) throw LinkSecurityException("File ended early")
                        val error = sink.complete(index, file.sha256)
                        write(link, FILE_DONE, error.orEmpty().toByteArray(Charsets.UTF_8))
                        if (error != null) {
                            sink.discard(index)
                            throw TransferAbortedException(error)
                        }
                        break
                    }
                    ABORT -> throw TransferAbortedException(String(payload))
                    else -> throw LinkSecurityException("Unexpected frame")
                }
            }
        }
    }

    private fun write(link: AuthenticatedLink, type: Byte, payload: ByteArray) =
        link.stream.write(link.cipher.seal(byteArrayOf(type) + payload))

    private fun read(link: AuthenticatedLink): Pair<Byte, ByteArray> {
        val plain = link.cipher.open(link.stream.read())
        if (plain.isEmpty()) throw LinkSecurityException("Empty frame")
        return plain[0] to plain.copyOfRange(1, plain.size)
    }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
