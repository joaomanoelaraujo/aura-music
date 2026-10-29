package com.aura.music.feature.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.aura.music.core.model.Track
import com.aura.music.data.media.LibraryIndex
import com.aura.music.data.repository.FavoritesRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.repository.LibraryState
import com.aura.music.data.repository.MixKind
import com.aura.music.data.repository.SmartMixes
import com.aura.music.navigation.CollectionKind
import com.aura.music.navigation.CollectionRoute
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CollectionUiState(
    val isLoading: Boolean = true,
    val kind: CollectionKind = CollectionKind.Favorites,
    val key: String = "",
    val tracks: List<Track> = emptyList(),
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
)

/** Track lists that aren't albums, artists or playlists: favorites, a genre or a folder. */
@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    favorites: FavoritesRepository,
    mixes: SmartMixes,
    private val player: PlayerConnection,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<CollectionRoute>()

    private val tracks: Flow<List<Track>> = when (route.kind) {
        CollectionKind.Favorites -> favorites.favoriteTracks
        CollectionKind.Genre -> library.index.map { it.genreTracks(route.key) }
        CollectionKind.Folder -> library.index.map { it.folderTracks(route.key) }
        CollectionKind.Mix -> mixes.tracks(MixKind.valueOf(route.key))
    }

    val state: StateFlow<CollectionUiState> = combine(library.state, tracks, player.state) { libraryState, tracks, playerState ->
        CollectionUiState(
            isLoading = libraryState !is LibraryState.Ready,
            kind = route.kind,
            key = route.key,
            tracks = tracks,
            currentTrackId = playerState.current?.id,
            isPlaying = playerState.isPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState(kind = route.kind, key = route.key))

    val title: String? = when (route.kind) {
        CollectionKind.Favorites -> null
        CollectionKind.Genre -> route.key
        CollectionKind.Folder -> LibraryIndex.folderName(route.key)
        CollectionKind.Mix -> null
    }

    /** Which automatic mix this is, for [CollectionKind.Mix]. */
    val mix: MixKind? = if (route.kind == CollectionKind.Mix) MixKind.valueOf(route.key) else null

    fun play(index: Int) = player.play(state.value.tracks, index)

    fun shuffle() = player.play(state.value.tracks, shuffle = true)
}
