package com.aura.music.feature.album

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.aura.music.core.model.Album
import com.aura.music.core.model.Track
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.repository.LibraryState
import com.aura.music.navigation.AlbumRoute
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface AlbumUiState {
    data object Loading : AlbumUiState
    data object NotFound : AlbumUiState
    data class Ready(
        val album: Album,
        val tracks: List<Track>,
        val hasMultipleDiscs: Boolean,
        val currentTrackId: Long?,
        val isPlaying: Boolean,
    ) : AlbumUiState
}

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private val albumId = savedStateHandle.toRoute<AlbumRoute>().albumId

    val state: StateFlow<AlbumUiState> = combine(library.state, player.state) { libraryState, playerState ->
        val index = (libraryState as? LibraryState.Ready)?.index ?: return@combine AlbumUiState.Loading
        val album = index.album(albumId) ?: return@combine AlbumUiState.NotFound
        val tracks = index.albumTracks(albumId)
        AlbumUiState.Ready(
            album = album,
            tracks = tracks,
            hasMultipleDiscs = tracks.distinctBy { it.discNumber }.size > 1,
            currentTrackId = playerState.current?.id,
            isPlaying = playerState.isPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumUiState.Loading)

    fun play(tracks: List<Track>, index: Int) = player.play(tracks, index)

    fun shuffle(tracks: List<Track>) = player.play(tracks, shuffle = true)
}
