package com.aura.music.together.transport

import com.aura.music.together.protocol.Message
import com.aura.music.together.protocol.SecureChannel
import com.aura.music.together.protocol.Wire
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A bidirectional pipe of whole frames, whatever carries them (local TCP or the relay). */
interface Connection {
    suspend fun send(frame: ByteArray)

    /** Next frame. Throws [ConnectionTimeout] after [timeoutMs] of silence (0 = wait forever), [IOException] when closed. */
    suspend fun receive(timeoutMs: Long = 0): ByteArray

    /** Lets frames already sent reach the peer, then closes. */
    suspend fun closeGracefully()

    fun close()
}

class ConnectionTimeout : IOException("No data within the timeout")

/** Messages over a [Connection], sealed with the session key. */
class SecureLink(val connection: Connection, private val channel: SecureChannel) {
    suspend fun send(message: Message) = connection.send(channel.seal(Wire.encode(message)))

    /** A frame that decrypts but doesn't parse ends the connection like any other protocol error. */
    suspend fun receive(timeoutMs: Long = 0): Message {
        val plain = channel.open(connection.receive(timeoutMs))
        return try {
            Wire.decode(plain)
        } catch (e: IllegalArgumentException) {
            throw IOException("Malformed message", e)
        }
    }
}

/** Direct connection on the local network: length-prefixed frames over TCP. */
class TcpConnection(private val socket: Socket) : Connection {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val sendLock = Mutex()

    init {
        socket.tcpNoDelay = true
    }

    override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) {
        sendLock.withLock {
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
        }
    }

    override suspend fun receive(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        socket.soTimeout = timeoutMs.toInt()
        try {
            val size = input.readInt()
            if (size <= 0 || size > MAX_FRAME) throw IOException("Bad frame size $size")
            ByteArray(size).also(input::readFully)
        } catch (_: SocketTimeoutException) {
            throw ConnectionTimeout()
        }
    }

    /**
     * Half-closes first: closing outright while the peer's pings are unread would make TCP send a
     * reset, which can discard the farewell still in flight.
     */
    override suspend fun closeGracefully() = withContext(Dispatchers.IO) {
        runCatching { socket.shutdownOutput() }
        delay(GRACE_MS)
        close()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val MAX_FRAME = 512 * 1024
        private const val GRACE_MS = 1_000L

        suspend fun connect(host: String, port: Int, timeoutMs: Int): TcpConnection = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            TcpConnection(socket)
        }
    }
}
