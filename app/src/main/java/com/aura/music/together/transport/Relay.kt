package com.aura.music.together.transport

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The little of a WebSocket the relay needs, so relay logic can be tested without a network. */
interface WsSocket {
    /** Queues a binary message; false when the socket is already closed. */
    fun send(bytes: ByteArray): Boolean

    /** Incoming binary messages; closed (with an [IOException]) when the socket closes. */
    val incoming: ReceiveChannel<ByteArray>

    fun close(code: Int = 1000, reason: String = "")
}

fun interface WsConnector {
    /** Opens a WebSocket or throws [IOException] if refused (room not found, no host yet, offline…). */
    suspend fun open(url: String, headers: Map<String, String>): WsSocket
}

/** The relay's room URLs; a room is named after the session id, which the invite already carries. */
class RelayEndpoint(private val baseUrl: String, val connector: WsConnector) {
    fun hostUrl(sessionId: String) = "${baseUrl.trimEnd('/')}/room/$sessionId/host"

    fun guestUrl(sessionId: String) = "${baseUrl.trimEnd('/')}/room/$sessionId/guest"
}

/** Frame types of the host ↔ relay envelope (see the Worker's source). */
internal object Envelope {
    const val JOIN: Byte = 1
    const val DATA: Byte = 2
    const val LEAVE: Byte = 3
    const val KICK: Byte = 4

    fun encode(type: Byte, cid: Int, payload: ByteArray? = null): ByteArray =
        ByteBuffer.allocate(5 + (payload?.size ?: 0)).put(type).putInt(cid).apply { payload?.let(::put) }.array()

    fun type(bytes: ByteArray) = bytes[0]

    fun cid(bytes: ByteArray) = ByteBuffer.wrap(bytes, 1, 4).int

    fun payload(bytes: ByteArray) = bytes.copyOfRange(5, bytes.size)
}

/** A guest's single WebSocket to the relay, used as a [Connection]. */
class WsConnection(private val ws: WsSocket) : Connection {
    override suspend fun send(frame: ByteArray) {
        if (!ws.send(frame)) throw IOException("Relay connection closed")
    }

    override suspend fun receive(timeoutMs: Long): ByteArray = receiveFrom(ws.incoming, timeoutMs)

    override suspend fun closeGracefully() {
        delay(GRACE_MS)
        close()
    }

    override fun close() = ws.close(1000, "bye")
}

internal suspend fun receiveFrom(channel: ReceiveChannel<ByteArray>, timeoutMs: Long): ByteArray = try {
    if (timeoutMs > 0) withTimeout(timeoutMs) { channel.receive() } else channel.receive()
} catch (_: TimeoutCancellationException) {
    throw ConnectionTimeout()
} catch (e: ClosedReceiveChannelException) {
    throw IOException("Closed", e)
}

enum class RelayStatus { Connecting, Online, Offline }

/**
 * The host's link to its relay room. One WebSocket carries every guest; each guest appears as a
 * virtual [Connection] handed to [onConnection], exactly like a TCP client on the local network.
 * Reconnects with backoff if the network drops; guests reconnect on their own meanwhile.
 */
class RelayHostLink(
    private val connector: WsConnector,
    private val url: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val onConnection: suspend (Connection) -> Unit,
) {
    private val guests = ConcurrentHashMap<Int, VirtualConnection>()
    private val _status = MutableStateFlow(RelayStatus.Connecting)
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    @Volatile private var socket: WsSocket? = null
    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive) {
                try {
                    val ws = connector.open(url, mapOf("Authorization" to "Bearer $token"))
                    socket = ws
                    _status.value = RelayStatus.Online
                    backoff = INITIAL_BACKOFF_MS
                    for (frame in ws.incoming) route(frame)
                } catch (_: IOException) {
                } catch (_: ClosedReceiveChannelException) {
                }
                socket = null
                guests.values.forEach { it.closeIncoming() }
                guests.clear()
                if (!isActive) break
                _status.value = RelayStatus.Offline
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                _status.value = RelayStatus.Connecting
            }
        }
    }

    fun stop() {
        job?.cancel()
        socket?.close(1000, "Session ended")
        guests.values.forEach { it.closeIncoming() }
        guests.clear()
        _status.value = RelayStatus.Offline
    }

    private fun route(frame: ByteArray) {
        if (frame.size < 5) return
        val cid = Envelope.cid(frame)
        when (Envelope.type(frame)) {
            Envelope.JOIN -> {
                val connection = VirtualConnection(cid)
                guests.put(cid, connection)?.closeIncoming()
                scope.launch(Dispatchers.IO) { onConnection(connection) }
            }
            Envelope.DATA -> guests[cid]?.deliver(Envelope.payload(frame))
            Envelope.LEAVE -> guests.remove(cid)?.closeIncoming()
        }
    }

    private inner class VirtualConnection(private val cid: Int) : Connection {
        private val incoming = Channel<ByteArray>(QUEUE)

        fun deliver(bytes: ByteArray) {
            // A guest flooding faster than the host reads is dropped rather than buffered forever.
            if (incoming.trySend(bytes).isFailure) closeIncoming()
        }

        fun closeIncoming() {
            incoming.close(IOException("Guest left"))
        }

        override suspend fun send(frame: ByteArray) {
            val ws = socket ?: throw IOException("Relay offline")
            if (!ws.send(Envelope.encode(Envelope.DATA, cid, frame))) throw IOException("Relay offline")
        }

        override suspend fun receive(timeoutMs: Long): ByteArray = receiveFrom(incoming, timeoutMs)

        override suspend fun closeGracefully() {
            delay(GRACE_MS)
            close()
        }

        override fun close() {
            socket?.send(Envelope.encode(Envelope.KICK, cid))
            guests.remove(cid)
            closeIncoming()
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
        const val QUEUE = 128
    }
}

private const val GRACE_MS = 400L
