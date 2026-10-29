package com.aura.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.data.PlaybackSession
import com.aura.desktop.data.SessionStore
import com.aura.desktop.data.SettingsStore
import com.aura.desktop.data.UserDataStore
import com.aura.desktop.library.LibraryMapper
import com.aura.desktop.library.LibraryScanner
import com.aura.desktop.library.LibraryScanner.Companion.extension
import com.aura.desktop.library.ScannedFile
import com.aura.desktop.library.TagReader
import com.aura.desktop.lyrics.LyricsService
import com.aura.desktop.playback.PlayerController
import com.aura.desktop.playback.Repeat
import com.aura.music.core.model.Track
import com.aura.music.data.search.PersonalSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    data object Favorites : Screen { override val title = "Favoritas" }
    data object Settings : Screen { override val title = "Configurações" }
    data object NowPlaying : Screen { override val title = "Tocando agora" }
    data class Album(val id: Long) : Screen { override val title = "Álbum" }
    data class Artist(val id: Long) : Screen { override val title = "Artista" }
    data class Folder(val path: String) : Screen { override val title = "Pasta" }
    data class Playlist(val id: String) : Screen { override val title = "Playlist" }
    data object Devices : Screen { override val title = "Dispositivos" }
    data object Discover : Screen { override val title = "Descobrir" }
    data object Together : Screen { override val title = "AURA Together" }
    data class Remote(val deviceId: String) : Screen { override val title = "Controle remoto" }

    val isTopLevel: Boolean
        get() = this is Songs || this is Albums || this is Artists || this is Folders || this is Favorites || this is Settings ||
            this is Playlist || this is Devices || this is Discover || this is Together
}

/** A dialog shown over the window. */
sealed interface Dialog {
    data class NewPlaylist(val tracks: List<Track> = emptyList()) : Dialog
    data class RenamePlaylist(val id: String, val current: String) : Dialog
    data class DeletePlaylist(val id: String, val name: String) : Dialog
    data class AddToPlaylist(val tracks: List<Track>) : Dialog
    data class LyricsSearch(val track: Track) : Dialog

    /** [deviceId] null renames this computer. */
    data class RenameDevice(val deviceId: String?, val current: String) : Dialog
    data class RevokeDevice(val deviceId: String, val name: String) : Dialog
}

/** UI state and actions shared by the window's parts. Lives on the UI thread. */
class AppModel(
    private val scope: CoroutineScope,
    val library: LibraryRepository,
    val player: PlayerController,
    val settings: SettingsStore,
    val artwork: ArtworkLoader,
    val userData: UserDataStore,
    val session: SessionStore,
    val lyrics: LyricsService,
    /** AURA Link; null if it couldn't start (the rest of the app works without it). */
    val link: com.aura.desktop.link.LinkService? = null,
    val discover: com.aura.desktop.discover.DesktopDiscover? = null,
    val downloads: com.aura.desktop.discover.DesktopDownloads? = null,
    val together: com.aura.desktop.together.DesktopTogether? = null,
) {
    private val history = mutableStateListOf<Screen>(Screen.Songs)
    val screen: Screen get() = history.last()
    val canGoBack: Boolean get() = history.size > 1

    var query by mutableStateOf("")
    var searchFocused by mutableStateOf(false)
    var dialog by mutableStateOf<Dialog?>(null)

    /** Bumped when saved lyrics change, so the lyrics view reloads. */
    var lyricsVersion by mutableStateOf(0)
        private set

    fun open(screen: Screen) {
        query = ""
        if (screen == this.screen) return
        // Top-level sections start a fresh history; detail pages stack on top.
        if (screen.isTopLevel) history.clear()
        history += screen
    }

    fun back() {
        if (history.size > 1) history.removeAt(history.lastIndex)
    }

    fun toggleQueue() = settings.update { it.copy(queuePanelOpen = !it.queuePanelOpen) }

    /** Opens the side panel on [tab] ("queue" or "lyrics"), or closes it if it's already showing it. */
    fun toggleSidePanel(tab: String) = settings.update {
        if (it.queuePanelOpen && it.sidePanel == tab) it.copy(queuePanelOpen = false) else it.copy(queuePanelOpen = true, sidePanel = tab)
    }

    fun addFolder(folder: File) = library.addFolder(folder.toPath())

    fun downloadFolder(): java.nio.file.Path =
        settings.settings.value.downloadFolder?.let { java.nio.file.Path.of(it) } ?: com.aura.desktop.data.AppPaths.defaultDownloadFolder

    /** Where songs received from linked devices go. */
    fun receiveFolder(): java.nio.file.Path =
        settings.settings.value.receiveFolder?.let { java.nio.file.Path.of(it) } ?: com.aura.desktop.data.AppPaths.defaultReceiveFolder

    // ---------------------------------------------------------------- favourites and playlists

    fun isFavorite(track: Track) = track.uri in userData.value.value.favorites

    fun toggleFavorite(track: Track) {
        val adding = !isFavorite(track)
        userData.toggleFavorite(track.uri)
        player.notify(if (adding) "Adicionada às favoritas" else "Removida das favoritas")
    }

    fun createPlaylist(name: String, tracks: List<Track>) {
        val id = userData.createPlaylist(name, tracks.map { it.uri })
        dialog = null
        player.notify(if (tracks.isEmpty()) "Playlist criada" else "Playlist criada com ${countLabel(tracks.size)}")
        open(Screen.Playlist(id))
    }

    fun addToPlaylist(id: String, tracks: List<Track>) {
        val added = userData.addToPlaylist(id, tracks.map { it.uri })
        dialog = null
        val name = userData.value.value.playlists.firstOrNull { it.id == id }?.name.orEmpty()
        player.notify(
            when {
                added == 0 -> "Já estava em “$name”"
                added == 1 -> "Adicionada a “$name”"
                else -> "$added músicas adicionadas a “$name”"
            },
        )
    }

    fun deletePlaylist(id: String) {
        userData.deletePlaylist(id)
        dialog = null
        if ((screen as? Screen.Playlist)?.id == id) open(Screen.Songs)
    }

    /** Tracks of a playlist in order; songs whose file is missing keep their place. */
    fun playlistTracks(id: String): List<Track> =
        userData.value.value.playlists.firstOrNull { it.id == id }?.paths.orEmpty().map(library::trackFor)

    fun favoriteTracks(): List<Track> = userData.value.value.favorites.map(library::trackFor)

    /** Plays [tracks] from [index], skipping songs whose file isn't available right now. */
    fun playAvailable(tracks: List<Track>, index: Int) {
        val chosen = tracks.getOrNull(index)
        val available = tracks.filter { library.isAvailable(it.uri) }
        if (available.isEmpty()) {
            player.notify("Os arquivos destas músicas não estão disponíveis")
            return
        }
        player.playTracks(available, available.indexOf(chosen).coerceAtLeast(0))
    }

    /** Listening signals for search ranking, computed locally from play counts and favourites. */
    fun searchSignals(): PersonalSignals {
        if (!settings.settings.value.personalizeSearch) return PersonalSignals.None
        val data = userData.value.value
        return PersonalSignals(
            plays = data.stats.mapKeys { LibraryMapper.trackId(it.key) }.mapValues { it.value.plays },
            lastPlayed = data.stats.mapKeys { LibraryMapper.trackId(it.key) }.mapValues { it.value.lastPlayed },
            favorites = data.favorites.map(LibraryMapper::trackId).toSet(),
        )
    }

    // ---------------------------------------------------------------- lyrics

    fun lyricsChanged() {
        lyricsVersion++
    }

    fun importLyrics(track: Track, file: File) {
        scope.launch {
            if (lyrics.import(track, file.toPath())) {
                lyricsChanged()
                player.notify("Letra importada")
            } else {
                player.notify("Esse arquivo não tem uma letra que o AURA entenda")
            }
        }
    }

    fun removeSavedLyrics(track: Track) {
        scope.launch {
            lyrics.removeSaved(track)
            lyricsChanged()
            player.notify("Letra salva removida")
        }
    }

    // ---------------------------------------------------------------- dropping files

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

    // ---------------------------------------------------------------- listening counts and session

    /**
     * Counts a play once a song has played for 30 s (or half of it, if shorter), and keeps the
     * session (queue, position) saved so the next start can pick up where this one stopped.
     */
    fun startTracking() {
        scope.launch {
            var countedUid = -1L
            combine(player.state, player.position) { s, p -> s to p }.collect { (s, p) ->
                val item = s.queue.current ?: return@collect
                val duration = s.durationMs.takeIf { it > 0 } ?: item.track.durationMs
                if (item.uid != countedUid && duration > 0 && p >= minOf(30_000L, duration / 2)) {
                    countedUid = item.uid
                    userData.recordPlay(item.track.uri)
                }
            }
        }
        scope.launch {
            player.state
                .map { Triple(it.queue.items.map { i -> i.uid }, it.queue.index, it.isPlaying) to it.queue.shuffle to it.queue.repeat }
                .distinctUntilChanged()
                .collect { saveSession() }
        }
    }

    fun saveSession() {
        val s = player.state.value
        session.update {
            PlaybackSession(
                paths = s.queue.items.map { it.track.uri },
                index = s.queue.index,
                positionMs = player.position.value,
                shuffle = s.queue.shuffle,
                repeat = s.queue.repeat.name,
            )
        }
    }

    /** Puts the last session's queue back (paused) once the library is known. */
    fun restoreSession() {
        if (!settings.settings.value.resumeSession) return
        val saved = session.value.value
        if (saved.paths.isEmpty() || saved.index < 0) return
        scope.launch {
            library.state.first { it.loaded }
            if (!player.state.value.queue.isEmpty) return@launch
            val current = saved.paths.getOrNull(saved.index)
            val paths = saved.paths.filter(library::isAvailable)
            if (paths.isEmpty()) return@launch
            val index = paths.indexOf(current).let { if (it < 0) 0 else it }
            val position = if (paths.getOrNull(index) == current) saved.positionMs else 0
            player.restore(paths.map(library::trackFor), index, position, saved.shuffle, runCatching { Repeat.valueOf(saved.repeat) }.getOrDefault(Repeat.Off))
        }
    }
}
