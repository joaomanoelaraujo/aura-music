package com.aura.desktop.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Density
import com.aura.desktop.TestMedia
import com.aura.desktop.audio.AudioEngine
import com.aura.desktop.audio.RealtimeSink
import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.data.SessionStore
import com.aura.desktop.data.SettingsStore
import com.aura.desktop.data.UserDataStore
import com.aura.desktop.lyrics.LyricsService
import com.aura.desktop.library.ArtworkStore
import com.aura.desktop.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors

/**
 * Renders the whole window off-screen with a real library (the test media) and a silent audio
 * sink, and saves PNGs to build/screenshots for review. Also proves every screen composes.
 */
class ScreenshotTest {

    @get:Rule val temp = TemporaryFolder()

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main") }
    private val dispatcher = thread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val engine = AudioEngine(RealtimeSink(speed = 0.05))
    private val out: Path = Paths.get("build", "screenshots").also { Files.createDirectories(it) }

    private var link: com.aura.desktop.link.LinkService? = null
    private var together: com.aura.desktop.together.DesktopTogether? = null

    @After
    fun tearDown() {
        link?.stop()
        together?.leave()
        scope.cancel()
        engine.close()
        thread.shutdownNow()
    }

    private fun model(): AppModel {
        val dir = temp.newFolder().toPath()
        val settings = SettingsStore(scope, dir.resolve("settings.json"))
        val store = ArtworkStore(dir.resolve("art"))
        val library = LibraryRepository(scope, settings, store, snapshotPath = dir.resolve("library.json"))
        val player = PlayerController(engine, scope, dispatcher, 0.8f)
        link = com.aura.desktop.link.LinkService(
            scope, dispatcher,
            com.aura.desktop.link.LocalIdentityStore(dir.resolve("link")),
            com.aura.desktop.link.PairedDeviceStore(scope, dir.resolve("link/devices.json")),
            com.aura.desktop.link.DesktopPlayerBridge(player, library, store),
            appVersion = "test", preferredPort = 0, advertise = false,
        )
        return AppModel(
            scope, library, player, settings, ArtworkLoader(store),
            UserDataStore(scope, dir.resolve("user.json")), SessionStore(scope, dir.resolve("session.json")),
            LyricsService(dir.resolve("lyrics")), link,
            together = com.aura.desktop.together.DesktopTogether(scope, dispatcher, player, library, store, dir.resolve("together"), relayUrl = null, localAddress = { "192.168.0.10" }).also { together = it },
        )
    }

    private fun shoot(model: AppModel, name: String, width: Int = 1440, height: Int = 900, settle: Long = 600) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            AuraApp(model, remember { FocusRequester() }, onAddFolder = {})
        }
        try {
            // Let flows, image loads and animations settle between frames.
            var time = 0L
            repeat(12) {
                scene.render(time)
                time += 100_000_000L
                Thread.sleep(settle / 12)
            }
            val image = scene.render(time)
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            Files.write(out.resolve("$name.png"), png)
        } finally {
            scene.close()
        }
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out" }
            Thread.sleep(25)
        }
    }

    private fun onMain(block: () -> Unit) = thread.submit(block).get()

    @Volatile private var pcSeenByPhone: com.aura.music.link.PairedDevice? = null

    /** What the simulated phone says it plays (invented titles). */
    private fun phoneState(): com.aura.music.link.RemotePlayerState {
        val songs = listOf("Manhã de Teste", "Linha Imaginária", "Trilha de Exemplo").mapIndexed { i, t ->
            com.aura.music.link.RemoteQueueItem(i.toLong() + 1, com.aura.music.link.RemoteTrack("t$i", t, "Artista Fictício", "Disco Inventado", 200_000L + i * 10_000))
        }
        return com.aura.music.link.RemotePlayerState(
            revision = 1, deviceName = "Galaxy de Teste", isPlaying = true, positionMs = 64_000, sampledAt = 0, durationMs = 200_000,
            current = songs[0].track, queue = songs, index = 0, volume = 0.7f,
        )
    }

    @Test
    fun rendersEveryScreen() {
        val model = model()
        model.library.start()
        waitFor { model.library.state.value.loaded }
        shoot(model, "01-empty")

        onMain { model.addFolder(TestMedia.root.toFile()) }
        waitFor { model.library.state.value.index.tracks.size == 6 && model.library.state.value.scanning == null }
        shoot(model, "02-songs")

        val tracks = model.library.state.value.index.tracks
        onMain { model.player.playTracks(tracks, tracks.indexOfFirst { it.title == "Noite de Verão" }) }
        waitFor { model.player.state.value.isPlaying }
        shoot(model, "03-playing", settle = 1_500)

        onMain { model.open(Screen.Albums) }
        shoot(model, "04-albums", settle = 1_200)

        val album = model.library.state.value.index.albums.first { it.title == "Álbum de Teste" }
        onMain { model.open(Screen.Album(album.id)) }
        shoot(model, "05-album", settle = 1_200)

        onMain { model.query = "agua" }
        shoot(model, "06-search", settle = 1_000)

        onMain {
            model.query = ""
            model.open(Screen.Folders)
            model.toggleQueue()
        }
        shoot(model, "07-folders-no-queue", width = 1100, height = 760)

        // Phase 3: favourites, playlists, lyrics, Now Playing, settings and a dialog.
        val lyricsTrack = model.library.state.value.index.tracks.first { it.title == "Água Clara" }
        val lrc = temp.newFile("letra.lrc").also {
            it.writeText("[00:00.20]Uma linha inventada para o teste\n[00:01.00]Outra linha que acende depois\n[00:02.00]E a última linha do exemplo\n")
        }
        onMain {
            model.toggleQueue()
            model.toggleFavorite(lyricsTrack)
            model.userData.createPlaylist("Para a estrada", tracks.take(3).map { it.uri })
            model.open(Screen.Favorites)
        }
        kotlinx.coroutines.runBlocking { model.lyrics.import(lyricsTrack, lrc.toPath()) }
        shoot(model, "08-favorites")

        onMain {
            model.player.playTracks(listOf(lyricsTrack), 0)
            model.settings.update { it.copy(sidePanel = "lyrics", queuePanelOpen = true) }
            model.open(Screen.Playlist(model.userData.value.value.playlists.first().id))
        }
        waitFor { model.player.state.value.isPlaying }
        shoot(model, "09-playlist-lyrics", settle = 1_500)

        onMain { model.open(Screen.NowPlaying) }
        shoot(model, "10-now-playing", settle = 1_500)

        onMain { model.open(Screen.Settings) }
        shoot(model, "11-settings")

        onMain {
            model.open(Screen.Songs)
            model.dialog = Dialog.AddToPlaylist(listOf(lyricsTrack))
        }
        shoot(model, "12-add-to-playlist")

        // Phase 4: devices, pairing window, pairing confirmation and remote control of a simulated phone.
        val service = link!!
        onMain {
            model.dialog = null
            service.start()
            service.openPairing()
            model.open(Screen.Devices)
        }
        shoot(model, "13-devices-pairing")

        val phone = com.aura.music.link.LocalDevice(
            "phone-x", "Galaxy de Teste", com.aura.music.link.Platform.Android,
            com.aura.music.link.crypto.SoftwareIdentity.generate(), listOf(com.aura.music.link.LinkCapabilities.PLAYER),
        )
        val secret = java.util.Base64.getUrlDecoder().decode(service.state.value.pairing!!.qrPayload.substringAfter("&s="))
        val pairing = Thread {
            runCatching {
                java.net.Socket(java.net.InetAddress.getLoopbackAddress(), service.state.value.port).use { s ->
                    com.aura.music.link.Pairing.initiate(com.aura.music.link.FrameStream(s.getInputStream(), s.getOutputStream()), com.aura.music.link.PairMethod.Qr, secret, phone) { true }
                }
            }.onSuccess { pcSeenByPhone = it }
        }.also { it.start() }
        waitFor { service.state.value.offer != null }
        shoot(model, "14-pairing-confirm")
        service.answerOffer(true)
        pairing.join(10_000)

        // The phone connects and reports what it plays.
        val socket = java.net.Socket(java.net.InetAddress.getLoopbackAddress(), service.state.value.port)
        val authenticated = com.aura.music.link.Handshake.connect(com.aura.music.link.FrameStream(socket.getInputStream(), socket.getOutputStream()), phone, pcSeenByPhone!!)
        com.aura.music.link.Channels.announce(authenticated, com.aura.music.link.ChannelHello.Control)
        val phoneSession = com.aura.music.link.LinkSession(authenticated, onMessage = { s, m ->
            if (m is com.aura.music.link.LinkMessage.StateRequest || m is com.aura.music.link.LinkMessage.Capabilities) {
                runCatching {
                    s.send(com.aura.music.link.LinkMessage.Capabilities("Galaxy de Teste", "test", listOf(com.aura.music.link.LinkCapabilities.PLAYER), com.aura.music.link.LinkPermissions()))
                    s.send(com.aura.music.link.LinkMessage.State(phoneState()))
                }
            }
            if (m is com.aura.music.link.LinkMessage.Search) runCatching { s.send(com.aura.music.link.LinkMessage.SearchResults(m.requestId, phoneState().queue.map { it.track })) }
        }, onClosed = { _, _ -> }, closeTransport = socket::close).also { it.start() }
        waitFor { service.state.value.peers.any { it.connected && it.remoteState != null } }
        onMain { model.open(Screen.Remote("phone-x")) }
        shoot(model, "15-remote", settle = 1_200)
        onMain { model.open(Screen.Devices) }
        shoot(model, "16-devices-paired")
        phoneSession.disconnect()

        // Phase 7: Together idle and hosting.
        onMain { model.open(Screen.Together) }
        shoot(model, "17-together")
        onMain { together!!.startHosting(com.aura.desktop.together.DesktopTogether.HostOptions("Jam de sexta", "PC de Teste")) }
        shoot(model, "18-together-hosting", settle = 1_200)

        listOf(
            "17-together", "18-together-hosting",
            "13-devices-pairing", "14-pairing-confirm", "15-remote", "16-devices-paired",
            "01-empty", "02-songs", "03-playing", "04-albums", "05-album", "06-search", "07-folders-no-queue",
            "08-favorites", "09-playlist-lyrics", "10-now-playing", "11-settings", "12-add-to-playlist",
        ).forEach {
            assertTrue(Files.size(out.resolve("$it.png")) > 10_000)
        }
    }
}
