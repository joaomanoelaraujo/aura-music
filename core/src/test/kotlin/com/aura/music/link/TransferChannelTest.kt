package com.aura.music.link

import com.aura.music.link.crypto.LinkSecurityException
import com.aura.music.link.crypto.SoftwareIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class TransferChannelTest {

    @get:Rule val temp = TemporaryFolder()

    private val a = LocalDevice("a", "A", Platform.Windows, SoftwareIdentity.generate(), emptyList())
    private val b = LocalDevice("b", "B", Platform.Android, SoftwareIdentity.generate(), emptyList())
    private fun paired(other: LocalDevice) = PairedDevice(other.deviceId, other.name, other.platform, Base64.getEncoder().encodeToString(other.identity.publicKey), 0)

    private fun <S, C> linked(server: (AuthenticatedLink) -> S, client: (AuthenticatedLink) -> C): Pair<Result<S>, Result<C>> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val serverResult = CompletableFuture.supplyAsync {
                listener.accept().use { s ->
                    runCatching { server(Handshake.accept(FrameStream(s.getInputStream(), s.getOutputStream()), a, { paired(b) })) }
                }
            }
            val clientResult = Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { s ->
                s.soTimeout = 15_000
                runCatching { client(Handshake.connect(FrameStream(s.getInputStream(), s.getOutputStream()), b, paired(a))) }
            }
            return serverResult.get(20, TimeUnit.SECONDS) to clientResult
        }
    }

    private fun offerFor(vararg files: Pair<String, ByteArray>) = TransferOffer(
        "transfer-1234",
        files.map { (name, bytes) -> TransferFile(name, bytes.size.toLong(), TransferChannel.sha256(ByteArrayInputStream(bytes))) },
    )

    private fun source(vararg contents: ByteArray) = TransferSource { i, offset -> ByteArrayInputStream(contents[i], offset.toInt(), contents[i].size - offset.toInt()) }

    @Test
    fun transfersSeveralFilesAndPublishesOnlyVerifiedOnes() {
        val one = Random(1).nextBytes(400_000)
        val two = Random(2).nextBytes(10)
        val offer = offerFor("um.mp3" to one, "dois.flac" to two)
        val published = mutableMapOf<Int, ByteArray>()
        val sink = StagingSink(temp.newFolder("staging"), offer) { i, f -> published[i] = f.readBytes(); f.delete(); null }
        val (sent, received) = linked(
            server = { link -> TransferChannel.send(link, offer, source(one, two), {}, { false }) },
            client = { link -> TransferChannel.receive(link, offer, sink, {}, { false }) },
        )
        sent.getOrThrow()
        received.getOrThrow()
        assertArrayEquals(one, published[0])
        assertArrayEquals(two, published[1])
    }

    @Test
    fun anInterruptedFileResumesWhereItStopped() {
        val data = Random(3).nextBytes(1_000_000)
        val offer = offerFor("longo.flac" to data)
        val staging = temp.newFolder("staging")
        // First attempt: the receiver gives up after ~300 KB.
        var got = 0L
        val first = StagingSink(staging, offer) { _, _ -> error("must not publish a partial file") }
        linked(
            server = { link -> runCatching { TransferChannel.send(link, offer, source(data), {}, { false }) } },
            client = { link -> TransferChannel.receive(link, offer, first, { got = it.fileBytes }, { got > 300_000 }) },
        )
        val partial = first.received(0)
        assertTrue("kept $partial bytes", partial in 300_000 until data.size)

        // Second attempt continues from there; the sender is asked only for the rest.
        var resumedFrom = -1L
        var result: ByteArray? = null
        val second = StagingSink(staging, offer) { _, f -> result = f.readBytes(); null }
        val (sent, received) = linked(
            server = { link ->
                TransferChannel.send(link, offer, { i, offset -> resumedFrom = offset; source(data).open(i, offset) }, {}, { false })
            },
            client = { link -> TransferChannel.receive(link, offer, second, {}, { false }) },
        )
        sent.getOrThrow()
        received.getOrThrow()
        assertEquals(partial, resumedFrom)
        assertArrayEquals(data, result)
    }

    @Test
    fun aCorruptedCopyIsNeverPublished() {
        val real = Random(4).nextBytes(50_000)
        val lie = real.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val offer = offerFor("musica.mp3" to real) // hash of the real file…
        var published = false
        val staging = temp.newFolder("staging")
        val sink = StagingSink(staging, offer) { _, _ -> published = true; null }
        val (_, received) = linked(
            server = { link -> runCatching { TransferChannel.send(link, offer, source(lie), {}, { false }) } }, // …bytes of another
            client = { link -> TransferChannel.receive(link, offer, sink, {}, { false }) },
        )
        assertTrue(received.exceptionOrNull() is TransferAbortedException)
        assertFalse(published)
        assertFalse(sink.part(0).exists())
    }

    @Test
    fun aSenderCannotSendMoreThanDeclared() {
        val real = ByteArray(1000)
        val offer = TransferOffer("transfer-5678", listOf(TransferFile("x.mp3", 10, TransferChannel.sha256(ByteArrayInputStream(real.copyOf(10))))))
        val sink = StagingSink(temp.newFolder("staging"), offer) { _, _ -> null }
        val (_, received) = linked(
            server = { link ->
                runCatching {
                    // A dishonest sender: chunks beyond the declared size.
                    val dishonest = offer.copy(files = listOf(offer.files[0].copy(sizeBytes = 1000)))
                    TransferChannel.send(link, dishonest, source(real), {}, { false })
                }
            },
            client = { link -> TransferChannel.receive(link, offer, sink, {}, { false }) },
        )
        assertTrue(received.exceptionOrNull() is LinkSecurityException)
    }

    @Test
    fun namesAreSanitized() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.mp3", FileNames.sanitize("C:\\Windows\\System32\\evil.mp3"))
        assertEquals("", FileNames.sanitize(".."))
        assertEquals("_CON.mp3", FileNames.sanitize("CON.mp3"))
        assertEquals("Música boa.mp3", FileNames.sanitize("Música: boa?.mp3".replace(":", "").replace("?", "")))
        assertEquals("ab.mp3", FileNames.sanitize("a\u0000b.mp3"))
        assertEquals("song (2).mp3", FileNames.numbered("song.mp3", 2))
    }

    @Test
    fun offersAreValidated() {
        val ok = TransferOffer("transfer-1234", listOf(TransferFile("a.mp3", 1, "0".repeat(64))))
        ok.validate()
        val bad = listOf(
            ok.copy(id = "../x"),
            ok.copy(files = emptyList()),
            ok.copy(files = listOf(ok.files[0].copy(sha256 = "xyz"))),
            ok.copy(files = listOf(ok.files[0].copy(sizeBytes = TransferLimits.MAX_FILE_BYTES + 1))),
            ok.copy(files = listOf(ok.files[0].copy(name = "/"))),
        )
        bad.forEach { offer -> assertTrue(runCatching { offer.validate() }.isFailure) }
    }

    @Test
    fun cleanupRemovesPartialFiles() {
        val offer = offerFor("a.mp3" to ByteArray(5))
        val sink = StagingSink(temp.newFolder("staging"), offer) { _, _ -> null }
        sink.append(0, byteArrayOf(1, 2), 2)
        assertTrue(sink.part(0).exists())
        sink.cleanup()
        assertFalse(sink.part(0).exists())
    }

    private fun TransferSink.received(file: File) = file.length()
}
