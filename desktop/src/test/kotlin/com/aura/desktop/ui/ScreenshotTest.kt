package com.aura.desktop.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Density
import com.aura.desktop.TestMedia
import com.aura.desktop.audio.AudioEngine
import com.aura.desktop.audio.RealtimeSink
import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.data.SettingsStore
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

    @After
    fun tearDown() {
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
        return AppModel(scope, library, player, settings, ArtworkLoader(store))
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

        listOf("01-empty", "02-songs", "03-playing", "04-albums", "05-album", "06-search", "07-folders-no-queue").forEach {
            assertTrue(Files.size(out.resolve("$it.png")) > 10_000)
        }
    }
}
