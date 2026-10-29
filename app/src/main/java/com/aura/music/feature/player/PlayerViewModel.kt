package com.aura.music.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.core.model.MiniPlayerStyle
import com.aura.music.core.model.NowPlayingButton
import com.aura.music.data.local.SettingsRepository
import com.aura.music.playback.SleepTimer
import com.aura.music.playback.SleepTimerState
import com.aura.music.data.repository.FavoritesRepository
import com.aura.music.data.repository.PlaylistRepository
import com.aura.music.playback.controller.PlaybackPosition
import com.aura.music.playback.controller.PlayerConnection
import com.aura.music.playback.controller.PlayerUiState
import com.aura.music.together.JamNotice
import com.aura.music.together.TogetherController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerConnection,
    private val favorites: FavoritesRepository,
    private val playlists: PlaylistRepository,
    settings: SettingsRepository,
    private val sleepTimer: SleepTimer,
    together: TogetherController,
) : ViewModel() {

    /** Optional Now Playing buttons, in display order. */
    val nowPlayingButtons: StateFlow<List<NowPlayingButton>> = settings.settings
        .map { prefs -> NowPlayingButton.entries.filter { it in prefs.nowPlayingButtons } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(NowPlayingButton.Lyrics, NowPlayingButton.Details, NowPlayingButton.Queue))

    val miniPlayerStyle: StateFlow<MiniPlayerStyle> = settings.settings.map { it.miniPlayerStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiniPlayerStyle.Floating)

    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimer.state

    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes)
    fun sleepAtEndOfTrack() = sleepTimer.startEndOfTrack()
    fun extendSleepTimer(minutes: Int) = sleepTimer.extend(minutes)
    fun cancelSleepTimer() = sleepTimer.cancel()

    val state: StateFlow<PlayerUiState> = player.state

    val position: Flow<PlaybackPosition> = player.positions()

    val playbackErrors: SharedFlow<String> = player.errors

    /** What happened to songs picked while following a jam as a guest. */
    val jamNotices: SharedFlow<JamNotice> = together.notices

    val isFavorite: StateFlow<Boolean> = combine(player.state, favorites.favoriteIds) { state, ids ->
        state.current?.id in ids
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val miniPlayerSwipe: StateFlow<Boolean> = settings.settings.map { it.miniPlayerSwipe }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.skipNext()
    fun previous() = player.skipPrevious()
    fun swipeToNext() = player.skipToNextItem()
    fun swipeToPrevious() = player.skipToPreviousItem()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun skipTo(index: Int) = player.skipToQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = player.moveQueueItem(from, to)
    fun removeQueueItem(index: Int) = player.removeQueueItem(index)

    fun toggleFavorite() {
        val id = state.value.current?.id ?: return
        viewModelScope.launch { favorites.toggle(id) }
    }

    fun saveQueueAsPlaylist(name: String, onSaved: () -> Unit) {
        val ids = state.value.queue.map { it.track.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            playlists.create(name, ids)
            onSaved()
        }
    }
}
