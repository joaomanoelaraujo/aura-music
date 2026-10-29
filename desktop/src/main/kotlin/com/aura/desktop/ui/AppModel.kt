package com.aura.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.data.SettingsStore
import com.aura.desktop.library.LibraryMapper
import com.aura.desktop.library.LibraryScanner
import com.aura.desktop.library.LibraryScanner.Companion.extension
import com.aura.desktop.library.ScannedFile
import com.aura.desktop.library.TagReader
import com.aura.desktop.playback.PlayerController
import com.aura.music.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

sealed interface Screen {
    val title: String

    data object Songs : Screen { override val title = "Músicas" }
    data object Albums : Screen { override val title = "Álbuns" }
    data object Artists : Screen { override val title = "Artistas" }
    data object Folders : Screen { override val title = "Pastas" }
    data class Album(val id: Long) : Screen { override val title = "Álbum" }
    data class Artist(val id: Long) : Screen { override val title = "Artista" }
    data class Folder(val path: String) : Screen { override val title = "Pasta" }
}

/** UI state and actions shared by the window's parts. Lives on the UI thread. */
class AppModel(
    private val scope: CoroutineScope,
    val library: LibraryRepository,
    val player: PlayerController,
    val settings: SettingsStore,
    val artwork: ArtworkLoader,
) {
    private val history = mutableStateListOf<Screen>(Screen.Songs)
    val screen: Screen get() = history.last()
    val canGoBack: Boolean get() = history.size > 1

    var query by mutableStateOf("")
    var searchFocused by mutableStateOf(false)

    fun open(screen: Screen) {
        query = ""
        if (screen == this.screen) return
        // Top-level sections start a fresh history; detail pages stack on top.
        if (screen is Screen.Songs || screen is Screen.Albums || screen is Screen.Artists || screen is Screen.Folders) history.clear()
        history += screen
    }

    fun back() {
        if (history.size > 1) history.removeAt(history.lastIndex)
    }

    fun toggleQueue() = settings.update { it.copy(queuePanelOpen = !it.queuePanelOpen) }

    fun addFolder(folder: File) = library.addFolder(folder.toPath())

    /**
     * Files dropped on the window: folders join the library; loose audio files go to the end of
     * the queue (playable right away, without being added to the library).
     */
    fun onDrop(files: List<File>) {
        val folders = files.filter(File::isDirectory)
        folders.forEach(::addFolder)
        val audio = files.filter { it.isFile && it.toPath().extension() in LibraryScanner.AUDIO_EXTENSIONS }
        if (audio.isEmpty()) return
        scope.launch {
            val tracks = withContext(Dispatchers.IO) { audio.mapNotNull { readLoose(it.toPath()) } }
            if (tracks.isNotEmpty()) {
                if (player.state.value.queue.isEmpty) player.playTracks(tracks, 0) else player.addToQueue(tracks)
            }
        }
    }

    private fun readLoose(path: Path): Track? {
        val known = library.state.value.index.track(LibraryMapper.trackId(path.toString()))
        if (known != null) return known
        val tags = runCatching { TagReader.read(path) }.getOrNull() ?: return null
        return LibraryMapper.toTrack(
            ScannedFile(
                path = path.toString(), sizeBytes = Files.size(path), modifiedMs = Files.getLastModifiedTime(path).toMillis(),
                title = tags.title, artist = tags.artist, album = tags.album, albumArtist = tags.albumArtist, genre = tags.genre,
                year = tags.year, trackNumber = tags.trackNumber, discNumber = tags.discNumber, durationMs = tags.durationMs,
                bitrate = tags.bitrate, codec = tags.codec, hasEmbeddedCover = tags.cover != null,
            ),
        )
    }

    /** Opens the folder holding [track] in the system file manager. */
    fun showInFolder(track: Track) {
        val folder = File(track.uri).parentFile ?: return
        scope.launch(Dispatchers.IO) { runCatching { Desktop.getDesktop().open(folder) } }
    }
}
