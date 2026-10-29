package com.aura.desktop.link

import com.aura.desktop.data.LibraryRepository
import com.aura.desktop.library.ArtworkStore
import com.aura.desktop.playback.PlayerController
import com.aura.desktop.playback.Repeat
import com.aura.music.core.model.Track
import com.aura.music.data.search.PersonalSignals
import com.aura.music.link.RemoteAction
import com.aura.music.link.RemotePlayerState
import com.aura.music.link.RemoteQueueItem
import com.aura.music.link.RemoteRepeat
import com.aura.music.link.RemoteTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/** Exposes the desktop player to AURA Link: snapshots of its state and the actions it accepts. */
class DesktopPlayerBridge(
    private val player: PlayerController,
    private val library: LibraryRepository,
    private val artwork: ArtworkStore,
) : LocalPlayerBridge {

    @Volatile private var revision = 0L

    override fun snapshot(deviceName: String, controlledBy: String?): RemotePlayerState {
        val s = player.state.value
        val queue = s.queue
        return RemotePlayerState(
            revision = ++revision,
            deviceName = deviceName,
            isPlaying = s.isPlaying,
            positionMs = player.position.value,
            sampledAt = LinkService.monotonicMs(),
            durationMs = s.durationMs.takeIf { it > 0 } ?: s.current?.durationMs ?: 0,
            current = s.current?.let(::remote),
            // A long queue is trimmed around the current song; that's what a remote shows.
            queue = queue.items.withIndex()
                .filter { (i, _) -> i >= queue.index - 20 && i <= queue.index + 200 }
                .map { (_, item) -> RemoteQueueItem(item.uid, remote(item.track)) },
            index = queue.items.withIndex().filter { (i, _) -> i >= queue.index - 20 && i <= queue.index + 200 }.indexOfFirst { it.index == queue.index },
            shuffle = queue.shuffle,
            repeat = when (queue.repeat) { Repeat.Off -> RemoteRepeat.Off; Repeat.All -> RemoteRepeat.All; Repeat.One -> RemoteRepeat.One },
            volume = s.volume,
            controlledBy = controlledBy,
        )
    }

    override suspend fun apply(action: RemoteAction): Boolean {
        val s = player.state.value
        val queue = s.queue
        fun indexOf(uid: Long) = queue.items.indexOfFirst { it.uid == uid }
        when (action) {
            RemoteAction.Play -> if (!s.isPlaying) player.togglePlayPause()
            RemoteAction.Pause -> if (s.isPlaying) player.togglePlayPause()
            RemoteAction.Next -> player.next()
            RemoteAction.Previous -> player.previous()
            is RemoteAction.Seek -> player.seekTo(action.positionMs.coerceAtLeast(0))
            is RemoteAction.SetVolume -> player.setVolume(action.volume)
            is RemoteAction.SetShuffle -> if (queue.shuffle != action.enabled) player.toggleShuffle()
            is RemoteAction.SetRepeat -> player.setRepeat(
                when (action.mode) { RemoteRepeat.Off -> Repeat.Off; RemoteRepeat.All -> Repeat.All; RemoteRepeat.One -> Repeat.One },
            )
            is RemoteAction.JumpTo -> indexOf(action.uid).takeIf { it >= 0 }?.let(player::jumpTo) ?: return false
            is RemoteAction.Remove -> indexOf(action.uid).takeIf { it >= 0 }?.let(player::removeFromQueue) ?: return false
            is RemoteAction.Move -> {
                val from = indexOf(action.uid).takeIf { it >= 0 } ?: return false
                player.moveInQueue(from, action.toIndex.coerceIn(0, queue.items.lastIndex))
            }
            RemoteAction.ClearUpcoming -> player.clearUpcoming()
            is RemoteAction.PlayTracks -> {
                val tracks = resolve(action.ids).takeIf { it.isNotEmpty() } ?: return false
                player.playTracks(tracks, action.start.coerceIn(0, tracks.lastIndex))
            }
            is RemoteAction.PlayNext -> player.playNext(resolve(action.ids).takeIf { it.isNotEmpty() } ?: return false)
            is RemoteAction.AddToQueue -> player.addToQueue(resolve(action.ids).takeIf { it.isNotEmpty() } ?: return false)
        }
        return true
    }

    override suspend fun search(query: String, limit: Int): List<RemoteTrack> = withContext(Dispatchers.Default) {
        val corpus = library.state.value.corpus
        if (query.isBlank()) library.state.value.index.tracks.take(limit).map(::remote)
        else corpus.search(query, PersonalSignals.None).tracks.take(limit).map(::remote)
    }

    override suspend fun artwork(trackId: String): ByteArray? = withContext(Dispatchers.IO) {
        val track = trackId.toLongOrNull()?.let { library.state.value.index.track(it) } ?: return@withContext null
        val file = artwork.fileFor(track) ?: return@withContext null
        // Covers are stored at most 600 px; shrink again for the wire.
        runCatching { ArtworkStore.shrink(Files.readAllBytes(file), 320) }.getOrNull()
    }

    private fun resolve(ids: List<String>): List<Track> = ids.mapNotNull { id -> id.toLongOrNull()?.let { library.state.value.index.track(it) } }

    private fun remote(track: Track) = RemoteTrack(track.id.toString(), track.title, track.artist, track.album, track.durationMs)
}
