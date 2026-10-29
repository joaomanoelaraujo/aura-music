package com.aura.music.feature.artist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.aura.music.core.model.Album
import com.aura.music.core.model.Artist
import com.aura.music.core.model.Track
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.repository.LibraryState
import com.aura.music.navigation.ArtistRoute
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface ArtistUiState {
    data object Loading : ArtistUiState
    data object NotFound : ArtistUiState
    data class Ready(
        val artist: Artist,
        val albums: List<Album>,
        val tracks: List<Track>,
        val currentTrackId: Long?,
        val isPlaying: Boolean,
    ) : ArtistUiState
}

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private val artistId = savedStateHandle.toRoute<ArtistRoute>().artistId

    val state: StateFlow<ArtistUiState> = combine(library.state, player.state) { libraryState, playerState ->
        val index = (libraryState as? LibraryState.Ready)?.index ?: return@combine ArtistUiState.Loading
        val artist = index.artist(artistId) ?: return@combine ArtistUiState.NotFound
        ArtistUiState.Ready(
            artist = artist,
            albums = index.artistAlbums(artistId),
            tracks = index.artistTracks(artistId),
            currentTrackId = playerState.current?.id,
            isPlaying = playerState.isPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArtistUiState.Loading)

    fun play(tracks: List<Track>, index: Int) = player.play(tracks, index)

    fun shuffle(tracks: List<Track>) = player.play(tracks, shuffle = true)
}
