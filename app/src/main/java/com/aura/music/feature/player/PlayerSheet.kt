package com.aura.music.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.together.JamNotice
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.Atmosphere
import com.aura.music.core.designsystem.atmosphere.LocalGradientIntensity
import com.aura.music.core.designsystem.atmosphere.atmosphereBackground
import com.aura.music.core.designsystem.atmosphere.rememberAtmosphere
import com.aura.music.core.designsystem.components.AdaptiveCover
import com.aura.music.core.designsystem.components.TextInputDialog
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.designsystem.theme.Sizes
import com.aura.music.core.common.formatDuration
import com.aura.music.core.designsystem.components.AuraChip
import com.aura.music.core.designsystem.components.AuraSheet
import com.aura.music.core.designsystem.components.SheetAction
import com.aura.music.core.designsystem.components.SheetHeader
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.MiniPlayerStyle
import com.aura.music.core.model.Track
import com.aura.music.feature.lyrics.LyricsFullScreen
import com.aura.music.feature.lyrics.LyricsHostActions
import com.aura.music.feature.lyrics.LyricsPanel
import com.aura.music.feature.lyrics.LyricsViewModel
import com.aura.music.navigation.SettingsSection
import com.aura.music.playback.SleepTimerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableLongStateOf
import com.aura.music.navigation.LocalNavigator
import com.aura.music.playback.controller.PlaybackPosition
import com.aura.music.playback.controller.PlayerUiState
import com.aura.music.ui.LocalMessenger
import com.aura.music.ui.LocalTrackActions
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private val MiniMargin = 8.dp
private val MiniGap = 6.dp
private val MiniArtworkInset = 8.dp
private val MiniArtworkSize = 48.dp

/**
 * The player as one continuous surface. At progress 0 it is the floating mini-player card;
 * dragging or tapping grows the same card into Now Playing while the artwork travels to
 * its full-size slot and the atmosphere fades in.
 */
@Composable
fun PlayerSheet(
    sheetState: PlayerSheetState,
    bottomBarHeight: Dp,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val position by viewModel.position.collectAsStateWithLifecycle(PlaybackPosition())
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val swipeEnabled by viewModel.miniPlayerSwipe.collectAsStateWithLifecycle()
    val miniStyle by viewModel.miniPlayerStyle.collectAsStateWithLifecycle()
    val atmosphere = rememberAtmosphere(track.artwork)
    val lyricsViewModel: LyricsViewModel = hiltViewModel()
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var lyricsMode by rememberSaveable { mutableStateOf(false) }
    var lyricsFullScreen by rememberSaveable { mutableStateOf(false) }
    var sleepSheetOpen by remember { mutableStateOf(false) }
    var savingQueue by remember { mutableStateOf(false) }

    PlaybackErrorMessages(viewModel)
    BackHandler(enabled = lyricsFullScreen) { lyricsFullScreen = false }
    BackHandler(enabled = queueOpen) { queueOpen = false }
    BackHandler(enabled = !queueOpen && !lyricsFullScreen && sheetState.isExpanded) { sheetState.collapse() }
    LaunchedEffect(sheetState.isExpanded) {
        if (!sheetState.isExpanded) {
            queueOpen = false
            lyricsFullScreen = false
        }
    }
    val lyricsHost = remember {
        LyricsHostActions(
            onToggleFullscreen = { lyricsFullScreen = !lyricsFullScreen },
            onShowCover = { lyricsMode = false },
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
        val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        val fontScale = density.fontScale
        val layout = remember(maxWidth, maxHeight, topInset, bottomInset, bottomBarHeight, fontScale, miniStyle) {
            SheetLayout(maxWidth, maxHeight, topInset, bottomInset, bottomBarHeight, fontScale, miniStyle)
        }
        val p = sheetState.progress
        val frame = layout.frameAt(p)
        val travelPx = with(density) { layout.collapsedTop.toPx() }

        Box(
            Modifier
                .offset { IntOffset(frame.left.roundToPx(), frame.top.roundToPx()) }
                .requiredSize(frame.width, frame.height)
                .clip(RoundedCornerShape(frame.corner))
                .background(lerp(lerp(AuraTheme.colors.surfaceElevated, atmosphere.glow, 0.28f), atmosphere.background, p))
                .draggable(
                    state = rememberDraggableState { delta -> sheetState.dragBy(-delta / travelPx) },
                    orientation = Orientation.Vertical,
                    enabled = !queueOpen,
                    onDragStopped = { velocity -> sheetState.release(-velocity / travelPx) },
                ),
        ) {
            if (p > 0f) {
                Box(
                    Modifier
                        .placeUnbounded(layout.width, layout.height) { IntOffset(-frame.left.roundToPx(), 0) }
                        .graphicsLayer { alpha = p }
                        .atmosphereBackground(atmosphere, glowStrength = LocalGradientIntensity.current),
                )
            }
            if (p < 1f) {
                MiniPlayerContent(
                    track = track,
                    isPlaying = state.isPlaying,
                    progressFraction = position.fraction,
                    atmosphere = atmosphere,
                    swipeEnabled = swipeEnabled,
                    onExpand = sheetState::expand,
                    onTogglePlay = viewModel::togglePlayPause,
                    onNext = viewModel::next,
                    onPrevious = viewModel::previous,
                    modifier = Modifier.graphicsLayer { alpha = (1f - p * 3f).coerceIn(0f, 1f) },
                )
            }
            if (p > 0f) {
                FullPlayer(
                    state = state,
                    position = position,
                    isFavorite = isFavorite,
                    atmosphere = atmosphere,
                    layout = layout,
                    frame = frame,
                    sheetState = sheetState,
                    onOpenQueue = { queueOpen = true },
                    viewModel = viewModel,
                    lyricsMode = lyricsMode,
                    onToggleLyrics = { lyricsMode = !lyricsMode },
                    onOpenSleepTimer = { sleepSheetOpen = true },
                    lyricsContent = { LyricsPanel(lyricsViewModel, atmosphere, lyricsHost) },
                )
            }
            MorphingArtwork(
                state = state,
                atmosphere = atmosphere,
                layout = layout,
                frame = frame,
                p = p,
                hidden = queueOpen || lyricsFullScreen || (lyricsMode && sheetState.isExpanded),
                onSwipeNext = viewModel::swipeToNext,
                onSwipePrevious = viewModel::swipeToPrevious,
            )
            QueueOverlay(
                visible = queueOpen && p > 0.99f,
                state = state,
                atmosphere = atmosphere,
                layout = layout,
                onClose = { queueOpen = false },
                onSaveQueue = { savingQueue = true },
                sheetState = sheetState,
                viewModel = viewModel,
            )
            LyricsOverlay(
                visible = lyricsFullScreen && p > 0.99f,
                track = track,
                atmosphere = atmosphere,
                layout = layout,
                viewModel = lyricsViewModel,
                host = lyricsHost,
            )
        }
    }

    if (sleepSheetOpen) SleepTimerSheet(viewModel, onDismiss = { sleepSheetOpen = false })
    if (savingQueue) SaveQueueDialog(viewModel, onDismiss = { savingQueue = false })
}

@Composable
private fun FullPlayer(
    state: PlayerUiState,
    position: PlaybackPosition,
    isFavorite: Boolean,
    atmosphere: Atmosphere,
    layout: SheetLayout,
    frame: SheetFrame,
    sheetState: PlayerSheetState,
    onOpenQueue: () -> Unit,
    viewModel: PlayerViewModel,
    lyricsMode: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    lyricsContent: @Composable () -> Unit,
) {
    val trackActions = LocalTrackActions.current
    val navigator = LocalNavigator.current
    val track = state.current ?: return
    val p = sheetState.progress
    val buttons by viewModel.nowPlayingButtons.collectAsStateWithLifecycle()
    val sleep by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    NowPlayingContent(
        state = state,
        position = position,
        isFavorite = isFavorite,
        atmosphere = atmosphere,
        geometry = layout.geometry,
        topInset = layout.topInset,
        actions = NowPlayingActions(
            onCollapse = sheetState::collapse,
            onMore = { trackActions.open(track) },
            onTogglePlay = viewModel::togglePlayPause,
            onNext = viewModel::next,
            onPrevious = viewModel::previous,
            onSeek = viewModel::seekTo,
            onToggleShuffle = viewModel::toggleShuffle,
            onCycleRepeat = viewModel::cycleRepeat,
            onToggleFavorite = viewModel::toggleFavorite,
            onOpenArtist = {
                if (track.artistId >= 0) {
                    sheetState.collapse()
                    navigator.openArtist(track.artistId)
                }
            },
            onOpenQueue = onOpenQueue,
            onOpenDetails = { trackActions.showDetails(track) },
            onToggleLyrics = onToggleLyrics,
            onOpenSleepTimer = onOpenSleepTimer,
            onOpenEqualizer = {
                sheetState.collapse()
                navigator.openSettingsSection(SettingsSection.Equalizer)
            },
        ),
        utility = UtilityState(buttons, lyricsMode, sleepLabel(sleep)),
        centerContent = if (lyricsMode) lyricsContent else null,
        modifier = Modifier
            .placeUnbounded(layout.width, layout.height) { IntOffset(-frame.left.roundToPx(), 0) }
            .graphicsLayer {
                alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                translationY = (1f - p) * 48.dp.toPx()
            },
    )
}

/**
 * The cover travels between the mini-player slot and the Now Playing slot. Fully expanded, it
 * becomes a swipeable carousel (next / previous track) and adapts to the artwork's resolution.
 */
@Composable
private fun MorphingArtwork(
    state: PlayerUiState,
    atmosphere: Atmosphere,
    layout: SheetLayout,
    frame: SheetFrame,
    p: Float,
    hidden: Boolean,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
) {
    val track = state.current ?: return
    val reduced = LocalReducedMotion.current
    val visibility by androidx.compose.animation.core.animateFloatAsState(
        if (hidden) 0f else 1f,
        Motion.tween(Motion.MEDIUM, reduced),
        label = "artworkVisibility",
    )
    val slot = layout.geometry.artworkSize
    val size = lerp(MiniArtworkSize, slot, p)
    val screenX = lerp(layout.artworkLeft, (layout.width - slot) / 2, p)
    val screenY = lerp(layout.collapsedTop + MiniArtworkInset, layout.geometry.artworkTop, p)
    val shape = RoundedCornerShape(lerp(12.dp, 24.dp, p))
    val tile = lerp(atmosphere.background, atmosphere.glow, 0.6f)
    CoverCarousel(
        current = track,
        previous = state.previousTrack,
        next = state.nextTrack,
        enabled = p >= 1f && !hidden,
        onNext = onSwipeNext,
        onPrevious = onSwipePrevious,
        modifier = Modifier
            .zIndex(1f)
            .placeUnbounded(size, size) { IntOffset((screenX - frame.left).roundToPx(), (screenY - frame.top).roundToPx()) }
            .graphicsLayer { alpha = visibility },
    ) { cover, coverModifier ->
        AdaptiveCover(
            source = cover.artwork,
            slot = slot,
            tint = tile,
            expansion = p,
            shape = shape,
            placeholderSeed = cover.album,
            modifier = coverModifier,
        )
    }
}

@Composable
private fun QueueOverlay(
    visible: Boolean,
    state: PlayerUiState,
    atmosphere: Atmosphere,
    layout: SheetLayout,
    onClose: () -> Unit,
    onSaveQueue: () -> Unit,
    sheetState: PlayerSheetState,
    viewModel: PlayerViewModel,
) {
    val navigator = LocalNavigator.current
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.zIndex(2f),
        enter = if (reduced) fadeIn(Motion.tween(0, true)) else slideInVertically(Motion.tween(Motion.LONG, false)) { it / 3 } + fadeIn(Motion.tween(Motion.MEDIUM, false)),
        exit = if (reduced) fadeOut(Motion.tween(0, true)) else slideOutVertically(Motion.tween(Motion.MEDIUM, false)) { it / 3 } + fadeOut(Motion.tween(Motion.SHORT, false)),
    ) {
        QueuePanel(
            state = state,
            atmosphere = atmosphere,
            topInset = layout.topInset,
            bottomInset = layout.bottomInset,
            modifier = Modifier.placeUnbounded(layout.width, layout.height) { IntOffset.Zero },
            actions = QueueActions(
                onClose = onClose,
                onSkipTo = viewModel::skipTo,
                onMove = viewModel::moveQueueItem,
                onRemove = viewModel::removeQueueItem,
                onAddSongs = {
                    onClose()
                    sheetState.collapse()
                    navigator.openTrackPicker(null)
                },
                onSaveAsPlaylist = onSaveQueue,
            ),
        )
    }
}

/** Full-screen lyrics above everything in the expanded sheet. */
@Composable
private fun LyricsOverlay(
    visible: Boolean,
    track: Track,
    atmosphere: Atmosphere,
    layout: SheetLayout,
    viewModel: LyricsViewModel,
    host: LyricsHostActions,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.zIndex(3f),
        enter = fadeIn(Motion.tween(Motion.MEDIUM, reduced)),
        exit = fadeOut(Motion.tween(Motion.SHORT, reduced)),
    ) {
        LyricsFullScreen(
            viewModel = viewModel,
            track = track,
            atmosphere = atmosphere,
            host = host,
            modifier = Modifier.placeUnbounded(layout.width, layout.height) { IntOffset.Zero },
        )
    }
}

@Composable
private fun sleepLabel(state: SleepTimerState): String? {
    val running = state as? SleepTimerState.Running
    if (running != null) {
        // Re-read once per second while visible so the countdown moves.
        var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        LaunchedEffect(running) {
            while (true) {
                now = android.os.SystemClock.elapsedRealtime()
                kotlinx.coroutines.delay(1_000)
            }
        }
        return formatDuration(running.remainingMs(now))
    }
    return if (state == SleepTimerState.EndOfTrack) stringResource(R.string.sleep_end_of_track) else null
}

@Composable
private fun SleepTimerSheet(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val state by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    AuraSheet(onDismiss) {
        SheetHeader(
            title = stringResource(R.string.sleep_timer_title),
            subtitle = when (val s = state) {
                is SleepTimerState.Running -> stringResource(R.string.sleep_remaining, sleepLabel(s).orEmpty())
                SleepTimerState.EndOfTrack -> stringResource(R.string.sleep_end_of_track_active)
                SleepTimerState.Off -> stringResource(R.string.sleep_timer_body)
            },
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            listOf(5, 10, 15, 30, 45, 60, 90).forEach { minutes ->
                AuraChip(stringResource(R.string.sleep_minutes, minutes), selected = false, onClick = {
                    viewModel.startSleepTimer(minutes)
                    onDismiss()
                })
            }
            AuraChip(stringResource(R.string.sleep_end_of_track), selected = state == SleepTimerState.EndOfTrack, onClick = {
                viewModel.sleepAtEndOfTrack()
                onDismiss()
            })
        }
        if (state is SleepTimerState.Running) {
            SheetAction(AuraIcons.Plus, stringResource(R.string.sleep_extend, 10), onClick = { viewModel.extendSleepTimer(10) })
        }
        if (state != SleepTimerState.Off) {
            SheetAction(AuraIcons.Close, stringResource(R.string.sleep_off), onClick = {
                viewModel.cancelSleepTimer()
                onDismiss()
            })
        }
    }
}

@Composable
private fun SaveQueueDialog(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    val defaultName = remember {
        resources.getString(R.string.queue_default_playlist_name, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date()))
    }
    TextInputDialog(
        title = stringResource(R.string.queue_save),
        initialValue = defaultName,
        placeholder = stringResource(R.string.playlist_name_placeholder),
        confirmLabel = stringResource(R.string.action_save),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = { name ->
            viewModel.saveQueueAsPlaylist(name) { messenger.show(resources.getString(R.string.msg_playlist_created, name.trim())) }
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun PlaybackErrorMessages(viewModel: PlayerViewModel) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.playbackErrors.collect { title ->
            messenger.show(
                if (title.isBlank()) resources.getString(R.string.msg_playback_error_generic) else resources.getString(R.string.msg_playback_error, title),
            )
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.jamNotices.collect { notice ->
            messenger.show(
                when (notice) {
                    is JamNotice.Sent -> resources.getString(if (notice.now) R.string.together_sent_now else R.string.together_sent_queue, notice.title)
                    JamNotice.ViewOnly -> resources.getString(R.string.together_guest_view_only)
                },
            )
        }
    }
}

/**
 * Measures the content at an exact size (possibly larger than the clipped sheet) and places it at
 * [offset] relative to the sheet, instead of letting the parent centre or squash it.
 */
private fun Modifier.placeUnbounded(width: Dp, height: Dp, offset: Density.() -> IntOffset): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints.fixed(width.roundToPx(), height.roundToPx()))
        val position = offset(this)
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(position) }
    }

/** Position and shape of the sheet for a given progress, in screen coordinates. */
data class SheetFrame(val left: Dp, val top: Dp, val width: Dp, val height: Dp, val corner: Dp)

/** Screen-dependent anchors for the collapsed and expanded sheet. */
class SheetLayout(
    val width: Dp,
    val height: Dp,
    val topInset: Dp,
    val bottomInset: Dp,
    bottomBarHeight: Dp,
    fontScale: Float,
    style: MiniPlayerStyle = MiniPlayerStyle.Floating,
) {
    /** Floating: an inset card with rounded corners. Docked: a full-width bar resting on the navigation. */
    private val margin = if (style == MiniPlayerStyle.Docked) 0.dp else MiniMargin
    private val gap = if (style == MiniPlayerStyle.Docked) 0.dp else MiniGap
    private val corner = if (style == MiniPlayerStyle.Docked) 0.dp else 18.dp
    val artworkLeft: Dp = margin + MiniArtworkInset
    val collapsedTop: Dp = height - bottomInset - bottomBarHeight - Sizes.miniPlayerHeight - gap
    val geometry: NowPlayingGeometry = NowPlayingGeometry.compute(width, height, topInset, bottomInset, fontScale)

    fun frameAt(p: Float): SheetFrame {
        val left = lerp(margin, 0.dp, p)
        val top = lerp(collapsedTop, 0.dp, p)
        val bottom = lerp(collapsedTop + Sizes.miniPlayerHeight, height, p)
        return SheetFrame(
            left = left,
            top = top,
            width = width - left * 2,
            height = bottom - top,
            corner = lerp(corner, 0.dp, p),
        )
    }
}
