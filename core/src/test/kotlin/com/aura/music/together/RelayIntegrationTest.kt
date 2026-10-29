package com.aura.music.together

import com.aura.music.together.transport.Connection
import com.aura.music.together.transport.OkHttpWsConnector
import com.aura.music.together.transport.RelayEndpoint
import com.aura.music.together.transport.RelayHostLink
import com.aura.music.together.transport.RelayStatus
import com.aura.music.together.transport.WsConnection
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to a real relay Worker (`wrangler dev` or the deployed one). Skipped unless
 * AURA_RELAY_URL is set, e.g. `ws://127.0.0.1:8788/relay/v1`.
 */
class RelayIntegrationTest {
    private val url = System.getenv("AURA_RELAY_URL")

    private fun id(bytes: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(SecureRandom()::nextBytes))

    @Test
    fun `frames cross the real relay both ways and only the owner can host`() = runBlocking {
        assumeTrue("AURA_RELAY_URL not set", url != null)
        val endpoint = RelayEndpoint(url!!, OkHttpWsConnector())
        val room = id(12)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hostSide = CompletableDeferred<Connection>()
        val host = RelayHostLink(endpoint.connector, endpoint.hostUrl(room), id(32), scope) { hostSide.complete(it) }
        try {
            host.start()
            withTimeout(10_000) { host.status.first { it == RelayStatus.Online } }

            val guest = WsConnection(endpoint.connector.open(endpoint.guestUrl(room), emptyMap()))
            val fromGuest = ByteArray(2_000).also(SecureRandom()::nextBytes)
            guest.send(fromGuest)
            val hostConn = withTimeout(10_000) { hostSide.await() }
            assertArrayEquals(fromGuest, hostConn.receive(5_000))
            val fromHost = ByteArray(300).also(SecureRandom()::nextBytes)
            hostConn.send(fromHost)
            assertArrayEquals(fromHost, guest.receive(5_000))

            // Another phone can't take over the room without the owner's token.
            val hijack = runCatching { endpoint.connector.open(endpoint.hostUrl(room), mapOf("Authorization" to "Bearer ${id(32)}")) }
            assertTrue("hijack refused: ${hijack.exceptionOrNull()}", hijack.exceptionOrNull() is IOException)

            // The host removing a guest closes the guest's socket.
            hostConn.close()
            val closed = runCatching { guest.receive(5_000) }.exceptionOrNull()
            assertTrue("guest closed: $closed", closed is IOException)
        } finally {
            host.stop()
            scope.cancel()
        }
        // With the host gone, nobody can join the room.
        val late = runCatching { endpoint.connector.open(endpoint.guestUrl(room), emptyMap()) }
        assertTrue("late guest refused: ${late.exceptionOrNull()}", late.exceptionOrNull() is IOException)
    }
}
