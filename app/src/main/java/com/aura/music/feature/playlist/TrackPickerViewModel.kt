package com.aura.music.feature.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.aura.music.core.model.Track
import com.aura.music.data.search.SearchCorpus
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.repository.PlaylistRepository
import com.aura.music.navigation.TrackPickerRoute
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrackPickerUiState(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val selected: Set<Long> = emptySet(),
)

@HiltViewModel
class TrackPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    val playlistId: Long? = savedStateHandle.toRoute<TrackPickerRoute>().playlistId

    private val query = MutableStateFlow("")
    private val selection = MutableStateFlow<Set<Long>>(linkedSetOf())

    private val corpus = library.index.map { SearchCorpus(it, emptyList()) }.flowOn(Dispatchers.Default)

    private val filtered = combine(corpus, query) { corpus, raw -> corpus.filterTracks(raw) }.flowOn(Dispatchers.Default)

    val state: StateFlow<TrackPickerUiState> = combine(query, filtered, selection) { q, tracks, selected ->
        TrackPickerUiState(q, tracks, selected)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackPickerUiState())

    private val allTracks = library.index.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggle(trackId: Long) = selection.update { current ->
        LinkedHashSet(current).apply { if (!add(trackId)) remove(trackId) }
    }

    /** Adds the selection, in the order it was picked. Reports how many tracks were actually added. */
    fun confirm(onDone: (Int) -> Unit) {
        val index = allTracks.value ?: return
        val tracks = index.tracks(selection.value.toList())
        if (tracks.isEmpty()) return
        val target = playlistId
        if (target == null) {
            player.addToQueue(tracks)
            onDone(tracks.size)
        } else {
            viewModelScope.launch { onDone(playlists.addTracks(target, tracks.map { it.id })) }
        }
    }
}
