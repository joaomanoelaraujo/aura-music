package com.aura.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.aura.desktop.audio.AudioEngine
import com.aura.desktop.data.AppPaths
import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.data.SettingsStore
import com.aura.desktop.library.ArtworkStore
import com.aura.desktop.playback.PlayerController
import com.aura.desktop.ui.AppModel
import com.aura.desktop.ui.ArtworkLoader
import com.aura.desktop.ui.AuraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Dimension
import javax.swing.JFileChooser
import javax.swing.UIManager

fun main(args: Array<String>) {
    if ("--self-test" in args) {
        kotlin.system.exitProcess(SelfTest.run())
    }
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settings = SettingsStore(scope)
    val artworkStore = ArtworkStore(AppPaths.artwork)
    val library = LibraryRepository(scope, settings, artworkStore)
    val engine = AudioEngine()
    val player = PlayerController(engine, scope, Dispatchers.Main, settings.settings.value.volume)
    val model = AppModel(scope, library, player, settings, ArtworkLoader(artworkStore))
    library.start()

    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp), position = WindowPosition.PlatformDefault)
        val searchFocus = remember { FocusRequester() }
        Window(
            onCloseRequest = {
                engine.close()
                scope.cancel()
                exitApplication()
            },
            state = windowState,
            title = "AURA",
            icon = AuraWindowIcon,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown) return@Window false
                val typing = model.searchFocused
                when {
                    event.isCtrlPressed && event.key == Key.F -> { searchFocus.requestFocus(); true }
                    event.key == Key.Escape && typing -> { model.query = ""; true }
                    typing -> false
                    event.key == Key.Spacebar -> { player.togglePlayPause(); true }
                    event.isCtrlPressed && event.key == Key.DirectionRight -> { player.next(); true }
                    event.isCtrlPressed && event.key == Key.DirectionLeft -> { player.previous(); true }
                    event.isCtrlPressed && event.key == Key.DirectionUp -> { player.setVolume(player.state.value.volume + 0.05f); true }
                    event.isCtrlPressed && event.key == Key.DirectionDown -> { player.setVolume(player.state.value.volume - 0.05f); true }
                    event.isAltPressed && event.key == Key.DirectionLeft -> { model.back(); true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = Dimension(980, 620)
            AuraApp(model, searchFocus, onAddFolder = { chooseFolder(window)?.let(model::addFolder) })
        }
    }
}

/** Native-looking folder picker (Swing with the system look and feel). */
private fun chooseFolder(parent: java.awt.Component): java.io.File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Escolha uma pasta de músicas"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
        approveButtonText = "Usar esta pasta"
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
