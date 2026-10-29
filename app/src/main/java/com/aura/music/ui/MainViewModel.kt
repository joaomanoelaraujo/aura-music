package com.aura.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.core.designsystem.theme.ThemePreferences
import com.aura.music.core.model.MiniPlayerStyle
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.media.AudioPermission
import com.aura.music.data.repository.LibraryRepository
import com.aura.music.data.repository.LibraryState
import com.aura.music.playback.controller.PlayerConnection
import com.aura.music.together.JoinRequest
import com.aura.music.together.TogetherController
import com.aura.music.together.protocol.Invite
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppUiState(
    val onboardingCompleted: Boolean,
    val theme: ThemePreferences,
    val adaptiveAtmosphere: Boolean,
    val atmosphereIntensity: Float,
    val gradientIntensity: Float,
    val miniPlayerStyle: MiniPlayerStyle,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    player: PlayerConnection,
    private val together: TogetherController,
) : AndroidViewModel(application) {

    /** Null until settings are read; the splash screen stays up meanwhile. */
    val appState: StateFlow<AppUiState?> = settings.settings
        .map {
            AppUiState(
                onboardingCompleted = it.onboardingCompleted,
                theme = ThemePreferences(it.themeMode, it.motion, it.cornerStyle, it.listDensity, it.textScale),
                adaptiveAtmosphere = it.adaptiveAtmosphere,
                atmosphereIntensity = it.atmosphereIntensity,
                gradientIntensity = it.gradientIntensity,
                miniPlayerStyle = it.miniPlayerStyle,
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val hasMedia: StateFlow<Boolean> = player.state
        .map { it.hasMedia }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** An invite received through a link, waiting for the user to accept or cancel. */
    data class InviteOffer(val invite: Invite, val blocked: Boolean)

    private val _pendingInvite = MutableStateFlow<InviteOffer?>(null)
    val pendingInvite: StateFlow<InviteOffer?> = _pendingInvite.asStateFlow()

    val joinRequests: StateFlow<List<JoinRequest>> = together.requests

    /** Handles an opened link; returns true when it was an AURA Together invite. */
    fun offerInvite(link: String): Boolean {
        val invite = Invite.parse(link) ?: return false
        viewModelScope.launch {
            _pendingInvite.value = InviteOffer(invite, blocked = !settings.settings.first().acceptInvites)
        }
        return true
    }

    fun acceptInvite() {
        val offer = _pendingInvite.value ?: return
        _pendingInvite.value = null
        viewModelScope.launch { together.join(offer.invite) }
    }

    fun dismissInvite() {
        _pendingInvite.value = null
    }

    fun respondToJoin(request: JoinRequest, accept: Boolean) = together.respond(request, accept)

    private val _openPlayerPending = MutableStateFlow(false)

    /** True when something outside the UI (the media notification) asked for the full player. */
    val openPlayerPending: StateFlow<Boolean> = _openPlayerPending.asStateFlow()

    fun requestOpenPlayer() {
        _openPlayerPending.value = true
    }

    fun consumeOpenPlayer() {
        _openPlayerPending.value = false
    }

    fun completeOnboarding(granted: Boolean) {
        viewModelScope.launch {
            settings.setOnboardingCompleted()
            if (granted) library.refresh()
        }
    }

    /** Picks up a permission granted from system settings while the app was in the background. */
    fun onResume() {
        if (library.state.value == LibraryState.PermissionRequired && AudioPermission.isGranted(getApplication())) {
            library.refresh()
        }
    }
}
