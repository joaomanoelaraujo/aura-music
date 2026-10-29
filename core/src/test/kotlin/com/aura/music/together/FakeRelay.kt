package com.aura.music.together

import com.aura.music.together.transport.Envelope
import com.aura.music.together.transport.WsConnector
import com.aura.music.together.transport.WsSocket
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel

/** In-memory stand-in for the Cloudflare relay Worker: same URLs, same host envelope, one room. */
class FakeRelay : WsConnector {
    @Volatile private var host: Pipe? = null
    private val guests = ConcurrentHashMap<Int, Pipe>()
    private val cids = AtomicInteger()
    val guestOpens = AtomicInteger()

    val hostOnline get() = host != null

    override suspend fun open(url: String, headers: Map<String, String>): WsSocket = synchronized(this) {
        if (url.endsWith("/host")) {
            if ((headers["Authorization"]?.removePrefix("Bearer ")?.length ?: 0) < 32) throw IOException("HTTP 401")
            host?.drop()
            Pipe(onSend = ::fromHost, onClose = ::closeHost).also { pipe ->
                host = pipe
                guests.keys.forEach { pipe.deliver(Envelope.encode(Envelope.JOIN, it)) }
            }
        } else {
            val h = host ?: throw IOException("HTTP 409")
            guestOpens.incrementAndGet()
            val cid = cids.incrementAndGet()
            Pipe(
                onSend = { bytes -> host?.deliver(Envelope.encode(Envelope.DATA, cid, bytes)) },
                onClose = {
                    guests.remove(cid)
                    host?.deliver(Envelope.encode(Envelope.LEAVE, cid))
                },
            ).also {
                guests[cid] = it
                h.deliver(Envelope.encode(Envelope.JOIN, cid))
            }
        }
    }

    private fun fromHost(frame: ByteArray) {
        if (frame.size < 5) return
        val cid = Envelope.cid(frame)
        when (Envelope.type(frame)) {
            Envelope.DATA -> guests[cid]?.deliver(Envelope.payload(frame))
            Envelope.KICK -> guests.remove(cid)?.drop()
        }
    }

    private fun closeHost(pipe: Pipe) = synchronized(this) {
        if (host !== pipe) return
        host = null
        guests.values.forEach { it.drop() }
        guests.clear()
    }

    private class Pipe(private val onSend: (ByteArray) -> Unit, private val onClose: (Pipe) -> Unit) : WsSocket {
        @Volatile private var open = true
        override val incoming = Channel<ByteArray>(Channel.UNLIMITED)

        fun deliver(bytes: ByteArray) {
            if (open) incoming.trySend(bytes)
        }

        /** Closed from the relay's side. */
        fun drop() {
            open = false
            incoming.close(IOException("Closed by relay"))
        }

        override fun send(bytes: ByteArray): Boolean {
            if (!open) return false
            onSend(bytes)
            return true
        }

        override fun close(code: Int, reason: String) {
            if (!open) return
            drop()
            onClose(this)
        }
    }
}
