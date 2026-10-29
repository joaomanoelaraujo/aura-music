package com.aura.music.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.core.model.Album
import com.aura.music.core.model.AlbumSort
import com.aura.music.core.model.Artist
import com.aura.music.core.model.AudioKind
import com.aura.music.core.model.ArtistSort
import com.aura.music.core.model.Folder
import com.aura.music.core.model.Genre
import com.aura.music.core.model.LibraryLayout
import com.aura.music.core.model.Playlist
import com.aura.music.core.model.SortSpec
import com.aura.music.core.model.Track
import com.aura.music.core.model.TrackSort
import com.aura.music.core.model.applySort
import com.aura.music.data.local.AppSettings
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.repository.LibraryState
import com.aura.music.data.repository.PlaylistRepository
import com.aura.music.feature.common.LibraryStatus
import com.aura.music.feature.common.status
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val status: LibraryStatus = LibraryStatus.Loading,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val audios: Map<AudioGroup, List<Track>> = emptyMap(),
    val settings: AppSettings = AppSettings(),
)

/** How the "Áudios" section is split for browsing. */
enum class AudioGroup { WhatsApp, Recordings, Other }

fun AudioKind.group(): AudioGroup = when (this) {
    AudioKind.WhatsAppVoice, AudioKind.WhatsAppAudio -> AudioGroup.WhatsApp
    AudioKind.Recording -> AudioGroup.Recordings
    else -> AudioGroup.Other
}

data class NowPlayingMarker(val trackId: Long? = null, val isPlaying: Boolean = false)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val player: PlayerConnection,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    val state: StateFlow<LibraryUiState> = combine(
        library.state,
        settingsRepository.settings,
        playlists.playlists,
    ) { libraryState, settings, playlistList ->
        val index = (libraryState as? LibraryState.Ready)?.index
        LibraryUiState(
            status = libraryState.status(),
            tracks = index?.tracks?.applySort(settings.trackSort).orEmpty(),
            albums = index?.albums?.applySort(settings.albumSort).orEmpty(),
            artists = index?.artists?.applySort(settings.artistSort).orEmpty(),
            playlists = playlistList,
            folders = index?.folders.orEmpty(),
            genres = index?.genres.orEmpty(),
            audios = index?.audios.orEmpty().groupBy { it.kind.group() },
            settings = settings,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    val nowPlaying: StateFlow<NowPlayingMarker> = player.state
        .map { NowPlayingMarker(it.current?.id, it.isPlaying) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingMarker())

    fun refresh() = library.refresh()

    fun play(tracks: List<Track>, index: Int) = player.play(tracks, index)

    fun shuffle(tracks: List<Track>) = player.play(tracks, shuffle = true)

    fun createPlaylist(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(playlists.create(name)) }
    }

    fun setTrackSort(spec: SortSpec<TrackSort>) = viewModelScope.launch { settingsRepository.setTrackSort(spec) }
    fun setAlbumSort(spec: SortSpec<AlbumSort>) = viewModelScope.launch { settingsRepository.setAlbumSort(spec) }
    fun setArtistSort(spec: SortSpec<ArtistSort>) = viewModelScope.launch { settingsRepository.setArtistSort(spec) }
    fun setAlbumLayout(layout: LibraryLayout) = viewModelScope.launch { settingsRepository.setAlbumLayout(layout) }
    fun setArtistLayout(layout: LibraryLayout) = viewModelScope.launch { settingsRepository.setArtistLayout(layout) }
}
