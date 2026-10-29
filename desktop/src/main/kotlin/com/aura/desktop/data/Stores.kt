package com.aura.desktop.data

import com.aura.desktop.library.ArtworkStore
import com.aura.desktop.library.LibraryMapper
import com.aura.desktop.library.LibraryScanner
import com.aura.desktop.library.ScanProgress
import com.aura.desktop.library.ScannedFile
import com.aura.music.data.media.LibraryIndex
import com.aura.music.data.search.SearchCorpus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

/** User settings, saved on every change (off the UI thread). */
class SettingsStore(private val scope: CoroutineScope, private val path: Path = AppPaths.settings) {
    private val _settings = MutableStateFlow(JsonFiles.read(path, Settings.serializer()) ?: Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = _settings.updateAndGet(transform)
        scope.launch(Dispatchers.IO) { runCatching { JsonFiles.write(path, Settings.serializer(), next) } }
    }

    private inline fun <T> MutableStateFlow<T>.updateAndGet(transform: (T) -> T): T {
        while (true) {
            val prev = value
            val next = transform(prev)
            if (compareAndSet(prev, next)) return next
        }
    }
}

/**
 * The library the UI sees. Starts instantly from the last saved scan, then rescans the chosen
 * folders in the background. Folders that are currently unreachable (an unplugged drive) keep
 * their songs in the saved scan but are left out of the library until they come back.
 */
class LibraryRepository(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val artwork: ArtworkStore,
    private val scanner: LibraryScanner = LibraryScanner(artwork),
    private val snapshotPath: Path = AppPaths.library,
) {
    data class State(
        val index: LibraryIndex = LibraryIndex(emptyList()),
        val corpus: SearchCorpus = SearchCorpus(LibraryIndex(emptyList()), emptyList()),
        val folders: List<String> = emptyList(),
        val unavailableFolders: Set<String> = emptySet(),
        /** Non-null while a scan runs. */
        val scanning: ScanProgress? = null,
        /** False until the saved scan has been read. */
        val loaded: Boolean = false,
    )

    private val _state = MutableStateFlow(State(folders = settings.settings.value.folders))
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var files: List<ScannedFile> = emptyList()
    private var scanJob: Job? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            files = JsonFiles.read(snapshotPath, LibrarySnapshot.serializer())
                ?.takeIf { it.version == LibrarySnapshot.VERSION }?.files.orEmpty()
            publish(files, scanning = null)
            rescan()
        }
    }

    fun addFolder(folder: Path) {
        val path = folder.toAbsolutePath().normalize().toString()
        settings.update { s -> if (path in s.folders) s else s.copy(folders = s.folders + path) }
        rescan()
    }

    fun removeFolder(path: String) {
        settings.update { s -> s.copy(folders = s.folders - path) }
        files = files.filterNot { isInside(it.path, path) }
        publish(files, scanning = null)
        persist()
        rescan()
    }

    fun rescan() {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            val job = coroutineContext[Job]
            val folders = settings.settings.value.folders
            val reachable = folders.filter { Files.isDirectory(Path.of(it)) }
            val previous = files.associateBy { it.path }
            val scanned = scanner.scan(
                folders = reachable.map(Path::of),
                previous = previous,
                onProgress = { p -> _state.update { it.copy(scanning = p) } },
                isCancelled = { job?.isActive == false },
            )
            if (job?.isActive == false) return@launch
            // Keep what we knew about unreachable folders, so nothing is lost when a drive is unplugged.
            val unreachable = folders - reachable.toSet()
            val kept = files.filter { f -> unreachable.any { isInside(f.path, it) } }
            files = scanned + kept
            publish(files, scanning = null)
            persist()
        }
    }

    private fun publish(all: List<ScannedFile>, scanning: ScanProgress?) {
        val folders = settings.settings.value.folders
        val unreachable = folders.filterNot { Files.isDirectory(Path.of(it)) }.toSet()
        val visible = all.filter { f -> folders.any { isInside(f.path, it) } && unreachable.none { isInside(f.path, it) } }
        val index = LibraryIndex(visible.map(LibraryMapper::toTrack))
        _state.value = State(
            index = index,
            corpus = SearchCorpus(index, emptyList()),
            folders = folders,
            unavailableFolders = unreachable,
            scanning = scanning,
            loaded = true,
        )
    }

    private fun persist() {
        runCatching { JsonFiles.write(snapshotPath, LibrarySnapshot.serializer(), LibrarySnapshot(files = files)) }
    }

    private fun isInside(file: String, folder: String): Boolean {
        val f = file.replace('\\', '/')
        val d = folder.replace('\\', '/').trimEnd('/') + "/"
        return f.startsWith(d, ignoreCase = System.getProperty("os.name").lowercase().contains("win"))
    }
}
