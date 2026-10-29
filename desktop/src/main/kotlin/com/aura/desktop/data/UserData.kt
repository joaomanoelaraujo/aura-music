package com.aura.desktop.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.UUID

/** A playlist keeps file paths, so it survives rescans and shows songs whose file went missing. */
@Serializable
data class StoredPlaylist(
    val id: String,
    val name: String,
    val paths: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class PlayStat(val plays: Int = 0, val lastPlayed: Long = 0)

/** Everything the user builds in the app: favourites, playlists and listening counts (all local). */
@Serializable
data class UserData(
    val favorites: List<String> = emptyList(),
    val playlists: List<StoredPlaylist> = emptyList(),
    val stats: Map<String, PlayStat> = emptyMap(),
)

/** Where playback was, to pick up again on the next start. */
@Serializable
data class PlaybackSession(
    val paths: List<String> = emptyList(),
    val index: Int = -1,
    val positionMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: String = "Off",
)

/**
 * A JSON-backed value with debounced, atomic saves on a background thread, plus [flush] for exit.
 */
open class JsonStore<T>(
    private val scope: CoroutineScope,
    private val path: Path,
    private val serializer: KSerializer<T>,
    initial: T,
    private val debounceMs: Long = 400,
) {
    private val _value = MutableStateFlow(JsonFiles.read(path, serializer) ?: initial)
    val value: StateFlow<T> = _value.asStateFlow()
    private var pending: Job? = null
    private val lock = Any()

    fun update(transform: (T) -> T) {
        synchronized(lock) { _value.value = transform(_value.value) }
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(debounceMs)
            save()
        }
    }

    fun flush() {
        pending?.cancel()
        save()
    }

    private fun save() {
        runCatching { JsonFiles.write(path, serializer, _value.value) }
    }
}

class UserDataStore(scope: CoroutineScope, path: Path = AppPaths.root.resolve("user.json")) :
    JsonStore<UserData>(scope, path, UserData.serializer(), UserData()) {

    fun isFavorite(path: String) = path in value.value.favorites

    fun toggleFavorite(path: String) = update { d ->
        d.copy(favorites = if (path in d.favorites) d.favorites - path else listOf(path) + d.favorites)
    }

    fun createPlaylist(name: String, paths: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        update { d -> d.copy(playlists = d.playlists + StoredPlaylist(id, uniqueName(d, name), paths, now, now)) }
        return id
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun deletePlaylist(id: String) = update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }

    /** Adds [paths] at the end, skipping songs already in the playlist. Returns how many were added. */
    fun addToPlaylist(id: String, paths: List<String>): Int {
        var added = 0
        editPlaylist(id) { p ->
            val fresh = paths.distinct().filterNot { it in p.paths }
            added = fresh.size
            p.copy(paths = p.paths + fresh)
        }
        return added
    }

    fun removeFromPlaylist(id: String, position: Int) = editPlaylist(id) { p ->
        if (position !in p.paths.indices) p else p.copy(paths = p.paths.toMutableList().apply { removeAt(position) })
    }

    fun moveInPlaylist(id: String, from: Int, to: Int) = editPlaylist(id) { p ->
        if (from !in p.paths.indices || to !in p.paths.indices) p
        else p.copy(paths = p.paths.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun recordPlay(path: String, at: Long = System.currentTimeMillis()) = update { d ->
        val s = d.stats[path] ?: PlayStat()
        d.copy(stats = d.stats + (path to PlayStat(s.plays + 1, at)))
    }

    private fun editPlaylist(id: String, transform: (StoredPlaylist) -> StoredPlaylist) = update { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }

    private fun uniqueName(d: UserData, wanted: String): String {
        val base = wanted.trim().ifEmpty { "Nova playlist" }
        val taken = d.playlists.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        return (2..999).map { "$base $it" }.first { it.lowercase() !in taken }
    }
}

class SessionStore(scope: CoroutineScope, path: Path = AppPaths.root.resolve("session.json")) :
    JsonStore<PlaybackSession>(scope, path, PlaybackSession.serializer(), PlaybackSession(), debounceMs = 1_500)
