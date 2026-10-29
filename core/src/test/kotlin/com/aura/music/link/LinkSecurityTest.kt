package com.aura.music.link

import com.aura.music.link.crypto.DeviceIdentity
import com.aura.music.link.crypto.FrameCipher
import com.aura.music.link.crypto.LinkSecurityException
import com.aura.music.link.crypto.SoftwareIdentity
import com.aura.music.link.crypto.Spake2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LinkSecurityTest {

    private fun device(id: String, name: String, platform: Platform = Platform.Windows) =
        LocalDevice(id, name, platform, SoftwareIdentity.generate(), listOf(LinkCapabilities.PLAYER))

    /** Runs [server] and [client] on the two ends of a real loopback TCP connection. */
    private fun <S, C> overTcp(server: (FrameStream) -> S, client: (FrameStream) -> C): Pair<Result<S>, Result<C>> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val serverResult = CompletableFuture.supplyAsync {
                listener.accept().use { s -> runCatching { server(FrameStream(s.getInputStream(), s.getOutputStream())) } }
            }
            val clientResult = Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { s ->
                s.soTimeout = 10_000
                runCatching { client(FrameStream(s.getInputStream(), s.getOutputStream())) }
            }
            return serverResult.get(15, TimeUnit.SECONDS) to clientResult
        }
    }

    @Test
    fun spake2AgreesOnlyWithTheSamePassword() {
        val a = Spake2(Spake2.Role.A, "12345678".toByteArray())
        val b = Spake2(Spake2.Role.B, "12345678".toByteArray())
        val ra = a.finish(b.share)
        val rb = b.finish(a.share)
        assertArrayEquals(ra.sharedKey, rb.sharedKey)
        assertArrayEquals(ra.expectedPeerConfirmation, rb.myConfirmation)
        assertArrayEquals(rb.expectedPeerConfirmation, ra.myConfirmation)

        val wrong = Spake2(Spake2.Role.B, "12345679".toByteArray())
        val rw = wrong.finish(Spake2(Spake2.Role.A, "12345678".toByteArray()).share)
        val ra2 = Spake2(Spake2.Role.A, "12345678".toByteArray()).let { it.finish(wrong.share) }
        assertFalse(ra2.sharedKey.contentEquals(rw.sharedKey))
        assertFalse(ra2.expectedPeerConfirmation.contentEquals(rw.myConfirmation))
    }

    @Test
    fun spake2RejectsPointsOffTheCurve() {
        val a = Spake2(Spake2.Role.A, "x".toByteArray())
        val bogus = ByteArray(65).also { it[0] = 4; it[1] = 1 }
        try {
            a.finish(bogus)
            fail("accepted a point off the curve")
        } catch (e: LinkSecurityException) {
            // expected
        }
    }

    @Test
    fun pairsWithTheQrSecretAndBothSeeTheSameCode() {
        val pc = device("pc-1", "PC do João")
        val phone = device("phone-1", "Galaxy do João", Platform.Android)
        val secret = Pairing.newQrSecret()
        val codes = mutableListOf<String>()
        val (onPc, onPhone) = overTcp(
            server = { s -> Pairing.respond(s, { secret }, pc, onWrongCode = {}) { offer -> synchronized(codes) { codes += offer.verificationCode }; true } },
            client = { s -> Pairing.initiate(s, PairMethod.Qr, secret, phone) { offer -> synchronized(codes) { codes += offer.verificationCode }; true } },
        )
        val phoneSeenByPc = onPc.getOrThrow()
        val pcSeenByPhone = onPhone.getOrThrow()
        assertEquals("phone-1", phoneSeenByPc.deviceId)
        assertEquals("Galaxy do João", phoneSeenByPc.name)
        assertEquals(Platform.Android, phoneSeenByPc.platform)
        assertArrayEquals(phone.identity.publicKey, phoneSeenByPc.publicKeyBytes)
        assertArrayEquals(pc.identity.publicKey, pcSeenByPhone.publicKeyBytes)
        assertEquals(2, codes.size)
        assertEquals(codes[0], codes[1])
        assertEquals(6, codes[0].length)
    }

    @Test
    fun aWrongTypedCodeFailsOnBothSidesAndCountsAsAnAttempt() {
        val attempts = AtomicInteger()
        val (onPc, onPhone) = overTcp(
            server = { s -> Pairing.respond(s, { Pairing.codeSecret("1234-5678") }, device("pc", "PC"), onWrongCode = { attempts.incrementAndGet() }) { true } },
            client = { s -> Pairing.initiate(s, PairMethod.Code, Pairing.codeSecret("1234 5679"), device("ph", "Phone")) { true } },
        )
        assertEquals(RejectReason.WrongCode, (onPhone.exceptionOrNull() as LinkRejectedException).reason)
        assertTrue(onPc.isFailure)
        assertEquals(1, attempts.get())
    }

    @Test
    fun aClosedPairingWindowRefusesAttempts() {
        val (_, onPhone) = overTcp(
            server = { s -> Pairing.respond(s, { null }, device("pc", "PC"), onWrongCode = {}) { true } },
            client = { s -> Pairing.initiate(s, PairMethod.Qr, Pairing.newQrSecret(), device("ph", "Phone")) { true } },
        )
        assertEquals(RejectReason.Expired, (onPhone.exceptionOrNull() as LinkRejectedException).reason)
    }

    @Test
    fun theServerCanTellPairingFromReconnecting() {
        val (isPairing, _) = overTcp(
            server = { s -> Pairing.isPairingAttempt(s).also { s.read() } },
            client = { s -> runCatching { Pairing.initiate(s, PairMethod.Qr, Pairing.newQrSecret(), device("ph", "Phone")) { true } } },
        )
        assertTrue(isPairing.getOrThrow())
    }

    @Test
    fun declinedOnEitherSideMeansNotPaired() {
        val secret = Pairing.codeSecret("87654321")
        val (onPc, onPhone) = overTcp(
            server = { s -> Pairing.respond(s, { secret }, device("pc", "PC"), onWrongCode = {}) { true } },
            client = { s -> Pairing.initiate(s, PairMethod.Code, secret, device("ph", "Phone")) { false } },
        )
        assertEquals(RejectReason.Declined, (onPc.exceptionOrNull() as LinkRejectedException).reason)
        assertEquals(RejectReason.Declined, (onPhone.exceptionOrNull() as LinkRejectedException).reason)
    }

    private fun paired(local: LocalDevice, other: LocalDevice) = PairedDevice(
        deviceId = other.deviceId, name = other.name, platform = other.platform,
        publicKey = Base64.getEncoder().encodeToString(other.identity.publicKey), pairedAt = 0,
    )

    @Test
    fun pairedDevicesReconnectWithFreshKeys() {
        val pc = device("pc", "PC")
        val phone = device("phone", "Phone", Platform.Android)
        val (onPc, onPhone) = overTcp(
            server = { s -> Handshake.accept(s, pc, lookup = { id -> if (id == "phone") paired(pc, phone) else null }) },
            client = { s -> Handshake.connect(s, phone, paired(phone, pc)) },
        )
        assertEquals("phone", onPc.getOrThrow().peer.deviceId)
        assertEquals("pc", onPhone.getOrThrow().peer.deviceId)
        // Both ends derived matching directional keys.
        val sealed = onPhone.getOrThrow().cipher.seal("olá".toByteArray())
        assertEquals("olá", String(onPc.getOrThrow().cipher.open(sealed)))
    }

    @Test
    fun aRevokedDeviceIsRefused() {
        val (onPc, onPhone) = overTcp(
            server = { s -> Handshake.accept(s, device("pc", "PC"), lookup = { null }) },
            client = { s -> Handshake.connect(s, device("phone", "Phone"), paired(device("x", "x"), device("pc", "PC"))) },
        )
        assertEquals(RejectReason.UnknownDevice, (onPhone.exceptionOrNull() as LinkRejectedException).reason)
        assertTrue(onPc.isFailure)
    }

    @Test
    fun anImpostorWithTheRightIdButAnotherKeyFails() {
        val pc = device("pc", "PC")
        val realPhone = device("phone", "Phone")
        val impostor = device("phone", "Phone") // same id and name, different key
        val (onPc, onImpostor) = overTcp(
            server = { s -> Handshake.accept(s, pc, lookup = { paired(pc, realPhone) }) },
            client = { s -> Handshake.connect(s, impostor, paired(impostor, pc)) },
        )
        assertTrue(onPc.exceptionOrNull() is LinkSecurityException)
        assertTrue(onImpostor.isFailure)
    }

    @Test
    fun aFakeComputerCannotAnswerForThePairedOne() {
        val phone = device("phone", "Phone")
        val realPc = device("pc", "PC")
        val fakePc = device("pc", "PC")
        val (_, onPhone) = overTcp(
            server = { s -> Handshake.accept(s, fakePc, lookup = { paired(fakePc, phone) }) },
            client = { s -> Handshake.connect(s, phone, paired(phone, realPc)) },
        )
        assertTrue(onPhone.exceptionOrNull() is LinkSecurityException)
    }

    @Test
    fun replayedOrReorderedFramesAreRejected() {
        val key1 = ByteArray(32) { 1 }
        val key2 = ByteArray(32) { 2 }
        val a = FrameCipher(key1, key2)
        val b = FrameCipher(key2, key1)
        val first = a.seal("um".toByteArray())
        val second = a.seal("dois".toByteArray())
        assertEquals("um", String(b.open(first)))
        try {
            b.open(first)
            fail("replay accepted")
        } catch (e: LinkSecurityException) {
            // expected
        }
        val c = FrameCipher(key2, key1)
        try {
            c.open(second)
            fail("out-of-order frame accepted")
        } catch (e: LinkSecurityException) {
            // expected
        }
        val tampered = a.seal("três".toByteArray()).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val d = FrameCipher(key2, key1).apply { open(first); open(second) }
        try {
            d.open(tampered)
            fail("tampered frame accepted")
        } catch (e: LinkSecurityException) {
            // expected
        }
    }

    @Test
    fun sessionsExchangeMessagesAndDropDuplicateCommands() {
        val pc = device("pc", "PC")
        val phone = device("phone", "Phone")
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val received = mutableListOf<LinkMessage>()
            val gotThree = CountDownLatch(3)
            val serverSide = CompletableFuture.supplyAsync {
                val socket = listener.accept()
                val link = Handshake.accept(FrameStream(socket.getInputStream(), socket.getOutputStream()), pc, lookup = { paired(pc, phone) })
                LinkSession(link, onMessage = { _, m -> synchronized(received) { received += m }; gotThree.countDown() }, onClosed = { _, _ -> }, closeTransport = socket::close).also { it.start() }
            }
            val socket = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            val link = Handshake.connect(FrameStream(socket.getInputStream(), socket.getOutputStream()), phone, paired(phone, pc))
            val client = LinkSession(link, onMessage = { _, _ -> }, onClosed = { _, _ -> }, closeTransport = socket::close).also { it.start() }
            val server = serverSide.get(10, TimeUnit.SECONDS)

            client.send(LinkMessage.Command(1, RemoteAction.Pause))
            client.send(LinkMessage.Command(1, RemoteAction.Pause)) // a duplicate: must be dropped
            client.send(LinkMessage.Command(2, RemoteAction.Seek(42_000)))
            client.send(LinkMessage.StateRequest)
            assertTrue(gotThree.await(5, TimeUnit.SECONDS))
            Thread.sleep(200)
            synchronized(received) {
                assertEquals(listOf(LinkMessage.Command(1, RemoteAction.Pause), LinkMessage.Command(2, RemoteAction.Seek(42_000)), LinkMessage.StateRequest), received)
            }
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun fingerprintsAreShortAndStable() {
        val id = SoftwareIdentity.generate()
        val f = DeviceIdentity.fingerprint(id.publicKey)
        assertEquals(f, DeviceIdentity.fingerprint(id.publicKey))
        assertEquals(19, f.length)
        val restored = SoftwareIdentity.restore(id.privateKeyBytes(), id.publicKey)
        assertTrue(DeviceIdentity.verify(id.publicKey, "x".toByteArray(), restored.sign("x".toByteArray())))
    }
}
