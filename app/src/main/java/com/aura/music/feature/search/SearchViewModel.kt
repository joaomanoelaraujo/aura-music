package com.aura.music.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.core.model.Album
import com.aura.music.core.model.Artist
import com.aura.music.core.model.Playlist
import com.aura.music.core.model.Track
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.repository.FavoritesRepository
import com.aura.music.data.repository.HistoryRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.repository.PlaylistRepository
import com.aura.music.data.repository.SearchHistoryRepository
import com.aura.music.data.search.PersonalSignals
import com.aura.music.data.search.SearchCorpus
import com.aura.music.data.search.SearchFilter
import com.aura.music.data.search.SmartResults
import com.aura.music.feature.common.LibraryStatus
import com.aura.music.feature.common.status
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the search page shows before anything is typed. */
data class SearchDiscover(
    val recentSearches: List<String> = emptyList(),
    val usualTracks: List<Track> = emptyList(),
    val topArtists: List<Artist> = emptyList(),
    val recentAlbums: List<Album> = emptyList(),
    val frequentPlaylists: List<Playlist> = emptyList(),
    val recentlyAdded: List<Track> = emptyList(),
)

data class SearchUiState(
    val status: LibraryStatus = LibraryStatus.Loading,
    val query: String = "",
    val filter: SearchFilter = SearchFilter.All,
    val results: SmartResults = SmartResults(),
    val discover: SearchDiscover = SearchDiscover(),
    val currentTrackId: Long? = null,
    val isPlaying: Boolean = false,
    val personalization: Boolean = true,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val library: LibraryRepository,
    playlists: PlaylistRepository,
    history: HistoryRepository,
    favorites: FavoritesRepository,
    settings: SettingsRepository,
    private val searchHistory: SearchHistoryRepository,
    private val player: PlayerConnection,
) : ViewModel() {

    private val query = savedStateHandle.getStateFlow(KEY_QUERY, "")
    private val filter = savedStateHandle.getStateFlow(KEY_FILTER, SearchFilter.All)

    /** (personalization, search history) switches from settings. */
    private val privacy: StateFlow<Pair<Boolean, Boolean>> = settings.settings
        .map { it.personalization to it.searchHistoryEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true to true)

    /** Rebuilt only when the library or playlists change, never per keystroke. */
    private val corpus: StateFlow<SearchCorpus?> = combine(library.index, playlists.playlists) { index, lists -> index to lists }
        .mapLatest { (index, lists) -> SearchCorpus(index, lists) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val signals: Flow<PersonalSignals> = combine(history.trackStats, favorites.favoriteIds, privacy) { stats, favs, (personal, _) ->
        if (!personal) {
            PersonalSignals.None
        } else {
            PersonalSignals(
                plays = stats.associate { it.trackId to it.plays },
                lastPlayed = stats.associate { it.trackId to it.lastPlayed },
                favorites = favs,
            )
        }
    }

    private val recentSearches: Flow<List<String>> = combine(searchHistory.recent, privacy) { recent, (_, enabled) ->
        if (enabled) recent else emptyList()
    }

    private val results: Flow<SmartResults> = combine(
        query.debounce { if (it.isBlank()) 0L else DEBOUNCE_MS },
        filter,
        corpus,
        signals,
        recentSearches,
    ) { q, f, c, s, recent -> SearchInput(q, f, c, s, recent) }
        .mapLatest { input -> input.corpus?.takeIf { input.query.isNotBlank() }?.search(input.query, input.signals, input.filter, input.recent) ?: SmartResults() }
        .flowOn(Dispatchers.Default)

    private val frequentPlaylists: Flow<List<Playlist>> = combine(
        playlists.playlists,
        playlists.playlistTrackIds,
        history.trackStats,
    ) { lists, ids, stats ->
        val plays = stats.associate { it.trackId to it.plays }
        lists.map { it to ids[it.id].orEmpty().sumOf { id -> plays[id] ?: 0 } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(SHELF)
            .map { it.first }
    }

    private val discover: Flow<SearchDiscover> = combine(
        recentSearches,
        combine(history.topTracks(USUAL), history.topArtists(SHELF), history.recentAlbums(SHELF), ::Triple),
        frequentPlaylists,
        library.index.map { it.recentlyAddedTracks(USUAL) },
        privacy,
    ) { recent, (usual, artists, albums), lists, added, (personal, _) ->
        if (personal) {
            SearchDiscover(recent, usual, artists, albums, lists, added)
        } else {
            SearchDiscover(recentSearches = recent, recentlyAdded = added)
        }
    }

    val state: StateFlow<SearchUiState> = combine(
        combine(library.state.map { it.status() }, query, filter, ::Triple),
        results,
        discover,
        player.state.map { it.current?.id to it.isPlaying }.distinctUntilChanged(),
        privacy,
    ) { (status, q, f), results, discover, (currentId, isPlaying), (personal, _) ->
        SearchUiState(status, q, f, if (q.isBlank()) SmartResults() else results, discover, currentId, isPlaying, personal)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(value: String) {
        savedStateHandle[KEY_QUERY] = value
    }

    fun setFilter(value: SearchFilter) {
        savedStateHandle[KEY_FILTER] = value
    }

    /** Saves the current query to the local history (when enabled) after the user acts on it. */
    fun commitQuery() {
        val q = query.value
        if (q.isBlank()) return
        if (!privacy.value.second) return
        viewModelScope.launch { searchHistory.record(q) }
    }

    fun removeRecent(text: String) = viewModelScope.launch { searchHistory.remove(text) }

    fun clearRecent() = viewModelScope.launch { searchHistory.clear() }

    fun refresh() = library.refresh()

    fun play(tracks: List<Track>, index: Int) {
        commitQuery()
        player.play(tracks, index)
    }

    private data class SearchInput(
        val query: String,
        val filter: SearchFilter,
        val corpus: SearchCorpus?,
        val signals: PersonalSignals,
        val recent: List<String>,
    )

    private companion object {
        const val KEY_QUERY = "query"
        const val KEY_FILTER = "filter"
        const val DEBOUNCE_MS = 60L
        const val SHELF = 10
        const val USUAL = 6
    }
}
