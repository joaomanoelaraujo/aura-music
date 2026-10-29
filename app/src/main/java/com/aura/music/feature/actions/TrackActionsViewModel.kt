package com.aura.music.feature.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.core.model.AudioKind
import com.aura.music.core.model.Playlist
import com.aura.music.core.model.Track
import com.aura.music.data.media.ClassificationOverride
import com.aura.music.data.repository.ClassificationRepository
import com.aura.music.data.repository.FavoritesRepository
import com.aura.music.data.repository.PlaylistRepository
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TrackActionsViewModel @Inject constructor(
    private val player: PlayerConnection,
    private val favorites: FavoritesRepository,
    private val playlists: PlaylistRepository,
    private val classification: ClassificationRepository,
) : ViewModel() {

    /** Files whose classification the user changed, so the sheet can offer to undo it. */
    val overrides: StateFlow<Map<Long, ClassificationOverride>> = classification.overrides
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setKind(track: Track, kind: AudioKind) = viewModelScope.launch { classification.setKind(track.id, kind) }

    fun hide(track: Track) = viewModelScope.launch { classification.hide(track.id) }

    fun resetClassification(track: Track) = viewModelScope.launch { classification.reset(track.id) }

    /** Treats every file in this track's folder as music (an exception to automatic detection). */
    fun treatFolderAsMusic(track: Track) = viewModelScope.launch { classification.setFolderRule(track.folder, AudioKind.Music) }

    val favoriteIds: StateFlow<Set<Long>> = favorites.favoriteIds

    val allPlaylists: StateFlow<List<Playlist>> = playlists.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playNext(track: Track) = player.playNext(listOf(track))

    fun addToQueue(track: Track) = player.addToQueue(listOf(track))

    fun toggleFavorite(track: Track, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(favorites.toggle(track.id)) }
    }

    fun addToPlaylist(playlist: Playlist, tracks: List<Track>, onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(playlists.addTracks(playlist.id, tracks.map { it.id })) }
    }

    fun createPlaylist(name: String, tracks: List<Track>, onCreated: () -> Unit) {
        viewModelScope.launch {
            playlists.create(name, tracks.map { it.id })
            onCreated()
        }
    }
}
