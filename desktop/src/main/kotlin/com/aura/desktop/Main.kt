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
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.aura.desktop.audio.AudioEngine
import com.aura.desktop.data.AppPaths
import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.data.SessionStore
import com.aura.desktop.data.SettingsStore
import com.aura.desktop.data.UserDataStore
import com.aura.desktop.lyrics.LyricsService
import androidx.compose.ui.window.WindowPlacement
import com.aura.desktop.library.ArtworkStore
import com.aura.desktop.playback.PlayerController
import com.aura.desktop.ui.AppModel
import com.aura.desktop.ui.ArtworkLoader
import com.aura.desktop.ui.AuraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    val userData = UserDataStore(scope)
    val session = SessionStore(scope)
    val lyrics = LyricsService(AppPaths.root.resolve("lyrics"))
    val linkDir = AppPaths.root.resolve("link")
    val linkDevices = com.aura.desktop.link.PairedDeviceStore(scope, linkDir.resolve("devices.json"))
    val link = runCatching {
        com.aura.desktop.link.LinkService(
            scope, Dispatchers.Main,
            com.aura.desktop.link.LocalIdentityStore(linkDir),
            linkDevices,
            com.aura.desktop.link.DesktopPlayerBridge(player, library, artworkStore),
            appVersion = com.aura.desktop.ui.appVersion(),
            transfers = com.aura.desktop.link.DesktopTransfers(
                scope,
                staging = AppPaths.root.resolve("transfers"),
                receiveDir = { settings.settings.value.receiveFolder?.let(java.nio.file.Path::of) ?: AppPaths.defaultReceiveFolder },
                permissions = { id -> linkDevices.find(id)?.permissions },
                onReceived = { files ->
                    // Received songs join the library: add their folder if it isn't one yet.
                    val dir = files.firstOrNull()?.parent ?: return@DesktopTransfers
                    scope.launch {
                        val inLibrary = settings.settings.value.folders.any { dir.startsWith(java.nio.file.Path.of(it)) }
                        if (inLibrary) library.rescan() else library.addFolder(dir)
                    }
                },
            ),
        )
    }.getOrNull()
    com.aura.music.data.discover.Http.userAgent = "AURA-Desktop/${com.aura.desktop.ui.appVersion()}"
    val downloads = com.aura.desktop.discover.DesktopDownloads(
        scope,
        staging = AppPaths.root.resolve("downloads"),
        folder = { settings.settings.value.downloadFolder?.let(java.nio.file.Path::of) ?: AppPaths.defaultDownloadFolder },
        onSaved = { file, download ->
            scope.launch {
                // Downloads join the library; then, if asked, go to the linked device.
                val dir = file.parent
                val inLibrary = settings.settings.value.folders.any { dir.startsWith(java.nio.file.Path.of(it)) }
                if (inLibrary) library.rescan() else library.addFolder(dir)
                download.sendTo?.let { deviceId ->
                    val track = com.aura.desktop.library.LibraryMapper.toTrack(
                        com.aura.desktop.library.ScannedFile(path = file.toString(), sizeBytes = java.nio.file.Files.size(file), modifiedMs = 0, title = download.title, artist = download.artist),
                    )
                    link?.sendFiles(deviceId, listOf(track))
                }
            }
        },
    )
    val together = com.aura.desktop.together.DesktopTogether(scope, Dispatchers.Main, player, library, artworkStore, AppPaths.root.resolve("together"))
    val model = AppModel(scope, library, player, settings, ArtworkLoader(artworkStore), userData, session, lyrics, link, com.aura.desktop.discover.DesktopDiscover(), downloads, together)
    link?.let { service ->
        service.start()
        // Paired devices see changes as they happen (play/pause, song, queue, seeks).
        scope.launch { player.state.collect { service.publishState() } }
        scope.launch { player.position.collect { service.publishState() } }
    }
    library.start()
    model.restoreSession()
    model.startTracking()
    // The keyboard's media keys control AURA even when another window has focus (Windows).
    com.aura.desktop.system.WindowsIntegration.listenToMediaKeys { key ->
        scope.launch {
            when (key) {
                com.aura.desktop.system.WindowsIntegration.MediaKey.PlayPause -> player.togglePlayPause()
                com.aura.desktop.system.WindowsIntegration.MediaKey.Next -> player.next()
                com.aura.desktop.system.WindowsIntegration.MediaKey.Previous -> player.previous()
                com.aura.desktop.system.WindowsIntegration.MediaKey.Stop -> if (player.state.value.isPlaying) player.togglePlayPause()
            }
        }
    }

    application {
        val saved = settings.settings.value
        val windowState = rememberWindowState(
            size = DpSize(saved.windowWidth.dp, saved.windowHeight.dp),
            position = WindowPosition.PlatformDefault,
            placement = if (saved.windowMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        val searchFocus = remember { FocusRequester() }
        // Tray icon with quick controls (Windows notification area).
        Tray(
            icon = AuraWindowIcon,
            tooltip = "AURA",
            menu = {
                Item("Tocar / pausar", onClick = player::togglePlayPause)
                Item("Próxima", onClick = player::next)
                Item("Anterior", onClick = player::previous)
            },
        )
        Window(
            onCloseRequest = {
                // Remember the window and where playback was, then write everything out.
                val maximized = windowState.placement == WindowPlacement.Maximized
                settings.update {
                    if (maximized) it.copy(windowMaximized = true)
                    else it.copy(windowMaximized = false, windowWidth = windowState.size.width.value.toInt(), windowHeight = windowState.size.height.value.toInt())
                }
                model.saveSession()
                session.flush()
                userData.flush()
                settings.flush()
                link?.stop()
                together.leave()
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
            androidx.compose.runtime.LaunchedEffect(Unit) { com.aura.desktop.system.WindowsIntegration.useDarkTitleBar(window.windowHandle) }
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
