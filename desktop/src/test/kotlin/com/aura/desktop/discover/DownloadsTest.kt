package com.aura.desktop.discover

import com.aura.desktop.TestMedia
import com.aura.music.data.discover.AudioOption
import com.aura.music.data.discover.DiscoverItem
import com.aura.music.data.discover.DownloadableTrack
import com.aura.music.data.discover.SourceKind
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Downloads against a local HTTP server (no internet): validation, resume, limits, naming. */
class DownloadsTest {

    @get:Rule val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: HttpServer
    private val song: ByteArray = Files.readAllBytes(TestMedia.file("Album/02.flac"))
    private val rangeRequests = CopyOnWriteArrayList<String>()
    private val slowAfter = AtomicInteger(Int.MAX_VALUE)

    /** A valid ~3 MB WAV (silence), built here so the repo needs no big fixture. */
    private val bigWav: ByteArray = run {
        val pcm = 3 * 1024 * 1024
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(44_100); putInt(44_100 * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(pcm)
        }.array()
        header + ByteArray(pcm)
    }

    /** Requests to /big.wav that drop the connection after 1 MB (the first one, when set). */
    private val dropFirst = AtomicInteger(0)

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/song.flac") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) rangeRequests += range
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            ex.responseHeaders.add("Content-Type", "audio/flac")
            ex.sendResponseHeaders(if (start > 0) 206 else 200, (song.size - start).toLong())
            ex.responseBody.use { out ->
                var i = start
                while (i < song.size) {
                    val n = minOf(4096, song.size - i)
                    out.write(song, i, n)
                    out.flush()
                    i += n
                    if (i > slowAfter.get()) Thread.sleep(50)
                }
            }
        }
        server.createContext("/big.wav") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) rangeRequests += range
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            ex.responseHeaders.add("Content-Type", "audio/wav")
            ex.sendResponseHeaders(if (start > 0) 206 else 200, (bigWav.size - start).toLong())
            val drop = dropFirst.getAndSet(0) == 1
            try {
                ex.responseBody.use { out ->
                    var i = start
                    while (i < bigWav.size) {
                        val n = minOf(16 * 1024, bigWav.size - i)
                        out.write(bigWav, i, n)
                        out.flush()
                        i += n
                        if (drop && i - start > 1024 * 1024) {
                            ex.close() // the connection drops mid-file
                            return@use
                        }
                        if (i > slowAfter.get()) Thread.sleep(30)
                    }
                }
            } catch (e: java.io.IOException) {
                // the client hung up (cancel): nothing to do
            }
        }
        server.createContext("/page.html") { ex ->
            val body = "<html>não é música</html>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/fake.mp3") { ex ->
            val body = ByteArray(50_000) { (it % 251).toByte() }
            ex.responseHeaders.add("Content-Type", "audio/mpeg")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        scope.cancel()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun downloads(folder: Path, saved: MutableList<Path> = CopyOnWriteArrayList(), maxBytes: Long = 2L * 1024 * 1024 * 1024) = DesktopDownloads(
        scope, staging = temp.newFolder().toPath(), folder = { folder }, onSaved = { p, _ -> saved.add(p) }, maxBytes = maxBytes,
        open = { u -> URL(u).openConnection() as HttpURLConnection }, requireHttps = false,
    )

    private fun enqueue(d: DesktopDownloads, path: String, size: Long? = null, ext: String = "flac") {
        val option = AudioOption("FLAC", ext, null, true, true, size, url(path), "x.$ext", "audio/flac")
        d.enqueue(DiscoverItem("k", "Faixa Livre", "Artista Aberto", null, SourceKind.DirectLink, "CC BY 4.0", url(path)), DownloadableTrack("Faixa Livre", 1, null, listOf(option)), option, null)
    }

    private fun waitFor(d: DesktopDownloads, condition: (DesktopDownload) -> Boolean): DesktopDownload {
        val end = System.currentTimeMillis() + 15_000
        while (true) {
            d.downloads.value.firstOrNull()?.takeIf(condition)?.let { return it }
            check(System.currentTimeMillis() < end) { "Timed out: ${d.downloads.value}" }
            Thread.sleep(20)
        }
    }

    @Test
    fun downloadsValidatesAndPublishesUnderASafeName() {
        val folder = temp.newFolder("Downloads").toPath()
        val saved = CopyOnWriteArrayList<Path>()
        val d = downloads(folder, saved)
        enqueue(d, "/song.flac", song.size.toLong())
        val done = waitFor(d) { it.status == DownloadStatus.Done }
        val file = folder.resolve("Artista Aberto - Faixa Livre.flac")
        assertEquals(file.toString(), done.savedPath)
        assertArrayEquals(song, Files.readAllBytes(file))
        assertEquals(listOf(file), saved.toList())

        // Same song again: a second file, the first untouched.
        enqueue(d, "/song.flac", song.size.toLong())
        waitFor(d) { it.status == DownloadStatus.Done && it.savedPath != done.savedPath }
        assertTrue(Files.exists(folder.resolve("Artista Aberto - Faixa Livre (2).flac")))
    }

    @Test
    fun aWebPageIsNotSavedAsMusic() {
        val folder = temp.newFolder("Downloads").toPath()
        val d = downloads(folder)
        enqueue(d, "/page.html")
        val failed = waitFor(d) { it.status == DownloadStatus.Failed }
        assertTrue(failed.error!!.contains("áudio"))
        Files.list(folder).use { assertEquals(0, it.count()) }
    }

    @Test
    fun bytesThatAreNotAudioAreRejectedAfterDownloading() {
        val folder = temp.newFolder("Downloads").toPath()
        val d = downloads(folder)
        enqueue(d, "/fake.mp3", ext = "mp3")
        val failed = waitFor(d) { it.status == DownloadStatus.Failed }
        assertTrue(failed.error, failed.error!!.contains("não é um áudio"))
        Files.list(folder).use { assertEquals(0, it.count()) }
    }

    @Test
    fun aDroppedDownloadResumesWithRangeAndEndsIdentical() {
        val folder = temp.newFolder("Downloads").toPath()
        val d = downloads(folder)
        dropFirst.set(1)
        enqueue(d, "/big.wav", bigWav.size.toLong(), ext = "wav")
        val failed = waitFor(d) { it.status == DownloadStatus.Failed }
        assertTrue(failed.error!!.contains("continua de onde parou"))
        d.retry(failed.id)
        val done = waitFor(d) { it.status == DownloadStatus.Done }
        // The retry asked only for the rest of the file…
        assertEquals(1, rangeRequests.size)
        assertTrue(rangeRequests.single().removePrefix("bytes=").substringBefore('-').toInt() > 1024 * 1024)
        // …and the result is byte-for-byte the original.
        assertArrayEquals(bigWav, Files.readAllBytes(Path.of(done.savedPath!!)))
    }

    @Test
    fun aCancelledDownloadLeavesNothing() {
        val folder = temp.newFolder("Downloads").toPath()
        val d = downloads(folder)
        slowAfter.set(0)
        enqueue(d, "/big.wav", bigWav.size.toLong(), ext = "wav")
        val running = waitFor(d) { it.status == DownloadStatus.Running && it.doneBytes > 0 }
        d.cancel(running.id)
        waitFor(d) { it.status == DownloadStatus.Cancelled }
        Thread.sleep(300)
        Files.list(folder).use { assertEquals(0, it.count()) }
        assertEquals(DownloadStatus.Cancelled, d.downloads.value.single().status)
    }

    @Test
    fun filesOverTheLimitAreRefused() {
        val folder = temp.newFolder("Downloads").toPath()
        val d = downloads(folder, maxBytes = 10_000)
        enqueue(d, "/song.flac", song.size.toLong())
        val failed = waitFor(d) { it.status == DownloadStatus.Failed }
        assertTrue(failed.error!!.contains("grande demais"))
        assertFalse(Files.list(folder).use { it.findAny().isPresent })
    }
}
