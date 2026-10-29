package com.aura.music.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aura.music.core.designsystem.atmosphere.AtmosphereEngine
import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.res.stringResource
import com.aura.music.R
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.aura.music.core.designsystem.atmosphere.LocalAtmosphereEnabled
import com.aura.music.core.designsystem.atmosphere.LocalAtmosphereIntensity
import com.aura.music.core.designsystem.atmosphere.LocalGradientIntensity
import com.aura.music.core.designsystem.theme.AuraDarkSurface
import com.aura.music.core.model.MiniPlayerStyle
import com.aura.music.core.designsystem.atmosphere.LocalAtmosphereEngine
import com.aura.music.core.designsystem.components.AuraSnackbar
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Sizes
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.feature.actions.TrackActionsHost
import com.aura.music.feature.onboarding.OnboardingScreen
import com.aura.music.feature.player.PlayerSheet
import com.aura.music.feature.player.rememberPlayerSheetState
import com.aura.music.navigation.AuraBottomBar
import com.aura.music.navigation.AuraNavHost
import com.aura.music.navigation.LicensesRoute
import com.aura.music.navigation.LocalNavigator
import com.aura.music.navigation.Navigator
import com.aura.music.navigation.SettingsRoute
import com.aura.music.navigation.SettingsSectionRoute
import com.aura.music.navigation.TogetherRoute
import com.aura.music.navigation.TrackPickerRoute

@Composable
fun AuraApp(viewModel: MainViewModel, atmosphereEngine: AtmosphereEngine) {
    val appState by viewModel.appState.collectAsStateWithLifecycle()
    val state = appState ?: return
    AuraTheme(state.theme) {
        CompositionLocalProvider(
            LocalAtmosphereEngine provides atmosphereEngine,
            LocalAtmosphereEnabled provides state.adaptiveAtmosphere,
            LocalAtmosphereIntensity provides state.atmosphereIntensity,
            LocalGradientIntensity provides state.gradientIntensity,
        ) {
            Crossfade(targetState = state.onboardingCompleted, label = "onboarding") { completed ->
                if (completed) {
                    MainScaffold(viewModel, state.miniPlayerStyle)
                } else {
                    OnboardingScreen(onFinished = viewModel::completeOnboarding)
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(viewModel: MainViewModel, miniPlayerStyle: MiniPlayerStyle) {
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messenger = remember { Messenger(snackbarHost, scope) }
    val controlledBy by viewModel.controlledBy.collectAsStateWithLifecycle()
    val controlledText = controlledBy?.let { androidx.compose.ui.res.stringResource(R.string.link_controlled_by, it) }
    LaunchedEffect(controlledText) { controlledText?.let(messenger::show) }
    val sheetState = rememberPlayerSheetState()
    val hasMedia by viewModel.hasMedia.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = destination == null || !destination.isFullScreen()
    val density = LocalDensity.current
    val navBarHeight = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val bottomBarHeight = if (showBottomBar) Sizes.bottomBarHeight else 0.dp
    val miniGap = if (miniPlayerStyle == MiniPlayerStyle.Docked) 0.dp else Spacing.x2
    val miniPlayerSpace = if (hasMedia) Sizes.miniPlayerHeight + miniGap else 0.dp
    val playerCoversScreen by remember(sheetState) { derivedStateOf { sheetState.progress > 0.5f } }
    SystemBarAppearance(lightContent = AuraTheme.colors.isLight && !(hasMedia && playerCoversScreen))
    val bottomInset = navBarHeight + bottomBarHeight + miniPlayerSpace

    LaunchedEffect(hasMedia) { if (!hasMedia) sheetState.collapse() }
    // Requests can arrive before the controller has connected, so they wait for media.
    val openPlayerPending by viewModel.openPlayerPending.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerPending, hasMedia) {
        if (openPlayerPending && hasMedia) {
            viewModel.consumeOpenPlayer()
            sheetState.expand()
        }
    }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalMessenger provides messenger,
        LocalPlayerSheet provides sheetState,
        LocalBottomInset provides bottomInset,
    ) {
        TrackActionsHost { actions ->
            CompositionLocalProvider(LocalTrackActions provides actions) {
                Box(Modifier.fillMaxSize().background(AuraTheme.colors.background)) {
                    AuraNavHost(navController, Modifier.fillMaxSize())
                    BottomScrim(height = bottomInset + Spacing.x6, Modifier.align(Alignment.BottomCenter))
                    if (showBottomBar) {
                        AuraBottomBar(
                            currentTab = navigator.currentTab,
                            onNavigate = navigator::openTopLevel,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .graphicsLayer { translationY = sheetState.progress * size.height },
                        )
                    }
                    SnackbarHost(
                        hostState = snackbarHost,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = bottomInset + Spacing.x2),
                        snackbar = { AuraSnackbar(it) },
                    )
                    if (hasMedia) AuraDarkSurface { PlayerSheet(sheetState, bottomBarHeight) }
                    TogetherDialogs(viewModel, onJoined = navigator::openTogether)
                }
            }
        }
    }
}

/** The guest's "accept invite?" dialog and the host's "let them in?" dialog, shown over any screen. */
@Composable
private fun TogetherDialogs(viewModel: MainViewModel, onJoined: () -> Unit) {
    val invite by viewModel.pendingInvite.collectAsStateWithLifecycle()
    val requests by viewModel.joinRequests.collectAsStateWithLifecycle()
    invite?.let { offer ->
        InviteDialog(
            title = stringResource(R.string.together_invite_title),
            message = if (offer.blocked) {
                stringResource(R.string.together_invites_off)
            } else {
                stringResource(R.string.together_invite_body, offer.invite.hostName, offer.invite.sessionName.ifBlank { offer.invite.hostName })
            },
            confirm = if (offer.blocked) null else stringResource(R.string.together_accept),
            dismiss = stringResource(if (offer.blocked) R.string.action_done else R.string.action_cancel),
            onConfirm = {
                viewModel.acceptInvite()
                onJoined()
            },
            onDismiss = viewModel::dismissInvite,
        )
    }
    requests.firstOrNull()?.let { request ->
        InviteDialog(
            title = stringResource(R.string.together_request_title),
            message = stringResource(R.string.together_request_body, request.name),
            confirm = stringResource(R.string.together_allow),
            dismiss = stringResource(R.string.together_decline),
            onConfirm = { viewModel.respondToJoin(request, true) },
            onDismiss = { viewModel.respondToJoin(request, false) },
        )
    }
}

@Composable
private fun InviteDialog(title: String, message: String, confirm: String?, dismiss: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraTheme.colors.surfaceElevated,
        shape = com.aura.music.core.designsystem.theme.Radius.xLarge,
        icon = { androidx.compose.material3.Icon(com.aura.music.core.designsystem.icons.AuraIcons.Together, null, tint = AuraTheme.colors.accent) },
        title = { androidx.compose.material3.Text(title, style = AuraTheme.type.headline, color = AuraTheme.colors.textPrimary) },
        text = { androidx.compose.material3.Text(message, style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary) },
        confirmButton = { if (confirm != null) com.aura.music.core.designsystem.components.AuraButton(confirm, onConfirm) },
        dismissButton = {
            com.aura.music.core.designsystem.components.AuraButton(dismiss, onDismiss, style = com.aura.music.core.designsystem.components.ButtonStyle.Secondary)
        },
    )
}

/** Dark status/navigation icons on light screens, light icons on dark ones and over the player. */
@Composable
private fun SystemBarAppearance(lightContent: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightContent
            isAppearanceLightNavigationBars = lightContent
        }
    }
}

/** Fades scrolling content out beneath the mini-player and navigation so the bottom stays calm. */
@Composable
private fun BottomScrim(height: Dp, modifier: Modifier = Modifier) {
    val background = AuraTheme.colors.background
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to background.copy(alpha = 0.85f), 1f to background)),
    )
}

/** Utility screens hide the bottom navigation to give their content the full height. */
private fun NavDestination.isFullScreen(): Boolean =
    hasRoute<SettingsRoute>() || hasRoute<SettingsSectionRoute>() || hasRoute<TogetherRoute>() || hasRoute<com.aura.music.navigation.LinkRoute>() || hasRoute<com.aura.music.navigation.LinkRemoteRoute>() || hasRoute<LicensesRoute>() || hasRoute<TrackPickerRoute>()
