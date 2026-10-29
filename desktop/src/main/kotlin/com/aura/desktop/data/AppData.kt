package com.aura.desktop.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Where AURA keeps its own files (never inside the user's music folders). */
object AppPaths {
    val root: Path by lazy {
        val os = System.getProperty("os.name").lowercase()
        val home = Path.of(System.getProperty("user.home"))
        val base = System.getProperty("aura.dataDir")?.let(Path::of) ?: when {
            os.contains("win") -> System.getenv("APPDATA")?.let(Path::of)?.resolve("AURA") ?: home.resolve("AURA")
            os.contains("mac") -> home.resolve("Library/Application Support/AURA")
            else -> (System.getenv("XDG_DATA_HOME")?.let(Path::of) ?: home.resolve(".local/share")).resolve("aura")
        }
        Files.createDirectories(base)
        base
    }

    val settings: Path get() = root.resolve("settings.json")
    val library: Path get() = root.resolve("library.json")
    val artwork: Path get() = root.resolve("artwork")

    /** Default folder for songs downloaded in Discover. */
    val defaultDownloadFolder: Path get() = Path.of(System.getProperty("user.home"), "Music", "AURA Downloads")

    /** Default folder for songs received from a linked phone. */
    val defaultReceiveFolder: Path get() = Path.of(System.getProperty("user.home"), "Music", "AURA Recebidas")
}

@Serializable
data class Settings(
    val folders: List<String> = emptyList(),
    /** App volume, 0..1 (not the system volume). */
    val volume: Float = 0.8f,
    val queuePanelOpen: Boolean = true,
    /** Which tab the side panel shows: "queue" or "lyrics". */
    val sidePanel: String = "queue",
    /**
     * Look up missing lyrics on LRCLIB. Off until the user turns it on: it sends the song's title,
     * artist, album and duration to an outside service.
     */
    val onlineLyrics: Boolean = false,
    /** Reopen the last queue (paused) when the app starts. */
    val resumeSession: Boolean = true,
    /** Rank equally good search results by what the user plays and favourites (computed locally). */
    val personalizeSearch: Boolean = true,
    val windowWidth: Int = 1280,
    val windowHeight: Int = 820,
    val windowMaximized: Boolean = false,
    /** Where songs received from linked devices are saved (part of the library). Null: the default. */
    val receiveFolder: String? = null,
    /** Where Discover downloads are saved (part of the library). Null: the default. */
    val downloadFolder: String? = null,
)

@Serializable
data class LibrarySnapshot(val version: Int = VERSION, val files: List<com.aura.desktop.library.ScannedFile> = emptyList()) {
    companion object {
        const val VERSION = 1
    }
}

/** Small JSON files written atomically, so a crash mid-write never loses the previous copy. */
object JsonFiles {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun <T> read(path: Path, serializer: KSerializer<T>): T? =
        runCatching { if (Files.exists(path)) json.decodeFromString(serializer, Files.readString(path)) else null }.getOrNull()

    fun <T> write(path: Path, serializer: KSerializer<T>, value: T) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(temp, json.encodeToString(serializer, value))
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
