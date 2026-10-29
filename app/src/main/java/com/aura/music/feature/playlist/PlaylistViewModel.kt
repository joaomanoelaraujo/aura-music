package com.aura.music.feature.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.aura.music.core.model.PlaylistEntry
import com.aura.music.data.repository.PlaylistDetail
import com.aura.music.data.repository.PlaylistRepository
import com.aura.music.navigation.PlaylistRoute
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PlaylistUiState {
    data object Loading : PlaylistUiState
    data object NotFound : PlaylistUiState
    data class Ready(
        val detail: PlaylistDetail,
        val currentTrackId: Long?,
        val isPlaying: Boolean,
    ) : PlaylistUiState
}

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PlaylistRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val playlistId = savedStateHandle.toRoute<PlaylistRoute>().playlistId

    val state: StateFlow<PlaylistUiState> = combine(repository.playlist(playlistId), player.state) { detail, playerState ->
        if (detail == null) {
            PlaylistUiState.NotFound
        } else {
            PlaylistUiState.Ready(detail, playerState.current?.id, playerState.isPlaying)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistUiState.Loading)

    private val entries: List<PlaylistEntry>
        get() = (state.value as? PlaylistUiState.Ready)?.detail?.entries.orEmpty()

    fun play(index: Int) = player.play(entries.map { it.track }, index)

    fun shuffle() = player.play(entries.map { it.track }, shuffle = true)

    fun rename(name: String) = viewModelScope.launch { repository.rename(playlistId, name) }

    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        repository.delete(playlistId)
        onDeleted()
    }

    fun remove(entryId: Long) = viewModelScope.launch { repository.removeEntry(playlistId, entryId) }

    fun reorder(orderedEntryIds: List<Long>) = viewModelScope.launch { repository.reorder(playlistId, orderedEntryIds) }
}
