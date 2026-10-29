package com.aura.desktop.link

import com.aura.desktop.TestMedia
import com.aura.desktop.library.LibraryMapper
import com.aura.desktop.library.ScannedFile
import com.aura.music.link.ChannelHello
import com.aura.music.link.Channels
import com.aura.music.link.FrameStream
import com.aura.music.link.Handshake
import com.aura.music.link.LinkMessage
import com.aura.music.link.LinkSession
import com.aura.music.link.LocalDevice
import com.aura.music.link.PairMethod
import com.aura.music.link.PairedDevice
import com.aura.music.link.Pairing
import com.aura.music.link.Platform
import com.aura.music.link.RemoteAction
import com.aura.music.link.RemotePlayerState
import com.aura.music.link.RemoteTrack
import com.aura.music.link.StagingSink
import com.aura.music.link.TransferChannel
import com.aura.music.link.TransferFile
import com.aura.music.link.TransferOffer
import com.aura.music.link.TransferRefusal
import com.aura.music.link.crypto.SoftwareIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Transfers between the desktop service and a simulated phone running the shared code. */
class TransfersTest {

    @get:Rule val temp = TemporaryFolder()

    private val mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }
    private val main = mainThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private lateinit var service: LinkService
    private lateinit var receiveDir: Path
    private val received = CopyOnWriteArrayList<Path>()

    private val phone = LocalDevice("phone-t", "Celular de Teste", Platform.Android, SoftwareIdentity.generate(), listOf("files.v1"))

    private object Silent : LocalPlayerBridge {
        override fun snapshot(deviceName: String, controlledBy: String?) = RemotePlayerState(1, deviceName, false, 0, 0, 0)
        override suspend fun apply(action: RemoteAction) = true
        override suspend fun search(query: String, limit: Int) = emptyList<RemoteTrack>()
        override suspend fun artwork(trackId: String): ByteArray? = null
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) service.stop()
        scope.cancel()
        mainThread.shutdownNow()
    }

    private fun start() {
        val dir = temp.newFolder().toPath()
        receiveDir = temp.newFolder("Recebidas").toPath()
        val devices = PairedDeviceStore(scope, dir.resolve("devices.json"))
        val transfers = DesktopTransfers(
            scope, staging = dir.resolve("staging"), receiveDir = { receiveDir },
            permissions = { id -> devices.find(id)?.permissions }, onReceived = { received.addAll(it) },
        )
        service = LinkService(scope, main, LocalIdentityStore(dir), devices, Silent, "test", preferredPort = 0, advertise = false, transfers = transfers)
        mainThread.submit { service.start() }.get()
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out; jobs=${service.transfers?.jobs?.value}" }
            Thread.sleep(20)
        }
    }

    private fun socket() = Socket(InetAddress.getLoopbackAddress(), service.state.value.port).apply { soTimeout = 15_000 }

    private fun pair(): PairedDevice {
        mainThread.submit { service.openPairing() }.get()
        val query = URI(service.state.value.pairing!!.qrPayload).rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        Thread { waitFor { service.state.value.offer != null }; service.answerOffer(true) }.start()
        return socket().use { s -> Pairing.initiate(FrameStream(s.getInputStream(), s.getOutputStream()), PairMethod.Qr, Base64.getUrlDecoder().decode(query.getValue("s")), phone) { true } }
    }

    private fun control(pc: PairedDevice, inbox: MutableList<LinkMessage>): LinkSession {
        val s = socket()
        val link = Handshake.connect(FrameStream(s.getInputStream(), s.getOutputStream()), phone, pc)
        Channels.announce(link, ChannelHello.Control)
        s.soTimeout = 0
        return LinkSession(link, onMessage = { _, m -> inbox += m }, onClosed = { _, _ -> }, closeTransport = s::close).also { it.start() }
    }

    /** The phone's side of a data connection. */
    private fun <T> data(pc: PairedDevice, hello: ChannelHello.Data, block: (com.aura.music.link.AuthenticatedLink) -> T): T =
        socket().use { s ->
            val link = Handshake.connect(FrameStream(s.getInputStream(), s.getOutputStream()), phone, pc)
            Channels.announce(link, hello)
            block(link)
        }

    private fun offerOf(vararg files: File) = TransferOffer(
        "t-" + java.util.UUID.randomUUID(),
        files.map { TransferFile(it.name, it.length(), it.inputStream().use(TransferChannel::sha256)) },
    )

    @Test
    fun aPhoneSendsSongsTheComputerAsksAndPublishesThem() {
        start()
        val pc = pair()
        val inbox = CopyOnWriteArrayList<LinkMessage>()
        val session = control(pc, inbox)
        val song = TestMedia.file("Album/01.mp3").toFile()
        val offer = offerOf(song)
        session.send(LinkMessage.TransferOfferMessage(offer))
        // The computer asks its user; accept.
        waitFor { service.transfers!!.incoming.value != null }
        assertEquals("Celular de Teste", service.transfers!!.incoming.value!!.peerName)
        service.transfers!!.answerIncoming(accept = true, always = false)
        waitFor { inbox.any { it is LinkMessage.TransferAnswer } }
        assertTrue((inbox.first { it is LinkMessage.TransferAnswer } as LinkMessage.TransferAnswer).accepted)
        // The phone dials and sends.
        data(pc, ChannelHello.Data(offer.id, senderIsDialer = true)) { link ->
            TransferChannel.send(link, offer, { _, offset -> song.inputStream().apply { skip(offset) } }, {}, { false })
        }
        waitFor { service.transfers!!.jobs.value.firstOrNull { it.id == offer.id }?.status == TransferStatus.Done }
        assertArrayEquals(song.readBytes(), Files.readAllBytes(receiveDir.resolve("01.mp3")))
        assertEquals(listOf(receiveDir.resolve("01.mp3")), received.toList())

        // The same song again: recognised, not duplicated, nothing overwritten.
        val again = offerOf(song)
        session.send(LinkMessage.TransferOfferMessage(again))
        waitFor { service.transfers!!.incoming.value != null }
        service.transfers!!.answerIncoming(accept = true, always = true)
        waitFor { inbox.count { it is LinkMessage.TransferAnswer } == 2 }
        data(pc, ChannelHello.Data(again.id, senderIsDialer = true)) { link ->
            TransferChannel.send(link, again, { _, offset -> song.inputStream().apply { skip(offset) } }, {}, { false })
        }
        waitFor { service.transfers!!.jobs.value.firstOrNull { it.id == again.id }?.status == TransferStatus.Done }
        Files.list(receiveDir).use { assertEquals(1, it.filter { f -> !f.fileName.toString().startsWith(".") }.count()) }
        // "Always accept" was remembered for this phone.
        waitFor { service.state.value.peers.single().device.permissions.receiveFilesWithoutAsking }
        session.disconnect()
    }

    @Test
    fun aDifferentFileWithTheSameNameIsNotOverwritten() {
        start()
        val pc = pair()
        Files.writeString(receiveDir.resolve("01.mp3"), "arquivo que já estava aqui")
        mainThread.submit { service.setPermissions("phone-t", service.state.value.peers.single().device.permissions.copy(receiveFilesWithoutAsking = true)) }.get()
        val inbox = CopyOnWriteArrayList<LinkMessage>()
        val session = control(pc, inbox)
        val song = TestMedia.file("Album/01.mp3").toFile()
        val offer = offerOf(song)
        session.send(LinkMessage.TransferOfferMessage(offer))
        waitFor { inbox.any { it is LinkMessage.TransferAnswer } }
        data(pc, ChannelHello.Data(offer.id, senderIsDialer = true)) { link ->
            TransferChannel.send(link, offer, { _, offset -> song.inputStream().apply { skip(offset) } }, {}, { false })
        }
        waitFor { service.transfers!!.jobs.value.firstOrNull { it.id == offer.id }?.status == TransferStatus.Done }
        assertEquals("arquivo que já estava aqui", Files.readString(receiveDir.resolve("01.mp3")))
        assertArrayEquals(song.readBytes(), Files.readAllBytes(receiveDir.resolve("01 (2).mp3")))
        session.disconnect()
    }

    @Test
    fun declinedTransfersSendNothing() {
        start()
        val pc = pair()
        val inbox = CopyOnWriteArrayList<LinkMessage>()
        val session = control(pc, inbox)
        session.send(LinkMessage.TransferOfferMessage(offerOf(TestMedia.file("03.ogg").toFile())))
        waitFor { service.transfers!!.incoming.value != null }
        service.transfers!!.answerIncoming(accept = false, always = false)
        waitFor { inbox.any { it is LinkMessage.TransferAnswer } }
        val answer = inbox.first { it is LinkMessage.TransferAnswer } as LinkMessage.TransferAnswer
        assertEquals(false, answer.accepted)
        assertEquals(TransferRefusal.Declined, answer.refusal)
        session.disconnect()
    }

    @Test
    fun theComputerSendsSongsToThePhone() {
        start()
        val pc = pair()
        val inbox = CopyOnWriteArrayList<LinkMessage>()
        val session = control(pc, inbox)
        waitFor { service.state.value.peers.single().connected }
        val songs = listOf("Album/02.flac", "04.m4a").map { name ->
            LibraryMapper.toTrack(ScannedFile(path = TestMedia.file(name).toString(), sizeBytes = Files.size(TestMedia.file(name)), modifiedMs = 0, title = name))
        }
        mainThread.submit { service.sendFiles("phone-t", songs) }.get()
        waitFor { inbox.any { it is LinkMessage.TransferOfferMessage } }
        val offer = (inbox.first { it is LinkMessage.TransferOfferMessage } as LinkMessage.TransferOfferMessage).offer
        assertEquals(listOf("02.flac", "04.m4a"), offer.files.map { it.name })
        // The phone accepts and dials to fetch the files.
        session.send(LinkMessage.TransferAnswer(offer.id, true))
        waitFor { service.transfers!!.jobs.value.single().status == TransferStatus.WaitingConnection }
        val got = mutableMapOf<Int, ByteArray>()
        val staging = temp.newFolder("phone-staging")
        data(pc, ChannelHello.Data(offer.id, senderIsDialer = false)) { link ->
            TransferChannel.receive(link, offer, StagingSink(staging, offer) { i, f -> got[i] = f.readBytes(); null }, {}, { false })
        }
        waitFor { service.transfers!!.jobs.value.single().status == TransferStatus.Done }
        assertArrayEquals(Files.readAllBytes(TestMedia.file("Album/02.flac")), got[0])
        assertArrayEquals(Files.readAllBytes(TestMedia.file("04.m4a")), got[1])
        session.disconnect()
    }

    @Test
    fun anotherPairedDeviceCannotHijackATransfer() {
        start()
        val pc = pair()
        val inbox = CopyOnWriteArrayList<LinkMessage>()
        val session = control(pc, inbox)
        waitFor { service.state.value.peers.single().connected }
        val song = LibraryMapper.toTrack(ScannedFile(path = TestMedia.file("03.ogg").toString(), sizeBytes = Files.size(TestMedia.file("03.ogg")), modifiedMs = 0))
        mainThread.submit { service.sendFiles("phone-t", listOf(song)) }.get()
        waitFor { inbox.any { it is LinkMessage.TransferOfferMessage } }
        val offer = (inbox.first { it is LinkMessage.TransferOfferMessage } as LinkMessage.TransferOfferMessage).offer
        session.send(LinkMessage.TransferAnswer(offer.id, true))
        // A data connection claiming to send (wrong direction) gets nothing and changes nothing.
        val result = runCatching {
            data(pc, ChannelHello.Data(offer.id, senderIsDialer = true)) { link ->
                TransferChannel.send(link, offer, { _, _ -> "x".byteInputStream() }, {}, { false })
            }
        }
        assertTrue(result.isFailure)
        assertEquals(TransferStatus.WaitingConnection, service.transfers!!.jobs.value.single().status)
        session.disconnect()
    }
}
