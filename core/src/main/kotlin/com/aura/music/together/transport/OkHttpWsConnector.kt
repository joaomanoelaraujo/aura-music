package com.aura.music.together.transport

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** [WsConnector] backed by OkHttp; plain JVM code, so tests can use it against a local relay too. */
class OkHttpWsConnector(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // Keeps carrier NAT mappings alive even while the session is quiet.
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : WsConnector {

    override suspend fun open(url: String, headers: Map<String, String>): WsSocket {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val incoming = Channel<ByteArray>(QUEUE)
        val opened = CompletableDeferred<Unit>()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // A reader that can't keep up is disconnected rather than buffered without limit.
                if (incoming.trySend(bytes.toByteArray()).isFailure) webSocket.cancel()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                incoming.close(IOException("Closed by relay: $code $reason"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close(IOException("Closed: $code $reason"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = IOException(response?.let { "Relay refused: HTTP ${it.code}" } ?: "Relay unreachable", t)
                opened.completeExceptionally(error)
                incoming.close(error)
            }
        })
        try {
            opened.await()
        } catch (e: Throwable) {
            ws.cancel()
            throw e
        }
        return object : WsSocket {
            override val incoming: ReceiveChannel<ByteArray> = incoming

            override fun send(bytes: ByteArray) = ws.send(bytes.toByteString())

            override fun close(code: Int, reason: String) {
                ws.close(code, reason)
            }
        }
    }

    private companion object {
        const val QUEUE = 256
    }
}
