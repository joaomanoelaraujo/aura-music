package com.aura.music.feature.recap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraChip
import com.aura.music.core.designsystem.components.AuraTopBar
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.components.LoadingState
import com.aura.music.core.designsystem.components.SectionHeader
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.data.repository.Recap
import com.aura.music.data.repository.RecapPeriod
import com.aura.music.data.repository.RecapRepository
import com.aura.music.core.designsystem.components.Artwork
import com.aura.music.navigation.LocalNavigator
import com.aura.music.playback.controller.PlayerConnection
import com.aura.music.ui.LocalBottomInset
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecapViewModel @Inject constructor(
    private val repository: RecapRepository,
    private val player: PlayerConnection,
) : ViewModel() {
    val period = MutableStateFlow(RecapPeriod.Month)

    /** Null while computing. */
    val recap: StateFlow<Recap?> = period.flatMapLatest { p -> flow { emit(null); emit(repository.recap(p)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state = combine(period, recap) { p, r -> p to r }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecapPeriod.Month to null)

    fun playTop() {
        val tracks = recap.value?.topTracks?.map { it.track } ?: return
        player.play(tracks, 0)
    }

    fun play(index: Int) {
        val tracks = recap.value?.topTracks?.map { it.track } ?: return
        player.play(tracks, index)
    }
}

/** "Your look back": how much, what, who, when and in which mood you listened. */
@Composable
fun RecapScreen(viewModel: RecapViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val (period, recap) = viewModel.state.collectAsStateWithLifecycle().value
    Column(Modifier.fillMaxSize()) {
        AuraTopBar(stringResource(R.string.recap_title), onBack = { navigator.back() })
        Row(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            RecapPeriod.entries.forEach { p ->
                AuraChip(stringResource(periodLabel(p)), selected = p == period, onClick = { viewModel.period.value = p })
            }
        }
        when {
            recap == null -> LoadingState()
            recap.isEmpty -> EmptyState(AuraIcons.History, stringResource(R.string.recap_empty_title), stringResource(R.string.recap_empty_body))
            else -> RecapContent(recap, viewModel)
        }
    }
}

private fun periodLabel(p: RecapPeriod) = when (p) {
    RecapPeriod.Month -> R.string.recap_month
    RecapPeriod.Year -> R.string.recap_year
    RecapPeriod.AllTime -> R.string.recap_all_time
}

@Composable
private fun RecapContent(recap: Recap, viewModel: RecapViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x6)) {
        item(key = "hero") { Hero(recap) }
        item(key = "stats") {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
                StatTile(recap.plays.toString(), pluralStringResource(R.plurals.recap_plays, recap.plays), Modifier.weight(1f))
                StatTile(recap.songs.toString(), pluralStringResource(R.plurals.recap_songs, recap.songs), Modifier.weight(1f))
                StatTile(recap.artists.toString(), pluralStringResource(R.plurals.recap_artists, recap.artists), Modifier.weight(1f))
            }
        }
        if (recap.topTracks.isNotEmpty()) {
            item(key = "tracksHeader") { SectionHeader(stringResource(R.string.recap_top_tracks), actionLabel = stringResource(R.string.recap_play_all), onAction = viewModel::playTop) }
            itemsIndexed(recap.topTracks, key = { _, t -> "t${t.track.id}" }) { i, ranked ->
                RankRow(i + 1, ranked.track.title, ranked.track.artist.orEmpty(), ranked.plays, ranked.track, onClick = { viewModel.play(i) })
            }
        }
        if (recap.topArtists.isNotEmpty()) {
            item(key = "artistsHeader") { SectionHeader(stringResource(R.string.recap_top_artists)) }
            itemsIndexed(recap.topArtists, key = { _, a -> "a${a.name}" }) { i, ranked ->
                RankRow(i + 1, ranked.name, null, ranked.plays, ranked.sample, onClick = null)
            }
        }
        item(key = "hoursHeader") { SectionHeader(stringResource(R.string.recap_when)) }
        item(key = "hours") { HourChart(recap.byHour) }
        recap.busiestDay?.let { day ->
            item(key = "day") {
                Text(
                    stringResource(R.string.recap_busiest_day, DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())),
                    style = AuraTheme.type.body,
                    color = AuraTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
                )
            }
        }
        if (recap.analyzedPlays > 0) {
            item(key = "moodHeader") { SectionHeader(stringResource(R.string.recap_mood)) }
            item(key = "mood") { Mood(recap) }
        }
    }
}

@Composable
private fun Hero(recap: Recap) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.x4)) {
        Text(stringResource(R.string.recap_listened), style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary)
        val hours = recap.minutes / 60
        Text(
            if (hours >= 10) pluralStringResource(R.plurals.recap_hours, hours.toInt(), hours) else pluralStringResource(R.plurals.recap_minutes, recap.minutes.toInt(), recap.minutes),
            style = AuraTheme.type.display,
            color = AuraTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(Radius.medium).background(AuraTheme.colors.surface).padding(Spacing.x3)) {
        Text(value, style = AuraTheme.type.headline, color = AuraTheme.colors.textPrimary, maxLines = 1)
        Text(label, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun RankRow(rank: Int, title: String, subtitle: String?, plays: Int, artwork: com.aura.music.core.model.Track, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(rank.toString(), style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textTertiary, modifier = Modifier.width(28.dp))
        Artwork(artwork.artwork, Modifier.size(48.dp), shape = Radius.small, placeholderSeed = title)
        Column(Modifier.weight(1f).padding(horizontal = Spacing.x3)) {
            Text(title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(pluralStringResource(R.plurals.recap_play_count, plays, plays), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
    }
}

/**
 * Plays per hour of the day: one series in one colour, thin bars with rounded tops anchored to the
 * baseline, a 2 dp gap between bars, the peak labelled, hours marked every 6 h. Screen readers get
 * the full numbers.
 */
@Composable
private fun HourChart(byHour: IntArray) {
    val max = byHour.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peak = byHour.indices.maxByOrNull { byHour[it] } ?: 0
    val bar = AuraTheme.colors.accent
    val track = AuraTheme.colors.surface
    val description = stringResource(R.string.recap_when_a11y, byHour.indices.filter { byHour[it] > 0 }.joinToString { "${it}h: ${byHour[it]}" })
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        Text(
            stringResource(R.string.recap_peak_hour, peak, pluralStringResource(R.plurals.recap_play_count, byHour[peak], byHour[peak])),
            style = AuraTheme.type.bodySmall,
            color = AuraTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.x2))
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
            val gap = 2.dp.toPx()
            val width = (size.width - gap * 23) / 24
            val radius = CornerRadius(minOf(4.dp.toPx(), width / 2))
            for (h in 0 until 24) {
                val x = h * (width + gap)
                drawRoundRect(track, Offset(x, 0f), Size(width, size.height), radius, alpha = 0.5f)
                val height = size.height * byHour[h] / max
                if (height > 0) {
                    // Rounded at the top only: the square bottom sits on the baseline.
                    clipRect(left = x, top = size.height - height, right = x + width, bottom = size.height) {
                        drawRoundRect(bar, Offset(x, size.height - height), Size(width, height + radius.y), radius)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.x1)) {
            listOf(0, 6, 12, 18).forEach { h ->
                Text("${h}h", style = AuraTheme.type.label, color = AuraTheme.colors.textTertiary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Mood(recap: Recap) {
    val calm = (recap.calmShare * 100).toInt()
    val energy = (recap.energyShare * 100).toInt()
    Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2)) {
        Text(stringResource(R.string.recap_mood_calm, calm), style = AuraTheme.type.body, color = AuraTheme.colors.textPrimary)
        Text(stringResource(R.string.recap_mood_energy, energy), style = AuraTheme.type.body, color = AuraTheme.colors.textPrimary)
        Text(stringResource(R.string.recap_mood_between, (100 - calm - energy).coerceAtLeast(0)), style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary)
    }
}
