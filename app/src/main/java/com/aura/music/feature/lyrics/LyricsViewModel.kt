package com.aura.music.feature.lyrics

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.data.lyrics.ImportResult
import com.aura.music.data.lyrics.LyricLine
import com.aura.music.data.lyrics.LyricsRepository
import com.aura.music.data.lyrics.LyricsSource
import com.aura.music.data.lyrics.LyricsState
import com.aura.music.data.lyrics.online.LrclibRecord
import com.aura.music.data.lyrics.online.LyricsLookup
import com.aura.music.data.lyrics.online.OnlineLyrics
import com.aura.music.core.model.Track
import com.aura.music.data.local.SettingsRepository
import com.aura.music.playback.KaraokeController
import com.aura.music.data.lyrics.Lyrics
import com.aura.music.data.analysis.IntensityProfile
import com.aura.music.data.analysis.LineMood
import com.aura.music.data.analysis.LyricMoods
import com.aura.music.data.analysis.SongAnalysisRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import com.aura.music.playback.controller.PlaybackPosition
import com.aura.music.playback.controller.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LyricsUiState(
    val trackId: Long? = null,
    val lyrics: LyricsState = LyricsState(),
    val loading: Boolean = true,
)

/** How the song moves, and the mood of each lyric line (null for lines without a time). */
class LyricsFx(val profile: IntensityProfile, val moods: List<LineMood?>)

/** The "search lyrics online" sheet. */
sealed interface LyricsSearchUi {
    data object Searching : LyricsSearchUi
    data class Results(val records: List<LrclibRecord>) : LyricsSearchUi
    data object Offline : LyricsSearchUi
    data object Failed : LyricsSearchUi
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LyricsViewModel @Inject constructor(
    private val player: PlayerConnection,
    private val repository: LyricsRepository,
    private val effects: SongAnalysisRepository,
    private val karaokeMode: KaraokeController,
    settings: SettingsRepository,
) : ViewModel() {

    /** Karaoke: the lead vocal removed while the lyrics play. */
    val karaoke: StateFlow<Boolean> = karaokeMode.enabled

    fun toggleKaraoke() = karaokeMode.toggle()


    private var track: Track? = null
    private val _autoSearching = MutableStateFlow(false)

    /** True while looking for this song's lyrics online by itself. */
    val autoSearching: StateFlow<Boolean> = _autoSearching.asStateFlow()

    private val _search = MutableStateFlow<LyricsSearchUi?>(null)
    val search: StateFlow<LyricsSearchUi?> = _search.asStateFlow()

    val state: StateFlow<LyricsUiState> = player.state
        .map { it.current }
        .distinctUntilChanged { a, b -> a?.id == b?.id }
        .onEach { current ->
            track = current
            _search.value = null
            // Opening lyrics for a song without any: look online right away (when allowed).
            if (current != null) {
                viewModelScope.launch {
                    _autoSearching.value = true
                    runCatching { repository.fetchOnlineIfMissing(current) }
                    if (track?.id == current.id) _autoSearching.value = false
                }
            }
        }
        .flatMapLatest { track ->
            if (track == null) {
                flowOf(LyricsUiState(loading = false))
            } else {
                repository.observe(track).map { LyricsUiState(track.id, it, loading = false) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUiState())

    private val effectsEnabled = settings.settings.map { it.lyricsEffects }.distinctUntilChanged()

    /** The current song's intensity profile: from the cache at once, or analyzed (a few seconds). */
    private val profile: Flow<IntensityProfile?> = combine(
        player.state.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id },
        effectsEnabled,
    ) { track, enabled -> track.takeIf { enabled } }
        .flatMapLatest { track ->
            if (track == null) {
                flowOf(null)
            } else {
                flow {
                    val cached = effects.cached(track)
                    emit(cached)
                    if (cached == null) emit(runCatching { effects.profile(track) }.getOrNull())
                }
            }
        }

    /** Lyric effects for the current song, or null when off, unsynced or not analyzed (yet). */
    val fx: StateFlow<LyricsFx?> = combine(state, profile) { ui, profile ->
        val lyrics = ui.lyrics.lyrics
        if (profile == null || !lyrics.synced) null else LyricsFx(profile, moodsOf(lyrics, ui.lyrics.totalOffsetMs, profile))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Each line's mood over the stretch of audio where it's sung (until the next line starts). */
    private fun moodsOf(lyrics: Lyrics, offsetMs: Long, profile: IntensityProfile): List<LineMood?> {
        val lines = lyrics.lines
        return lines.mapIndexed { i, line ->
            val time = line.timeMs ?: return@mapIndexed null
            val next = lines.drop(i + 1).firstNotNullOfOrNull { it.timeMs }?.takeIf { it > time } ?: (time + LAST_LINE_MS)
            LyricMoods.of(profile, time - offsetMs, next - offsetMs)
        }
    }

    /** Fine-grained position, only polled while a lyrics view collects it. */
    val position: Flow<PlaybackPosition> = player.positions(playingTickMs = TICK_MS)

    val isPlaying: StateFlow<Boolean> = player.state.map { it.isPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Seeks so that [line] becomes the active verse, honouring the sync offset. */
    fun seekTo(line: LyricLine) {
        val time = line.timeMs ?: return
        player.seekTo((time - state.value.lyrics.totalOffsetMs).coerceAtLeast(0))
    }

    fun togglePlayPause() = player.togglePlayPause()

    fun nudgeOffset(deltaMs: Long) = withTrack { id -> repository.setOffset(id, state.value.lyrics.userOffsetMs + deltaMs) }

    fun resetOffset() = withTrack { id -> repository.setOffset(id, 0) }

    fun save(text: String, onSaved: () -> Unit) = withTrack { id ->
        repository.saveCustom(id, text)
        onSaved()
    }

    fun revert(onDone: () -> Unit) = withTrack { id ->
        repository.clearCustom(id)
        onDone()
    }

    /** Searches LRCLIB; [text] replaces the song's own "artist title" when the tags are off. */
    fun searchOnline(text: String? = null) {
        val current = track ?: return
        _search.value = LyricsSearchUi.Searching
        viewModelScope.launch {
            _search.value = when (val result = repository.searchOnline(current, text)) {
                is OnlineLyrics.SearchResult.Found -> LyricsSearchUi.Results(result.records)
                OnlineLyrics.SearchResult.Offline -> LyricsSearchUi.Offline
                OnlineLyrics.SearchResult.Failed -> LyricsSearchUi.Failed
            }
        }
    }

    fun closeSearch() {
        _search.value = null
    }

    fun choose(record: LrclibRecord, onDone: () -> Unit) = withTrack { id ->
        repository.chooseOnline(id, record)
        _search.value = null
        onDone()
    }

    /** Drops lyrics that were found online (wrong song): they won't be fetched by themselves again. */
    fun removeOnline(onDone: () -> Unit) = withTrack { id ->
        repository.removeOnline(id)
        onDone()
    }

    /** What to search for by default: the song's own artist and title. */
    fun defaultQuery(): String = track?.let { LyricsLookup.suggestedQuery(it.title, it.artist) }.orEmpty()

    val isOnlineSource: Boolean get() = state.value.lyrics.source == LyricsSource.Online

    fun import(uri: Uri, onResult: (ImportResult) -> Unit) = withTrack { id -> onResult(repository.import(id, uri)) }

    private fun withTrack(block: suspend (Long) -> Unit) {
        val id = state.value.trackId ?: return
        viewModelScope.launch { block(id) }
    }

    private companion object {
        const val TICK_MS = 100L
        const val LAST_LINE_MS = 4_000L
    }
}
