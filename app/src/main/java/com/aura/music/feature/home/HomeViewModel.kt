package com.aura.music.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.core.model.Album
import com.aura.music.core.model.Artist
import com.aura.music.core.model.HomeSection
import com.aura.music.core.model.HomeSections
import com.aura.music.core.model.Track
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.repository.FavoritesRepository
import com.aura.music.data.repository.HistoryRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.repository.MixSummary
import com.aura.music.data.repository.PlaylistRepository
import com.aura.music.data.repository.SmartMixes
import com.aura.music.feature.common.LibraryStatus
import com.aura.music.feature.common.status
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeUiState(
    val status: LibraryStatus = LibraryStatus.Loading,
    val continueTrack: Track? = null,
    val isPlaying: Boolean = false,
    val recentTracks: List<Track> = emptyList(),
    /** True when [recentTracks] comes from listening history; false when it falls back to newly added files. */
    val recentFromHistory: Boolean = false,
    val recentAlbums: List<Album> = emptyList(),
    val topArtists: List<Artist> = emptyList(),
    val favoritesCount: Int = 0,
    val playlistsCount: Int = 0,
    val recentlyAdded: List<Track> = emptyList(),
    val mixes: List<MixSummary> = emptyList(),
    /** Visible sections in the user's order. */
    val sections: List<HomeSection> = HomeSections.Default.filter { it.visible }.map { it.section },
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val player: PlayerConnection,
    history: HistoryRepository,
    favorites: FavoritesRepository,
    playlists: PlaylistRepository,
    settings: SettingsRepository,
    smartMixes: SmartMixes,
) : ViewModel() {

    private val layout = combine(
        settings.settings.map { prefs -> prefs.homeSections.filter { it.visible }.map { it.section } }.distinctUntilChanged(),
        library.index.map { it.recentlyAddedTracks(SHELF_SIZE) },
    ) { sections, added -> sections to added }

    private val shortcuts = combine(favorites.favoriteTracks, playlists.playlists, smartMixes.available) { favs, lists, mixes ->
        Triple(favs.size, lists.size, mixes)
    }

    private val shelves = combine(
        library.index,
        history.recentTracks(SHELF_SIZE),
        history.topArtists(SHELF_SIZE),
    ) { index, recent, artists ->
        val fallback = recent.isEmpty()
        Shelves(
            recentTracks = if (fallback) index.tracks.sortedByDescending { it.dateAddedSec }.take(SHELF_SIZE) else recent,
            recentFromHistory = !fallback,
            recentAlbums = index.recentlyAddedAlbums(SHELF_SIZE),
            topArtists = artists,
        )
    }

    val state: StateFlow<HomeUiState> = combine(
        library.state.map { it.status() },
        player.state.map { it.current to it.isPlaying }.distinctUntilChanged(),
        shelves,
        shortcuts,
        layout,
    ) { status, (current, isPlaying), shelves, (favoritesCount, playlistsCount, mixes), (sections, added) ->
        HomeUiState(
            status = status,
            continueTrack = current,
            isPlaying = isPlaying,
            recentTracks = shelves.recentTracks,
            recentFromHistory = shelves.recentFromHistory,
            recentAlbums = shelves.recentAlbums,
            topArtists = shelves.topArtists,
            favoritesCount = favoritesCount,
            playlistsCount = playlistsCount,
            recentlyAdded = added,
            mixes = mixes,
            sections = sections,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refresh() = library.refresh()

    fun playFrom(tracks: List<Track>, index: Int) = player.play(tracks, index)

    fun resume() = player.togglePlayPause()

    private data class Shelves(
        val recentTracks: List<Track>,
        val recentFromHistory: Boolean,
        val recentAlbums: List<Album>,
        val topArtists: List<Artist>,
    )

    private companion object {
        const val SHELF_SIZE = 12
    }
}
