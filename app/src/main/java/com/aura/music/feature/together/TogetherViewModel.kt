package com.aura.music.feature.together

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.core.model.Track
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.search.SearchCorpus
import com.aura.music.playback.controller.PlaybackPosition
import com.aura.music.playback.controller.PlayerConnection
import com.aura.music.playback.controller.PlayerUiState
import com.aura.music.together.JoinRequest
import com.aura.music.together.StartResult
import com.aura.music.together.TogetherController
import com.aura.music.together.TogetherRole
import com.aura.music.together.protocol.Command
import com.aura.music.together.protocol.ControlMode
import com.aura.music.together.protocol.Invite
import com.aura.music.together.protocol.TrackRef
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TogetherViewModel @Inject constructor(
    private val together: TogetherController,
    private val player: PlayerConnection,
    private val settings: SettingsRepository,
    library: LibraryRepository,
) : ViewModel() {

    val role: StateFlow<TogetherRole> = together.role
    val requests: StateFlow<List<JoinRequest>> = together.requests
    val ambiguous: StateFlow<Pair<TrackRef, List<Track>>?> = together.ambiguous
    val playerState: StateFlow<PlayerUiState> = player.state
    val position: Flow<PlaybackPosition> = player.positions()

    val displayName: StateFlow<String> = settings.settings.map { it.displayName }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val addQuery = MutableStateFlow("")
    private val corpus = library.index.map { SearchCorpus(it, emptyList()) }.flowOn(Dispatchers.Default)

    /** Songs the user can add to the shared queue, filtered by the add sheet's search field. */
    val addCandidates: StateFlow<List<Track>> = combine(corpus, addQuery) { c, q -> c.filterTracks(q).take(60) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val addQueryText: StateFlow<String> = addQuery

    fun setAddQuery(value: String) {
        addQuery.value = value
    }

    fun create(sessionName: String, onResult: (StartResult) -> Unit) = viewModelScope.launch {
        onResult(together.startHosting(sessionName.trim()))
    }

    /** Returns false when the text isn't a valid AURA invite. */
    fun joinFromText(text: String): Boolean {
        val invite = Invite.parse(text.trim()) ?: return false
        viewModelScope.launch { together.join(invite) }
        return true
    }

    fun setDisplayName(name: String) = viewModelScope.launch { settings.setDisplayName(name) }

    fun leave() = together.leave()
    fun command(command: Command) = together.command(command)
    fun respond(request: JoinRequest, accept: Boolean) = together.respond(request, accept)
    fun kick(id: String) = together.kick(id)
    fun react(emoji: String) = together.react(emoji)
    val reactions = together.reactions
    fun setCanControl(id: String, allowed: Boolean) = together.setCanControl(id, allowed)
    fun setMode(mode: ControlMode) = together.setMode(mode)
    fun chooseMatch(ref: TrackRef, track: Track) = together.chooseMatch(ref, track)
    fun add(track: Track) = together.command(Command.AddToQueue(listOf(together.refOf(track))))
}
