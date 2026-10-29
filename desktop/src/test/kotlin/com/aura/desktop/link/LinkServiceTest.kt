package com.aura.desktop.link

import com.aura.music.link.FrameStream
import com.aura.music.link.Handshake
import com.aura.music.link.LinkMessage
import com.aura.music.link.LinkPermissions
import com.aura.music.link.LinkRejectedException
import com.aura.music.link.LinkSession
import com.aura.music.link.LocalDevice
import com.aura.music.link.PairMethod
import com.aura.music.link.PairedDevice
import com.aura.music.link.Pairing
import com.aura.music.link.Platform
import com.aura.music.link.RejectReason
import com.aura.music.link.RemoteAction
import com.aura.music.link.RemotePlayerState
import com.aura.music.link.RemoteTrack
import com.aura.music.link.crypto.SoftwareIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The desktop's AURA Link service against a simulated phone that uses the same shared code the
 * Android app uses, over real loopback TCP.
 */
class LinkServiceTest {

    @get:Rule val temp = TemporaryFolder()

    private val mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }
    private val main = mainThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    private class FakeBridge : LocalPlayerBridge {
        val applied = CopyOnWriteArrayList<RemoteAction>()
        override fun snapshot(deviceName: String, controlledBy: String?) =
            RemotePlayerState(1, deviceName, isPlaying = false, positionMs = 0, sampledAt = 0, durationMs = 0, controlledBy = controlledBy)
        override suspend fun apply(action: RemoteAction): Boolean { applied += action; return true }
        override suspend fun search(query: String, limit: Int) = listOf(RemoteTrack("1", "Canção Inventada", "Banda Exemplo"))
        override suspend fun artwork(trackId: String): ByteArray? = null
    }

    private val bridge = FakeBridge()
    private lateinit var service: LinkService

    @After
    fun tearDown() {
        if (::service.isInitialized) service.stop()
        scope.cancel()
        mainThread.shutdownNow()
    }

    private fun startService(): LinkService {
        val dir = temp.newFolder().toPath()
        service = LinkService(scope, main, LocalIdentityStore(dir), PairedDeviceStore(scope, dir.resolve("devices.json")), bridge, "test", preferredPort = 0, advertise = false)
        mainThread.submit { service.start() }.get()
        return service
    }

    private val phone = LocalDevice("phone-1", "Galaxy de Teste", Platform.Android, SoftwareIdentity.generate(), listOf("player.v1"))

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out; state=${service.state.value}" }
            Thread.sleep(20)
        }
    }

    private fun socket() = Socket(InetAddress.getLoopbackAddress(), service.state.value.port).apply { soTimeout = 15_000 }

    /** Pairs [phone] by scanning the QR code, accepting on both sides. Returns what the phone stored. */
    private fun pairByQr(): PairedDevice {
        mainThread.submit { service.openPairing() }.get()
        val payload = service.state.value.pairing!!.qrPayload
        val query = URI(payload).rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val secret = Base64.getUrlDecoder().decode(query.getValue("s"))
        // The computer user accepts once the offer shows up.
        Thread {
            waitFor { service.state.value.offer != null }
            service.answerOffer(true)
        }.start()
        return socket().use { s -> Pairing.initiate(FrameStream(s.getInputStream(), s.getOutputStream()), PairMethod.Qr, secret, phone) { true } }
    }

    private fun connect(pc: PairedDevice, received: MutableList<LinkMessage>): LinkSession {
        val s = socket()
        val link = Handshake.connect(FrameStream(s.getInputStream(), s.getOutputStream()), phone, pc)
        com.aura.music.link.Channels.announce(link, com.aura.music.link.ChannelHello.Control)
        s.soTimeout = 0
        return LinkSession(link, onMessage = { _, m -> received += m }, onClosed = { _, _ -> }, closeTransport = s::close).also { it.start() }
    }

    @Test
    fun aPhonePairsByQrThenControlsTheComputer() {
        startService()
        val pc = pairByQr()
        waitFor { service.state.value.peers.any { it.device.deviceId == "phone-1" } }
        assertNull(service.state.value.pairing) // the window closes once paired
        assertEquals("Galaxy de Teste", service.state.value.peers.single().device.name)

        val received = CopyOnWriteArrayList<LinkMessage>()
        val session = connect(pc, received)
        waitFor { service.state.value.peers.single().connected }
        // The computer introduces itself and sends what it's playing.
        waitFor { received.any { it is LinkMessage.Capabilities } && received.any { it is LinkMessage.State } }

        session.command(RemoteAction.Pause)
        session.command(RemoteAction.Seek(30_000))
        waitFor { bridge.applied.size == 2 }
        assertEquals(listOf(RemoteAction.Pause, RemoteAction.Seek(30_000)), bridge.applied.toList())
        waitFor { service.state.value.controlledBy == "Galaxy de Teste" }

        session.send(LinkMessage.Search(7, "inventada"))
        waitFor { received.any { it is LinkMessage.SearchResults } }
        assertEquals("Canção Inventada", (received.first { it is LinkMessage.SearchResults } as LinkMessage.SearchResults).tracks.single().title)
        session.disconnect()
    }

    @Test
    fun permissionsAreEnforcedOnTheComputer() {
        startService()
        val pc = pairByQr()
        mainThread.submit { service.setPermissions("phone-1", LinkPermissions(controlPlayback = false, browseLibrary = false)) }.get()
        val received = CopyOnWriteArrayList<LinkMessage>()
        val session = connect(pc, received)
        session.command(RemoteAction.Next)
        waitFor { received.any { it is LinkMessage.CommandResult } }
        val result = received.first { it is LinkMessage.CommandResult } as LinkMessage.CommandResult
        assertEquals(false, result.ok)
        assertTrue(bridge.applied.isEmpty())
        session.send(LinkMessage.Search(1, "x"))
        waitFor { received.any { it is LinkMessage.SearchResults } }
        assertTrue((received.first { it is LinkMessage.SearchResults } as LinkMessage.SearchResults).tracks.isEmpty())
        session.disconnect()
    }

    @Test
    fun pausedAndRevokedDevicesCannotReconnect() {
        startService()
        val pc = pairByQr()
        mainThread.submit { service.pause("phone-1") }.get()
        val paused = runCatching { socket().use { s -> Handshake.connect(FrameStream(s.getInputStream(), s.getOutputStream()), phone, pc) } }
        assertEquals(RejectReason.Paused, (paused.exceptionOrNull() as LinkRejectedException).reason)

        mainThread.submit { service.resume("phone-1") }.get()
        val received = CopyOnWriteArrayList<LinkMessage>()
        val session = connect(pc, received)
        waitFor { service.state.value.peers.single().connected }

        mainThread.submit { service.revoke("phone-1") }.get()
        // The phone is told it was unpaired…
        waitFor { received.any { it is LinkMessage.Bye && it.revoked } }
        assertTrue(service.state.value.peers.isEmpty())
        // …and can't come back with its old key.
        val again = runCatching { socket().use { s -> Handshake.connect(FrameStream(s.getInputStream(), s.getOutputStream()), phone, pc) } }
        assertEquals(RejectReason.UnknownDevice, (again.exceptionOrNull() as LinkRejectedException).reason)
        assertTrue(!session.isOpen)
    }

    @Test
    fun wrongCodesUseUpTheAttemptsAndCloseTheWindow() {
        startService()
        mainThread.submit { service.openPairing() }.get()
        val real = service.state.value.pairing!!.code
        val wrong = ((real.toInt() + 1) % 100_000_000).toString().padStart(8, '0')
        repeat(LinkService.MAX_ATTEMPTS) {
            val r = runCatching { socket().use { s -> Pairing.initiate(FrameStream(s.getInputStream(), s.getOutputStream()), PairMethod.Code, Pairing.codeSecret(wrong), phone) { true } } }
            assertTrue(r.isFailure)
        }
        waitFor { service.state.value.pairing == null }
        assertNotNull(service.state.value.message)
        // Even the right code is refused now.
        val late = runCatching { socket().use { s -> Pairing.initiate(FrameStream(s.getInputStream(), s.getOutputStream()), PairMethod.Code, Pairing.codeSecret(real), phone) { true } } }
        assertEquals(RejectReason.Expired, (late.exceptionOrNull() as LinkRejectedException).reason)
    }

    @Test
    fun theIdentityIsStableAcrossRestarts() {
        val dir = temp.newFolder().toPath()
        val first = LocalIdentityStore(dir).load("PC")
        val second = LocalIdentityStore(dir).load("PC")
        assertEquals(first.first, second.first)
        assertTrue(first.second.publicKey.contentEquals(second.second.publicKey))
    }
}
