package com.aura.music.feature.lyrics

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.ButtonStyle
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.data.lyrics.LrcParser
import com.aura.music.data.lyrics.LyricLine
import com.aura.music.data.lyrics.Lyrics
import com.aura.music.data.analysis.LineMood
import com.aura.music.data.analysis.LyricMoods
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lyrics that follow the music: the sung verse is fully opaque and slightly larger, its
 * neighbours recede, and the list glides so the active verse sits in the upper third.
 * Scrolling by hand pauses the auto-scroll for a moment; tapping a verse seeks to it.
 * With [fx], the sung verse also reacts to how the music feels at that moment.
 */
@Composable
fun LyricsLines(
    lyrics: Lyrics,
    offsetMs: Long,
    positionMs: Long,
    large: Boolean,
    onLineClick: (LyricLine) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    fx: LyricsFx? = null,
    isPlaying: Boolean = false,
    accent: Color = Color.Unspecified,
) {
    val reduced = LocalReducedMotion.current
    // Effects follow the beat, so they need a clock finer than the 100 ms position updates.
    val clock = rememberSmoothPosition(positionMs, running = fx != null && isPlaying && !reduced)
    val lines = lyrics.lines
    val active = if (lyrics.synced) LrcParser.activeIndex(lines, positionMs + offsetMs) else -1
    val listState = rememberLazyListState()
    var userScrolledAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolledAt = SystemClock.elapsedRealtime() }
    }

    BoxWithConstraints(modifier) {
        val anchorPx = with(LocalDensity.current) { (maxHeight * 0.3f).roundToPx() }
        LaunchedEffect(active, lyrics) {
            if (!lyrics.synced || SystemClock.elapsedRealtime() - userScrolledAt < MANUAL_SCROLL_HOLD_MS) return@LaunchedEffect
            val target = active.coerceAtLeast(0)
            if (reduced) listState.scrollToItem(target, -anchorPx) else listState.animateScrollToItem(target, -anchorPx)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = maxHeight * 0.6f + contentPadding.calculateBottomPadding(),
            ),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                LyricRow(
                    line = line,
                    state = when {
                        !lyrics.synced -> LineState.Plain
                        index == active -> LineState.Active
                        index < active -> LineState.Past
                        else -> LineState.Upcoming
                    },
                    large = large,
                    onClick = if (lyrics.synced && line.timeMs != null) ({ onLineClick(line) }) else null,
                    effect = if (index == active && fx != null) fx.moods.getOrNull(index)?.let { LineEffect(it, fx, clock, accent) } else null,
                )
            }
        }
    }
}

private enum class LineState { Active, Past, Upcoming, Plain }

/** What the sung line should do: its mood, the song's hits and a frame-accurate playback clock. */
private class LineEffect(val mood: LineMood, val fx: LyricsFx, val clock: LongState, val accent: Color)

/**
 * The playback position advanced every frame between the player's updates, while [running]; it is
 * read only while drawing, so effects animate without recomposing the lyrics.
 */
@Composable
private fun rememberSmoothPosition(positionMs: Long, running: Boolean): LongState {
    val smooth = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, running) {
        smooth.longValue = positionMs
        if (!running) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> smooth.longValue = positionMs + (now - start) / 1_000_000 }
        }
    }
    return smooth
}

@Composable
private fun LyricRow(line: LyricLine, state: LineState, large: Boolean, onClick: (() -> Unit)?, effect: LineEffect? = null) {
    val reduced = LocalReducedMotion.current
    val alpha by animateFloatAsState(
        when (state) {
            LineState.Active -> 1f
            LineState.Past -> 0.32f
            LineState.Upcoming -> 0.5f
            LineState.Plain -> 0.92f
        },
        Motion.tween(Motion.MEDIUM, reduced),
        label = "lyricAlpha",
    )
    val mood = effect?.mood
    val lift = when (mood) {
        LineMood.Strong -> 1.03f
        LineMood.Peak -> 1.05f
        else -> 1f
    }
    val scale by animateFloatAsState(if (state == LineState.Active) lift else 0.96f, Motion.tween(Motion.MEDIUM, reduced), label = "lyricScale")
    val base = if (large) {
        AuraTheme.type.headline.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
    } else {
        AuraTheme.type.headline.copy(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    }
    // A glow in the song's colour, softer on calm parts and brighter as the music builds.
    val glowColor = effect?.accent?.takeIf { it != Color.Unspecified } ?: AuraTheme.colors.accent
    val style = if (mood == null) {
        base
    } else {
        val (glowAlpha, blur) = when (mood) {
            LineMood.Calm -> 0.35f to 16f
            LineMood.Flow -> 0.5f to 22f
            LineMood.Strong -> 0.7f to 28f
            LineMood.Peak -> 0.9f to 36f
        }
        base.copy(shadow = Shadow(glowColor.copy(alpha = glowAlpha), Offset.Zero, blur))
    }
    val shakePx = with(LocalDensity.current) { SHAKE_DP.dp.toPx() }
    val description = stringResource(R.string.cd_lyric_line, line.text)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(Radius.small)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(role = Role.Button, onClick = onClick)
                        .semantics { onClick(label = description) { onClick(); true } }
                } else {
                    Modifier
                },
            )
            .padding(vertical = if (large) Spacing.x3 else Spacing.x2, horizontal = Spacing.x1)
            .graphicsLayer {
                this.alpha = alpha
                var s = scale
                if (effect != null && !reduced) {
                    val now = effect.clock.longValue
                    when (effect.mood) {
                        // Calm parts breathe slowly; the rest beat with the song's hits.
                        LineMood.Calm -> s *= 1f + BREATH * sin(2 * PI * now / BREATH_PERIOD_MS).toFloat()
                        else -> {
                            val pulse = effect.fx.profile.pulseAt(now) * LyricMoods.pulseWeight(effect.mood)
                            s *= 1f + PULSE * pulse
                            if (effect.mood == LineMood.Peak && pulse > SHAKE_FROM) {
                                val shake = (pulse - SHAKE_FROM) / (1f - SHAKE_FROM)
                                translationX = shakePx * shake * sin(now * 0.09).toFloat()
                                translationY = shakePx * 0.5f * shake * cos(now * 0.13).toFloat()
                            }
                        }
                    }
                }
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
    ) {
        if (line.text.isBlank()) {
            Text("• • •", style = style.copy(fontSize = style.fontSize * 0.7f), color = AuraTheme.colors.textSecondary)
        } else {
            Text(line.text, style = style, color = if (state == LineState.Active) AuraTheme.colors.textPrimary else AuraTheme.colors.textPrimary.copy(alpha = 0.9f))
        }
    }
}

private const val BREATH = 0.012f
private const val BREATH_PERIOD_MS = 3_200.0
private const val PULSE = 0.06f
private const val SHAKE_FROM = 0.55f
private const val SHAKE_DP = 3f

/**
 * Shown when a track has no lyrics: while the app looks online by itself it says so; otherwise it
 * offers searching online, importing a file or writing them.
 */
@Composable
fun LyricsEmptyState(
    searching: Boolean,
    onSearchOnline: () -> Unit,
    onPickFile: () -> Unit,
    onWrite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (searching) {
            CircularProgressIndicator(color = AuraTheme.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(Spacing.x4))
            Text(stringResource(R.string.lyrics_searching), style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary)
            return@Column
        }
        EmptyState(AuraIcons.Lyrics, stringResource(R.string.lyrics_empty_title), stringResource(R.string.lyrics_empty_body))
        AuraButton(stringResource(R.string.lyrics_search_online), onSearchOnline, icon = AuraIcons.Search)
        Spacer(Modifier.height(Spacing.x3))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
            AuraButton(stringResource(R.string.lyrics_pick_file), onPickFile, icon = AuraIcons.FileText, style = ButtonStyle.Secondary)
            AuraButton(stringResource(R.string.lyrics_write), onWrite, icon = AuraIcons.Edit, style = ButtonStyle.Secondary)
        }
    }
}

/** − value + control for the per-track sync offset, in 0.1 s steps. */
@Composable
fun OffsetControl(offsetMs: Long, onNudge: (Long) -> Unit, onReset: () -> Unit, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.x1)) {
        Text(stringResource(R.string.lyrics_sync), style = AuraTheme.type.label, color = AuraTheme.colors.textSecondary)
        com.aura.music.core.designsystem.components.AuraIconButton(AuraIcons.Minus, stringResource(R.string.cd_lyrics_later), { onNudge(-STEP_MS) }, iconSize = 18.dp)
        Text(
            stringResource(R.string.lyrics_offset_value, formatOffset(offsetMs)),
            style = AuraTheme.type.numeric,
            color = if (offsetMs == 0L) AuraTheme.colors.textSecondary else accent,
            modifier = Modifier.width(52.dp).heightIn(min = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        com.aura.music.core.designsystem.components.AuraIconButton(AuraIcons.Plus, stringResource(R.string.cd_lyrics_earlier), { onNudge(STEP_MS) }, iconSize = 18.dp)
        if (offsetMs != 0L) {
            Text(
                stringResource(R.string.lyrics_offset_reset),
                style = AuraTheme.type.label,
                color = AuraTheme.colors.textSecondary,
                modifier = Modifier.clip(Radius.pill).clickable(role = Role.Button, onClick = onReset).padding(Spacing.x2),
            )
        }
    }
}

fun formatOffset(ms: Long): String {
    val sign = if (ms > 0) "+" else if (ms < 0) "−" else ""
    val abs = kotlin.math.abs(ms)
    return "$sign${abs / 1000},${(abs % 1000) / 100}"
}

private const val STEP_MS = 100L
private const val MANUAL_SCROLL_HOLD_MS = 2_500L
