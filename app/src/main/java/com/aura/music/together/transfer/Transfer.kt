package com.aura.music.together.transfer

import com.aura.music.playback.stream.GrowingFiles
import com.aura.music.together.protocol.Message
import com.aura.music.together.protocol.TrackRef
import com.aura.music.together.transport.SecureLink
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Random access to a song file: [read] returns up to `length` bytes at `offset` (empty at the end). */
class TrackFile(val sizeBytes: Long, val read: (offset: Long, length: Int) -> ByteArray) {
    companion object {
        /**
         * A received song, possibly still arriving: reads wait for the bytes, so the host can pass a
         * guest's song on to the others while it is still coming in.
         */
        fun of(file: File): TrackFile? {
            val total = runCatching { GrowingFiles.awaitTotal(file, 0) }.getOrNull() ?: return null
            return TrackFile(total) { offset, length ->
                val available = runCatching { GrowingFiles.awaitAvailable(file, offset, WAIT_MS) }.getOrDefault(0L)
                if (available <= 0) return@TrackFile ByteArray(0)
                GrowingFiles.open(file).use { raf ->
                    raf.seek(offset)
                    val buffer = ByteArray(minOf(length.toLong(), available).toInt())
                    val n = raf.read(buffer).coerceAtLeast(0)
                    buffer.copyOf(n)
                }
            }
        }

        private const val WAIT_MS = 30_000L
    }
}

/** What a phone can hand to the others: songs (by range) and their covers. */
interface SongSource {
    suspend fun open(ref: TrackRef): TrackFile?

    /** The cover as a JPEG of at most [Transfers.MAX_ART_BYTES], or null when there is none. */
    suspend fun artwork(ref: TrackRef): ByteArray?
}

object Transfers {
    /** Fits a relay frame (64 KiB) with room for the header, encryption and envelope. */
    const val CHUNK_BYTES = 48 * 1024
    const val MAX_ART_BYTES = 46 * 1024
    const val MAX_FETCH_BYTES = 512 * 1024
    const val WINDOW_BYTES = 256 * 1024
    const val WINDOWS = 2
    const val MAX_SONG_BYTES = 400L * 1024 * 1024

    /** Answers a [Message.Fetch] from [file]; null means the song isn't offered or can't be read. */
    suspend fun serve(link: SecureLink, fetch: Message.Fetch, file: TrackFile?, active: () -> Boolean = { true }) {
        if (file == null || fetch.offset < 0 || fetch.offset >= file.sizeBytes) return link.send(Message.Unavailable(fetch.track))
        val end = minOf(file.sizeBytes, fetch.offset + fetch.length.coerceIn(0, MAX_FETCH_BYTES))
        var offset = fetch.offset
        while (offset < end && active()) {
            val data = read(file, offset, minOf(CHUNK_BYTES.toLong(), end - offset).toInt())
            if (data.isEmpty()) break
            link.send(Message.Chunk(fetch.track, offset, file.sizeBytes, data))
            offset += data.size
        }
        if (offset == fetch.offset) link.send(Message.Unavailable(fetch.track))
    }

    /**
     * Reads with a few quick retries: a song that just finished arriving is briefly locked while it
     * gets its final name on some systems, and that must not look like "unavailable" to the asker.
     * Whatever happens, the asker gets an answer: a request left unanswered would stall it.
     */
    private suspend fun read(file: TrackFile, offset: Long, length: Int): ByteArray {
        repeat(READ_ATTEMPTS) { attempt ->
            runCatching { file.read(offset, length) }.onSuccess { return it }
            if (attempt < READ_ATTEMPTS - 1) delay(READ_RETRY_MS)
        }
        return ByteArray(0)
    }

    private const val READ_ATTEMPTS = 20
    private const val READ_RETRY_MS = 50L

    suspend fun serveArt(link: SecureLink, request: Message.FetchArt, jpeg: ByteArray?) {
        link.send(Message.Art(request.track, jpeg?.takeIf { it.size <= MAX_ART_BYTES } ?: ByteArray(0)))
    }
}

/**
 * Answers one peer's song and cover requests one at a time, in the order they came. Order matters:
 * a receiver appends chunks to the end of its file, so a later window served first would be lost.
 */
class TransferQueue(
    scope: CoroutineScope,
    private val link: SecureLink,
    private val song: suspend (TrackRef) -> TrackFile?,
    private val cover: suspend (TrackRef) -> ByteArray?,
) {
    private val requests = Channel<Message>(Channel.UNLIMITED)
    private val worker = scope.launch {
        for (request in requests) {
            runCatching {
                when (request) {
                    is Message.Fetch -> Transfers.serve(link, request, runCatching { song(request.track) }.getOrNull())
                    is Message.FetchArt -> Transfers.serveArt(link, request, runCatching { cover(request.track) }.getOrNull())
                    else -> Unit
                }
            }
        }
    }

    fun offer(request: Message) {
        requests.trySend(request)
    }

    fun close() {
        requests.close()
        worker.cancel()
    }
}

/**
 * Receives one song at a time into a [StreamCache]: its cover first, then the audio, keeping up to
 * [Transfers.WINDOWS] requests in flight. Not thread-safe: use from one coroutine or under a lock.
 */
class TrackReceiver(private val cache: StreamCache) {

    sealed interface Event {
        /** The size is known and the first bytes are in: the song can start playing already. */
        data class Started(val ref: TrackRef, val file: File) : Event
        data class Progress(val ref: TrackRef, val fraction: Float) : Event
        data class Complete(val ref: TrackRef, val file: File) : Event
        data class Failed(val ref: TrackRef) : Event
    }

    var target: TrackRef? = null
        private set
    private var total = -1L
    private val pendingEnds = ArrayDeque<Long>()
    private var artRequested = false

    /** Where a gap was last detected, so it is asked for again only once. */
    private var recoveringFrom = -1L

    /** Switches to receiving [ref] (null = nothing); what already arrived of a song is kept. */
    fun receive(ref: TrackRef?) {
        if (ref == target) return
        reset()
        target = ref
    }

    /** Requests sent on a dropped connection are lost: they're asked again on the next [pump]. */
    fun connectionLost() {
        pendingEnds.clear()
        artRequested = false
    }

    suspend fun pump(send: suspend (Message) -> Unit) {
        val ref = target ?: return
        // The cover comes after the first audio, which is what lets the song start.
        if (total > 0 && !artRequested && !cache.hasArt(ref)) {
            artRequested = true
            send(Message.FetchArt(ref))
        }
        var next = pendingEnds.lastOrNull() ?: cache.receivedBytes(ref)
        // Until the size is known, one request at a time.
        val limit = if (total < 0) 1 else Transfers.WINDOWS
        while (pendingEnds.size < limit && (total < 0 || next < total)) {
            send(Message.Fetch(ref, next, Transfers.WINDOW_BYTES))
            next += Transfers.WINDOW_BYTES
            pendingEnds.addLast(next)
        }
    }

    fun onArt(art: Message.Art) {
        if (art.track == target && art.data.size in 1..Transfers.MAX_ART_BYTES) cache.saveArt(art.track, art.data)
    }

    fun onChunk(chunk: Message.Chunk): Event? {
        val ref = target ?: return null
        if (chunk.track != ref) return null
        if (chunk.totalBytes !in 1..Transfers.MAX_SONG_BYTES) {
            cache.failed(ref)
            reset()
            return Event.Failed(ref)
        }
        val first = total < 0
        if (first) {
            total = chunk.totalBytes
            cache.expect(ref, total)
        }
        if (!cache.append(ref, chunk.offset, chunk.data)) {
            // Bytes from beyond what arrived (answered out of order, or lost on a reconnect): they
            // can't be kept, so ask again from the gap instead of waiting forever for it.
            val received = cache.receivedBytes(ref)
            if (chunk.offset <= received || received == recoveringFrom) return null
            recoveringFrom = received
            pendingEnds.clear()
            return Event.Progress(ref, received.toFloat() / total)
        }
        val received = cache.receivedBytes(ref)
        while (pendingEnds.isNotEmpty() && received >= minOf(pendingEnds.first(), total)) pendingEnds.removeFirst()
        if (received < total) {
            return if (first) Event.Started(ref, cache.playable(ref) ?: return null) else Event.Progress(ref, received.toFloat() / total)
        }
        val file = cache.finish(ref)
        reset()
        return Event.Complete(ref, file)
    }

    /** True when the song being received turned out to be unavailable. */
    fun onUnavailable(message: Message.Unavailable): Boolean {
        if (message.track != target) return false
        cache.failed(message.track)
        reset()
        return true
    }

    private fun reset() {
        target = null
        total = -1
        pendingEnds.clear()
        artRequested = false
        recoveringFrom = -1
    }
}
