package com.aura.music.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import com.aura.music.core.designsystem.atmosphere.AtmosphereEngine
import com.aura.music.core.image.ArtworkFetcher
import com.aura.music.core.model.AlbumSort
import com.aura.music.core.model.ArtistSort
import com.aura.music.core.model.CornerStyle
import com.aura.music.core.model.CoverSize
import com.aura.music.core.model.DefaultRepeat
import com.aura.music.core.model.HomeSectionPref
import com.aura.music.core.model.LibraryLayout
import com.aura.music.core.model.ListDensity
import com.aura.music.core.model.MiniPlayerStyle
import com.aura.music.core.model.MotionLevel
import com.aura.music.core.model.NowPlayingButton
import com.aura.music.core.model.OutputPreference
import com.aura.music.core.model.PreviousBehavior
import com.aura.music.core.model.SortSpec
import com.aura.music.core.model.ThemeMode
import com.aura.music.core.model.Track
import com.aura.music.core.model.TrackSort
import com.aura.music.data.local.AppSettings
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.lyrics.LyricsFolderIndex
import com.aura.music.data.media.FolderRule
import com.aura.music.data.repository.ClassificationRepository
import com.aura.music.data.repository.HistoryRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.repository.LibraryState
import com.aura.music.data.repository.SearchHistoryRepository
import com.aura.music.data.backup.BackupRepository
import com.aura.music.data.backup.BackupResult
import com.aura.music.playback.EqualizerController
import com.aura.music.playback.EqualizerInfo
import com.aura.music.playback.EqualizerSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibrarySummary(val tracks: Int, val albums: Int, val artists: Int, val audios: Int)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val summary: LibrarySummary? = null,
    val isScanning: Boolean = false,
    val folders: List<String> = emptyList(),
    val hidden: List<Track> = emptyList(),
    val folderRules: List<FolderRule> = emptyList(),
)

data class EqualizerUiState(val info: EqualizerInfo? = null, val settings: EqualizerSettings = EqualizerSettings())

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val library: LibraryRepository,
    private val classification: ClassificationRepository,
    private val history: HistoryRepository,
    private val searchHistory: SearchHistoryRepository,
    private val equalizer: EqualizerController,
    private val backups: BackupRepository,
    private val lyricsFolders: LyricsFolderIndex,
    private val imageLoader: ImageLoader,
    private val artworkFetcher: ArtworkFetcher.Factory,
    private val atmosphere: AtmosphereEngine,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        repository.settings,
        library.state,
        library.scannedFolders,
        classification.folderRules,
    ) { settings, libraryState, folders, rules ->
        val index = (libraryState as? LibraryState.Ready)?.index
        SettingsUiState(
            settings = settings,
            summary = index?.let { LibrarySummary(it.tracks.size, it.albums.size, it.artists.size, it.audios.size) },
            isScanning = libraryState is LibraryState.Loading,
            folders = folders,
            hidden = index?.hidden.orEmpty(),
            folderRules = rules,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val equalizerState: StateFlow<EqualizerUiState> = combine(equalizer.info, equalizer.settings, ::EqualizerUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqualizerUiState())

    // Appearance
    fun setThemeMode(value: ThemeMode) = launch { repository.setThemeMode(value) }
    fun setAdaptiveAtmosphere(value: Boolean) = launch { repository.setAdaptiveAtmosphere(value) }
    fun setAtmosphereIntensity(value: Float) = launch { repository.setAtmosphereIntensity(value) }
    fun setGradientIntensity(value: Float) = launch { repository.setGradientIntensity(value) }
    fun setCornerStyle(value: CornerStyle) = launch { repository.setCornerStyle(value) }
    fun setCoverSize(value: CoverSize) = launch { repository.setCoverSize(value) }
    fun setListDensity(value: ListDensity) = launch { repository.setListDensity(value) }
    fun setTextScale(value: Float) = launch { repository.setTextScale(value) }
    fun setMotion(value: MotionLevel) = launch { repository.setMotion(value) }
    fun setMiniPlayerStyle(value: MiniPlayerStyle) = launch { repository.setMiniPlayerStyle(value) }
    fun setMiniPlayerSwipe(value: Boolean) = launch { repository.setMiniPlayerSwipe(value) }
    fun setAlbumLayout(value: LibraryLayout) = launch { repository.setAlbumLayout(value) }
    fun setArtistLayout(value: LibraryLayout) = launch { repository.setArtistLayout(value) }
    fun toggleNowPlayingButton(button: NowPlayingButton) = launch {
        val current = state.value.settings.nowPlayingButtons
        repository.setNowPlayingButtons(if (button in current) current - button else current + button)
    }
    fun setHomeSections(value: List<HomeSectionPref>) = launch { repository.setHomeSections(value) }

    // Playback
    fun setResumeLastSession(value: Boolean) = launch { repository.setResumeLastSession(value) }
    fun setSkipSilence(value: Boolean) = launch { repository.setSkipSilence(value) }
    fun setNormalizeVolume(value: Boolean) = launch { repository.setNormalizeVolume(value) }
    fun setTransitionSeconds(value: Int) = launch { repository.setTransitionSeconds(value) }
    fun setAnalyzeLibrary(value: Boolean) = launch { repository.setAnalyzeLibrary(value) }
    fun setPauseOnDisconnect(value: Boolean) = launch { repository.setPauseOnDisconnect(value) }
    fun setResumeOnConnect(value: Boolean) = launch { repository.setResumeOnConnect(value) }
    fun setDefaultRepeat(value: DefaultRepeat) = launch { repository.setDefaultRepeat(value) }
    fun setKeepShuffle(value: Boolean) = launch { repository.setKeepShuffle(value) }
    fun setPreviousBehavior(value: PreviousBehavior) = launch { repository.setPreviousBehavior(value) }
    fun setOutputPreference(value: OutputPreference) = launch { repository.setOutputPreference(value) }

    // Backup
    private val _backupBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy

    fun exportBackup(uri: Uri, onDone: (BackupResult) -> Unit) = launch {
        _backupBusy.value = true
        onDone(backups.export(uri))
        _backupBusy.value = false
    }

    fun restoreBackup(uri: Uri, onDone: (BackupResult) -> Unit) = launch {
        _backupBusy.value = true
        onDone(backups.restore(uri))
        _backupBusy.value = false
    }

    // Equalizer
    fun setEqualizerEnabled(value: Boolean) = launch { equalizer.setEnabled(value) }
    fun useEqualizerPreset(index: Int) = launch { equalizer.usePreset(index) }
    fun setBandLevel(band: Int, level: Int) = launch { equalizer.setBandLevel(band, level) }
    fun resetEqualizer() = launch { equalizer.reset() }

    // Library
    fun setMinTrackDuration(seconds: Int) = launch { repository.setMinTrackDuration(seconds) }
    fun setHideWhatsApp(value: Boolean) = launch { repository.setHideWhatsApp(value) }
    fun setOnlineLyrics(value: Boolean) = launch { repository.setOnlineLyrics(value) }
    fun setLyricsEffects(value: Boolean) = launch { repository.setLyricsEffects(value) }
    fun setOnlineCovers(value: Boolean) = launch { repository.setOnlineCovers(value) }
    fun setShowUntagged(value: Boolean) = launch { repository.setShowUntagged(value) }
    fun setFolderIncluded(path: String, included: Boolean) = launch { repository.setFolderExcluded(path, !included) }
    fun unhide(track: Track) = launch { classification.unhide(track.id) }
    fun removeFolderRule(rule: FolderRule) = launch { classification.removeFolderRule(rule.path) }
    fun setTrackSort(spec: SortSpec<TrackSort>) = launch { repository.setTrackSort(spec) }
    fun setAlbumSort(spec: SortSpec<AlbumSort>) = launch { repository.setAlbumSort(spec) }
    fun setArtistSort(spec: SortSpec<ArtistSort>) = launch { repository.setArtistSort(spec) }

    /** Forgets which albums had no artwork and re-extracts palettes, so new covers show up. */
    fun refreshArtwork(onDone: () -> Unit) {
        artworkFetcher.clearMissing()
        atmosphere.clear()
        imageLoader.memoryCache?.clear()
        onDone()
    }

    /** Frees the in-memory image cache (AURA keeps no image files of its own on disk). */
    fun clearImageCache(onDone: () -> Unit) {
        imageLoader.memoryCache?.clear()
        atmosphere.clear()
        onDone()
    }

    /** Rescans MediaStore and reports back once the new index is ready. */
    fun rescan(onDone: () -> Unit) {
        viewModelScope.launch {
            // Subscribe before triggering so a fast scan can't complete unobserved.
            val nextReady = async(start = CoroutineStart.UNDISPATCHED) {
                library.state.drop(1).first { it is LibraryState.Ready }
            }
            library.refresh()
            nextReady.await()
            onDone()
        }
    }

    // Lyrics
    fun addLyricsFolder(uri: Uri) = launch {
        // Keep access across restarts; the grant is read-only.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        repository.addLyricsFolder(uri.toString())
    }

    fun removeLyricsFolder(uri: String) = launch {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        repository.removeLyricsFolder(uri)
    }

    fun reindexLyrics(onDone: () -> Unit) = launch {
        lyricsFolders.invalidate()
        onDone()
    }

    // Search & privacy
    fun setPersonalization(value: Boolean) = launch { repository.setPersonalization(value) }
    fun setSearchHistoryEnabled(value: Boolean) = launch { repository.setSearchHistoryEnabled(value) }
    fun clearSearchHistory(onDone: () -> Unit) = launch {
        searchHistory.clear()
        onDone()
    }
    fun clearPlayHistory(onDone: () -> Unit) = launch {
        history.clear()
        onDone()
    }

    // AURA Together
    fun setDisplayName(value: String) = launch { repository.setDisplayName(value) }
    fun setAcceptInvites(value: Boolean) = launch { repository.setAcceptInvites(value) }
    fun setApproveJoins(value: Boolean) = launch { repository.setApproveJoins(value) }
    fun setTogetherDemocratic(value: Boolean) = launch { repository.setTogetherDemocratic(value) }
    fun setTogetherMaxParticipants(value: Int) = launch { repository.setTogetherMaxParticipants(value) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        val MinDurationOptions = listOf(0, 10, 30, 60)
    }
}
