package com.aura.music.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraChip
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraTopBar
import com.aura.music.core.designsystem.components.ConfirmDialog
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.components.PageTitle
import com.aura.music.core.designsystem.components.TextInputDialog
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.AlbumSort
import com.aura.music.core.model.ArtistSort
import com.aura.music.core.model.CornerStyle
import com.aura.music.core.model.CoverSize
import com.aura.music.core.model.DefaultRepeat
import com.aura.music.core.model.HomeSection
import com.aura.music.core.model.LibraryLayout
import com.aura.music.core.model.ListDensity
import com.aura.music.core.model.MiniPlayerStyle
import com.aura.music.core.model.MotionLevel
import com.aura.music.core.model.NowPlayingButton
import com.aura.music.core.model.OutputPreference
import com.aura.music.core.model.PreviousBehavior
import com.aura.music.core.model.SortSpec
import com.aura.music.core.model.TextScale
import com.aura.music.core.model.ThemeMode
import com.aura.music.core.model.TrackSort
import com.aura.music.data.local.AppSettings
import com.aura.music.data.backup.BackupResult
import com.aura.music.data.lyrics.LyricsFolderLabel
import com.aura.music.navigation.LocalNavigator
import com.aura.music.navigation.SettingsSection
import com.aura.music.playback.eq.EqPreset
import com.aura.music.ui.LocalBottomInset
import com.aura.music.ui.LocalMessenger

@Composable
fun SettingsSectionScreen(section: SettingsSection, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val s = state.settings
    Column(Modifier.fillMaxSize()) {
        AuraTopBar(title = null, onBack = { navigator.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x8)) {
            item { PageTitle(stringResource(section.titleRes)) }
            when (section) {
                SettingsSection.Appearance -> appearance(s, viewModel)
                SettingsSection.Home -> home(s, viewModel)
                SettingsSection.Playback -> playback(s, viewModel)
                SettingsSection.Equalizer -> item { EqualizerContent(viewModel) }
                SettingsSection.Library -> library(state, viewModel)
                SettingsSection.Folders -> folders(state, viewModel)
                SettingsSection.Hidden -> hidden(state, viewModel)
                SettingsSection.Lyrics -> item { LyricsFoldersContent(s, viewModel) }
                SettingsSection.Privacy -> item { PrivacyContent(s, viewModel) }
                SettingsSection.Backup -> item { BackupContent(viewModel) }
            }
        }
    }
}

private val SettingsSection.titleRes: Int
    get() = when (this) {
        SettingsSection.Appearance -> R.string.settings_appearance
        SettingsSection.Home -> R.string.settings_home
        SettingsSection.Playback -> R.string.settings_playback
        SettingsSection.Equalizer -> R.string.player_equalizer
        SettingsSection.Library -> R.string.settings_library
        SettingsSection.Folders -> R.string.settings_folders
        SettingsSection.Hidden -> R.string.settings_hidden
        SettingsSection.Lyrics -> R.string.settings_lyrics
        SettingsSection.Privacy -> R.string.settings_privacy
        SettingsSection.Backup -> R.string.settings_backup
    }

private fun LazyListScope.appearance(s: AppSettings, vm: SettingsViewModel) {
    item { SettingsSection(stringResource(R.string.settings_theme_section)) }
    item {
        ChoiceRow(stringResource(R.string.settings_theme), null, ThemeMode.entries, s.themeMode, { stringResource(it.labelRes) }, vm::setThemeMode)
    }
    item {
        SwitchRow(stringResource(R.string.settings_atmosphere), stringResource(R.string.settings_atmosphere_body), s.adaptiveAtmosphere, vm::setAdaptiveAtmosphere)
    }
    item {
        PercentSliderRow(
            stringResource(R.string.settings_atmosphere_intensity),
            stringResource(R.string.settings_atmosphere_intensity_body),
            s.atmosphereIntensity,
            vm::setAtmosphereIntensity,
            enabled = s.adaptiveAtmosphere,
        )
    }
    item {
        PercentSliderRow(stringResource(R.string.settings_gradient_intensity), stringResource(R.string.settings_gradient_intensity_body), s.gradientIntensity, vm::setGradientIntensity)
    }

    item { SettingsSection(stringResource(R.string.settings_layout_section)) }
    item { ChoiceRow(stringResource(R.string.settings_corners), null, CornerStyle.entries, s.cornerStyle, { stringResource(it.labelRes) }, vm::setCornerStyle) }
    item { ChoiceRow(stringResource(R.string.settings_cover_size), null, CoverSize.entries, s.coverSize, { stringResource(it.labelRes) }, vm::setCoverSize) }
    item {
        ChoiceRow(stringResource(R.string.settings_album_layout), null, LibraryLayout.entries, s.albumLayout, { stringResource(it.labelRes) }, vm::setAlbumLayout)
    }
    item {
        ChoiceRow(stringResource(R.string.settings_artist_layout), null, LibraryLayout.entries, s.artistLayout, { stringResource(it.labelRes) }, vm::setArtistLayout)
    }
    item { ChoiceRow(stringResource(R.string.settings_density), null, ListDensity.entries, s.listDensity, { stringResource(it.labelRes) }, vm::setListDensity) }
    item {
        ChoiceRow(
            stringResource(R.string.settings_text_size),
            stringResource(R.string.settings_text_size_body),
            TextScale.options,
            TextScale.options.minBy { kotlin.math.abs(it - s.textScale) },
            { "${(it * 100).toInt()}%" },
            vm::setTextScale,
        )
    }

    item { SettingsSection(stringResource(R.string.settings_motion_section)) }
    item {
        ChoiceRow(stringResource(R.string.settings_motion), stringResource(R.string.settings_motion_body), MotionLevel.entries, s.motion, { stringResource(it.labelRes) }, vm::setMotion)
    }

    item { SettingsSection(stringResource(R.string.settings_player_section)) }
    item {
        ChoiceRow(stringResource(R.string.settings_mini_style), null, MiniPlayerStyle.entries, s.miniPlayerStyle, { stringResource(it.labelRes) }, vm::setMiniPlayerStyle)
    }
    item {
        SwitchRow(stringResource(R.string.settings_mini_swipe), stringResource(R.string.settings_mini_swipe_body), s.miniPlayerSwipe, vm::setMiniPlayerSwipe)
    }
    item {
        MultiChoiceRow(
            stringResource(R.string.settings_now_playing_buttons),
            stringResource(R.string.settings_now_playing_buttons_body),
            NowPlayingButton.entries,
            s.nowPlayingButtons,
            { stringResource(it.labelRes) },
            vm::toggleNowPlayingButton,
        )
    }
}

private fun LazyListScope.home(s: AppSettings, vm: SettingsViewModel) {
    item { NoteText(stringResource(R.string.settings_home_body)) }
    itemsIndexed(s.homeSections, key = { _, pref -> pref.section.name }) { index, pref ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = Spacing.gutter, end = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(pref.section.labelRes),
                style = AuraTheme.type.titleSmall,
                color = if (pref.visible) AuraTheme.colors.textPrimary else AuraTheme.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            AuraIconButton(
                AuraIcons.ArrowUp,
                stringResource(R.string.a11y_move_up),
                onClick = { vm.setHomeSections(s.homeSections.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                enabled = index > 0,
                iconSize = 20.dp,
            )
            AuraIconButton(
                AuraIcons.ArrowDown,
                stringResource(R.string.a11y_move_down),
                onClick = { vm.setHomeSections(s.homeSections.toMutableList().apply { add(index + 1, removeAt(index)) }) },
                enabled = index < s.homeSections.lastIndex,
                iconSize = 20.dp,
            )
            AuraIconButton(
                if (pref.visible) AuraIcons.Eye else AuraIcons.EyeOff,
                stringResource(if (pref.visible) R.string.settings_home_hide else R.string.settings_home_show, stringResource(pref.section.labelRes)),
                onClick = { vm.setHomeSections(s.homeSections.map { if (it.section == pref.section) it.copy(visible = !it.visible) else it }) },
                tint = if (pref.visible) AuraTheme.colors.accent else AuraTheme.colors.textTertiary,
                iconSize = 20.dp,
            )
        }
    }
}

private fun LazyListScope.playback(s: AppSettings, vm: SettingsViewModel) {
    item { SettingsSection(stringResource(R.string.settings_session_section)) }
    item { SwitchRow(stringResource(R.string.settings_resume), stringResource(R.string.settings_resume_body), s.resumeLastSession, vm::setResumeLastSession) }
    item {
        ChoiceRow(
            stringResource(R.string.settings_default_repeat),
            stringResource(R.string.settings_default_repeat_body),
            DefaultRepeat.entries,
            s.defaultRepeat,
            { stringResource(it.labelRes) },
            vm::setDefaultRepeat,
        )
    }
    item { SwitchRow(stringResource(R.string.settings_keep_shuffle), stringResource(R.string.settings_keep_shuffle_body), s.keepShuffle, vm::setKeepShuffle) }
    item {
        ChoiceRow(stringResource(R.string.settings_previous), null, PreviousBehavior.entries, s.previousBehavior, { stringResource(it.labelRes) }, vm::setPreviousBehavior)
    }
    item { SwitchRow(stringResource(R.string.settings_skip_silence), stringResource(R.string.settings_skip_silence_body), s.skipSilence, vm::setSkipSilence) }
    item { SwitchRow(stringResource(R.string.settings_normalize), stringResource(R.string.settings_normalize_body), s.normalizeVolume, vm::setNormalizeVolume) }
    item {
        ChoiceRow(
            stringResource(R.string.settings_transition),
            stringResource(R.string.settings_transition_body),
            listOf(0, 2, 4, 6, 8, 12),
            s.transitionSeconds,
            { if (it == 0) stringResource(R.string.settings_transition_off) else stringResource(R.string.settings_transition_seconds, it) },
            vm::setTransitionSeconds,
        )
    }
    item { SwitchRow(stringResource(R.string.settings_analyze_library), stringResource(R.string.settings_analyze_library_body), s.analyzeLibrary, vm::setAnalyzeLibrary) }
    item { InfoRow(stringResource(R.string.settings_gapless), stringResource(R.string.settings_gapless_value)) }
    item { NoteText(stringResource(R.string.settings_gapless_body)) }

    item { SettingsSection(stringResource(R.string.settings_output_section)) }
    item {
        SwitchRow(stringResource(R.string.settings_pause_disconnect), stringResource(R.string.settings_pause_disconnect_body), s.pauseOnDisconnect, vm::setPauseOnDisconnect)
    }
    item {
        SwitchRow(
            stringResource(R.string.settings_resume_connect),
            stringResource(R.string.settings_resume_connect_body),
            s.resumeOnConnect,
            vm::setResumeOnConnect,
            enabled = s.pauseOnDisconnect,
        )
    }
    item {
        ChoiceRow(
            stringResource(R.string.settings_output),
            stringResource(R.string.settings_output_body),
            OutputPreference.entries,
            s.outputPreference,
            { stringResource(it.labelRes) },
            vm::setOutputPreference,
        )
    }
    item {
        val navigator = LocalNavigator.current
        ActionRow(AuraIcons.ChevronRight, stringResource(R.string.player_equalizer), stringResource(R.string.settings_equalizer_summary), onClick = {
            navigator.openSettingsSection(SettingsSection.Equalizer)
        })
    }
    item { NoteText(stringResource(R.string.settings_sleep_note)) }
}

@Composable
private fun EqualizerContent(vm: SettingsViewModel) {
    val eq by vm.equalizerState.collectAsStateWithLifecycle()
    val info = eq.info ?: return
    Column {
        SwitchRow(stringResource(R.string.eq_enabled), stringResource(R.string.eq_enabled_body), eq.settings.enabled, vm::setEqualizerEnabled)
        SettingsSection(stringResource(R.string.eq_presets))
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
            verticalArrangement = Arrangement.spacedBy(Spacing.x2),
        ) {
            info.presets.forEachIndexed { index, preset ->
                AuraChip(stringResource(presetName(preset)), selected = eq.settings.preset == index, onClick = { vm.useEqualizerPreset(index) })
            }
            if (eq.settings.preset < 0 && eq.settings.levels.any { it != 0 }) AuraChip(stringResource(R.string.eq_custom), selected = true, onClick = {})
        }
        SettingsSection(stringResource(R.string.eq_bands))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).alpha(if (eq.settings.enabled) 1f else 0.45f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.x1),
        ) {
            info.bands.forEach { band ->
                VerticalBandSlider(
                    label = if (band.centerHz >= 1000) "${band.centerHz / 1000}k" else "${band.centerHz}",
                    description = if (band.centerHz >= 1000) "${band.centerHz / 1000} kHz" else "${band.centerHz} Hz",
                    level = eq.settings.levels.getOrNull(band.index) ?: 0,
                    min = info.minLevel,
                    max = info.maxLevel,
                    enabled = eq.settings.enabled,
                    onCommit = { vm.setBandLevel(band.index, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(Spacing.x3))
        ActionRow(AuraIcons.Refresh, stringResource(R.string.eq_reset), null, onClick = vm::resetEqualizer)
        NoteText(stringResource(R.string.eq_note))
    }
}

private fun presetName(preset: EqPreset): Int = when (preset) {
    EqPreset.Flat -> R.string.eq_preset_flat
    EqPreset.BassBoost -> R.string.eq_preset_bass
    EqPreset.Rock -> R.string.eq_preset_rock
    EqPreset.Pop -> R.string.eq_preset_pop
    EqPreset.Jazz -> R.string.eq_preset_jazz
    EqPreset.Classical -> R.string.eq_preset_classical
    EqPreset.Vocal -> R.string.eq_preset_vocal
    EqPreset.Electronic -> R.string.eq_preset_electronic
    EqPreset.Acoustic -> R.string.eq_preset_acoustic
    EqPreset.TrebleBoost -> R.string.eq_preset_treble
}

/**
 * One band of the graphic equalizer: drag (or tap) along the column; the value snaps to 0.5 dB and
 * is saved when the finger lifts. The bar grows from the 0 dB line, up for boost, down for cut.
 */
@Composable
private fun VerticalBandSlider(
    label: String,
    description: String,
    level: Int,
    min: Int,
    max: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) level.toFloat() else dragging
    val db = shown / 100f
    val accent = AuraTheme.colors.accent
    val track = AuraTheme.colors.surfaceElevated
    val zeroLine = AuraTheme.colors.textTertiary
    fun levelAt(y: Float, height: Float): Float {
        val fraction = 1f - (y / height).coerceIn(0f, 1f)
        val raw = min + fraction * (max - min)
        return (Math.round(raw / STEP_MB) * STEP_MB).toFloat()
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (db == 0f) "0" else "%+.1f".format(java.util.Locale.ROOT, db),
            style = AuraTheme.type.label,
            color = if (db == 0f) AuraTheme.colors.textTertiary else AuraTheme.colors.textPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.height(Spacing.x2))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(BAND_HEIGHT)
                .semantics {
                    contentDescription = description
                    stateDescription = "%+.1f dB".format(java.util.Locale.ROOT, db)
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, min.toFloat()..max.toFloat(), steps = (max - min) / STEP_MB - 1)
                    if (enabled) {
                        setProgress { target ->
                            onCommit((Math.round(target / STEP_MB) * STEP_MB).coerceIn(min, max))
                            true
                        }
                    }
                }
                .pointerInput(enabled, min, max) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset -> onCommit(levelAt(offset.y, size.height.toFloat()).toInt()) }
                }
                .pointerInput(enabled, min, max) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = { offset -> dragging = levelAt(offset.y, size.height.toFloat()) },
                        onDragEnd = {
                            if (!dragging.isNaN()) onCommit(dragging.toInt())
                            dragging = Float.NaN
                        },
                        onDragCancel = { dragging = Float.NaN },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            dragging = levelAt(change.position.y, size.height.toFloat())
                        },
                    )
                },
        ) {
            val barWidth = 6.dp.toPx()
            val x = size.width / 2
            val yOf = { value: Float -> size.height * (1f - (value - min) / (max - min)) }
            val zero = yOf(0f)
            val y = yOf(shown)
            drawLine(track, Offset(x, 0f), Offset(x, size.height), strokeWidth = barWidth, cap = StrokeCap.Round)
            drawLine(zeroLine, Offset(x - barWidth * 1.6f, zero), Offset(x + barWidth * 1.6f, zero), strokeWidth = 1.dp.toPx())
            if (shown != 0f) drawLine(accent, Offset(x, zero), Offset(x, y), strokeWidth = barWidth, cap = StrokeCap.Round)
            drawCircle(accent, radius = 9.dp.toPx(), center = Offset(x, y))
        }
        Spacer(Modifier.height(Spacing.x2))
        Text(label, style = AuraTheme.type.label, color = AuraTheme.colors.textSecondary, maxLines = 1)
    }
}

private val BAND_HEIGHT = 200.dp
private const val STEP_MB = 50

private fun LazyListScope.library(state: SettingsUiState, vm: SettingsViewModel) {
    val s = state.settings
    item { SwitchRow(stringResource(R.string.settings_online_covers), stringResource(R.string.settings_online_covers_body), s.onlineCovers, vm::setOnlineCovers) }
    item {
        val messenger = LocalMessenger.current
        val resources = LocalResources.current
        ActionRow(
            AuraIcons.Refresh,
            stringResource(R.string.settings_rescan),
            stringResource(R.string.settings_rescan_body),
            enabled = !state.isScanning,
            onClick = { vm.rescan { messenger.show(resources.getString(R.string.settings_rescan_done)) } },
        )
    }
    item {
        ChoiceRow(
            stringResource(R.string.settings_min_duration),
            stringResource(R.string.settings_min_duration_body),
            SettingsViewModel.MinDurationOptions,
            s.minTrackDurationSec,
            { if (it == 0) stringResource(R.string.settings_min_duration_off) else stringResource(R.string.settings_min_duration_value, it) },
            vm::setMinTrackDuration,
        )
    }

    item { SettingsSection(stringResource(R.string.settings_smart_library)) }
    item { SwitchRow(stringResource(R.string.settings_hide_whatsapp), stringResource(R.string.settings_hide_whatsapp_body), s.hideWhatsApp, vm::setHideWhatsApp) }
    item { SwitchRow(stringResource(R.string.settings_hide_timestamp_titles), stringResource(R.string.settings_hide_timestamp_titles_body), s.hideTimestampTitles, vm::setHideTimestampTitles) }
    item { SwitchRow(stringResource(R.string.settings_show_untagged), stringResource(R.string.settings_show_untagged_body), s.showUntagged, vm::setShowUntagged) }
    item {
        val navigator = LocalNavigator.current
        val excluded = s.excludedFolders.size
        ActionRow(
            AuraIcons.Folder,
            stringResource(R.string.settings_folders),
            if (excluded == 0) stringResource(R.string.settings_folders_all) else pluralStringResource(R.plurals.settings_folders_excluded, excluded, excluded),
            onClick = { navigator.openSettingsSection(SettingsSection.Folders) },
        )
    }
    item {
        val navigator = LocalNavigator.current
        ActionRow(
            AuraIcons.EyeOff,
            stringResource(R.string.settings_hidden),
            pluralStringResource(R.plurals.settings_hidden_count, state.hidden.size, state.hidden.size),
            onClick = { navigator.openSettingsSection(SettingsSection.Hidden) },
        )
    }
    if (state.folderRules.isNotEmpty()) {
        item { SettingsSection(stringResource(R.string.settings_folder_rules)) }
        items(state.folderRules, key = { "rule${it.path}" }) { rule ->
            Row(Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = Spacing.x2)) {
                    Text(rule.path.trimEnd('/'), style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.settings_folder_rule_music), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
                }
                AuraIconButton(AuraIcons.Close, stringResource(R.string.settings_folder_rule_remove, rule.path), onClick = { vm.removeFolderRule(rule) }, iconSize = 18.dp)
            }
        }
    }

    item { SettingsSection(stringResource(R.string.settings_sorting)) }
    item {
        SortChoice(stringResource(R.string.library_songs), TrackSort.entries, s.trackSort, { stringResource(it.labelRes) }, vm::setTrackSort)
    }
    item {
        SortChoice(stringResource(R.string.library_albums), AlbumSort.entries, s.albumSort, { stringResource(it.labelRes) }, vm::setAlbumSort)
    }
    item {
        SortChoice(stringResource(R.string.library_artists), ArtistSort.entries, s.artistSort, { stringResource(it.labelRes) }, vm::setArtistSort)
    }

    item { SettingsSection(stringResource(R.string.settings_artwork)) }
    item {
        val messenger = LocalMessenger.current
        val resources = LocalResources.current
        ActionRow(AuraIcons.Image, stringResource(R.string.settings_refresh_artwork), stringResource(R.string.settings_refresh_artwork_body), onClick = {
            vm.refreshArtwork { messenger.show(resources.getString(R.string.settings_refresh_artwork_done)) }
        })
    }
    item {
        val messenger = LocalMessenger.current
        val resources = LocalResources.current
        ActionRow(AuraIcons.Trash, stringResource(R.string.settings_clear_image_cache), stringResource(R.string.settings_clear_image_cache_body), onClick = {
            vm.clearImageCache { messenger.show(resources.getString(R.string.settings_clear_image_cache_done)) }
        })
    }
}

@Composable
private fun <T : Enum<T>> SortChoice(title: String, keys: List<T>, current: SortSpec<T>, label: @Composable (T) -> String, onSelect: (SortSpec<T>) -> Unit) {
    ChoiceRow(
        title = title,
        body = null,
        options = keys.flatMap { listOf(SortSpec(it, true), SortSpec(it, false)) },
        selected = current,
        label = { spec -> label(spec.key) + if (spec.ascending) " ↑" else " ↓" },
        onSelect = onSelect,
    )
}

private fun LazyListScope.folders(state: SettingsUiState, vm: SettingsViewModel) {
    item { NoteText(stringResource(R.string.settings_folders_body)) }
    if (state.folders.isEmpty()) {
        item { EmptyState(AuraIcons.Folder, stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body)) }
    }
    items(state.folders, key = { it }) { path ->
        SwitchRow(
            title = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
            body = path.trimEnd('/'),
            checked = state.settings.excludedFolders.none { path.startsWith(it) },
            onChange = { vm.setFolderIncluded(path, it) },
            enabled = state.settings.excludedFolders.none { it != path && path.startsWith(it) },
        )
    }
}

private fun LazyListScope.hidden(state: SettingsUiState, vm: SettingsViewModel) {
    item { NoteText(stringResource(R.string.settings_hidden_body)) }
    if (state.hidden.isEmpty()) {
        item { EmptyState(AuraIcons.Eye, stringResource(R.string.settings_hidden_empty), stringResource(R.string.settings_hidden_empty_body)) }
    }
    items(state.hidden, key = { it.id }) { track ->
        ActionRow(AuraIcons.Eye, track.title, track.folder + track.displayName, onClick = { vm.unhide(track) })
    }
}

@Composable
private fun LyricsFoldersContent(s: AppSettings, vm: SettingsViewModel) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addLyricsFolder(uri) }
    Column {
        SwitchRow(stringResource(R.string.settings_lyrics_online), stringResource(R.string.settings_lyrics_online_body), s.onlineLyrics, vm::setOnlineLyrics)
        SwitchRow(stringResource(R.string.settings_lyrics_effects), stringResource(R.string.settings_lyrics_effects_body), s.lyricsEffects, vm::setLyricsEffects)
        NoteText(stringResource(R.string.settings_lyrics_body))
        s.lyricsFolders.forEach { uri ->
            Row(Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    LyricsFolderLabel.of(uri),
                    style = AuraTheme.type.titleSmall,
                    color = AuraTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AuraIconButton(AuraIcons.Close, stringResource(R.string.settings_lyrics_remove_folder), onClick = { vm.removeLyricsFolder(uri) }, iconSize = 18.dp)
            }
        }
        ActionRow(AuraIcons.Plus, stringResource(R.string.settings_lyrics_add_folder), null, onClick = { picker.launch(null) }, tint = AuraTheme.colors.accent)
        if (s.lyricsFolders.isNotEmpty()) {
            ActionRow(AuraIcons.Refresh, stringResource(R.string.settings_lyrics_reindex), stringResource(R.string.settings_lyrics_reindex_body), onClick = {
                vm.reindexLyrics { messenger.show(resources.getString(R.string.settings_lyrics_reindex_done)) }
            })
        }
        NoteText(stringResource(R.string.settings_lyrics_formats))
    }
}

@Composable
private fun BackupContent(vm: SettingsViewModel) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    val report: (BackupResult) -> Unit = { result ->
        messenger.show(
            when (result) {
                is BackupResult.Exported -> resources.getString(R.string.backup_exported, result.playlists, result.songs)
                is BackupResult.Restored -> resources.getString(R.string.backup_restored, result.playlists, result.favorites, result.lyrics, result.plays) +
                    if (result.missingSongs > 0) " " + resources.getString(R.string.backup_missing, result.missingSongs) else ""
                BackupResult.NotABackup -> resources.getString(R.string.backup_not_a_backup)
                BackupResult.LibraryNotReady -> resources.getString(R.string.backup_library_not_ready)
                is BackupResult.Failed -> resources.getString(R.string.backup_failed)
            },
        )
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri, report)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.restoreBackup(uri, report)
    }
    Column {
        NoteText(stringResource(R.string.backup_body))
        ActionRow(AuraIcons.Save, stringResource(R.string.backup_export), stringResource(R.string.backup_export_body), onClick = {
            if (!busy) exporter.launch("aura-backup-${java.time.LocalDate.now()}.json")
        })
        ActionRow(AuraIcons.Refresh, stringResource(R.string.backup_restore), stringResource(R.string.backup_restore_body), onClick = {
            if (!busy) importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
        })
        if (busy) NoteText(stringResource(R.string.backup_working))
        NoteText(stringResource(R.string.backup_note))
    }
}

@Composable
private fun PrivacyContent(s: AppSettings, vm: SettingsViewModel) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    var confirmClearPlays by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    Column {
        NoteText(stringResource(R.string.settings_privacy_body))
        SwitchRow(stringResource(R.string.settings_personalization), stringResource(R.string.settings_personalization_body), s.personalization, vm::setPersonalization)
        SwitchRow(stringResource(R.string.settings_search_history), stringResource(R.string.settings_search_history_body), s.searchHistoryEnabled, vm::setSearchHistoryEnabled)
        ActionRow(AuraIcons.Trash, stringResource(R.string.settings_clear_search_history), null, onClick = {
            vm.clearSearchHistory { messenger.show(resources.getString(R.string.settings_cleared)) }
        })
        ActionRow(AuraIcons.Trash, stringResource(R.string.settings_clear_play_history), stringResource(R.string.settings_clear_play_history_body), onClick = {
            confirmClearPlays = true
        }, tint = AuraTheme.colors.danger)
        SettingsSection(stringResource(R.string.settings_together_section))
        ActionRow(
            AuraIcons.Edit,
            stringResource(R.string.settings_display_name),
            s.displayName.ifBlank { stringResource(R.string.settings_display_name_unset) },
            onClick = { editingName = true },
        )
        SwitchRow(stringResource(R.string.settings_accept_invites), stringResource(R.string.settings_accept_invites_body), s.acceptInvites, vm::setAcceptInvites)
        SwitchRow(stringResource(R.string.settings_approve_joins), stringResource(R.string.settings_approve_joins_body), s.approveJoins, vm::setApproveJoins)
        ChoiceRow(
            stringResource(R.string.settings_together_mode),
            stringResource(R.string.settings_together_mode_body),
            listOf(true, false),
            s.togetherDemocratic,
            { stringResource(if (it) R.string.together_mode_democratic else R.string.together_mode_host) },
            vm::setTogetherDemocratic,
        )
        ChoiceRow(
            stringResource(R.string.settings_together_max),
            null,
            listOf(2, 4, 8, 12, 16),
            s.togetherMaxParticipants,
            { it.toString() },
            vm::setTogetherMaxParticipants,
        )
        NoteText(stringResource(R.string.settings_together_privacy))
    }
    if (editingName) {
        TextInputDialog(
            title = stringResource(R.string.settings_display_name),
            initialValue = s.displayName,
            placeholder = stringResource(R.string.together_your_name),
            confirmLabel = stringResource(R.string.action_save),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                vm.setDisplayName(it)
                editingName = false
            },
            onDismiss = { editingName = false },
        )
    }
    if (confirmClearPlays) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_play_history),
            message = stringResource(R.string.settings_clear_play_history_confirm),
            confirmLabel = stringResource(R.string.action_delete),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmClearPlays = false
                vm.clearPlayHistory { messenger.show(resources.getString(R.string.settings_cleared)) }
            },
            onDismiss = { confirmClearPlays = false },
        )
    }
}

// Labels

private val ThemeMode.labelRes get() = when (this) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Dark -> R.string.theme_dark
    ThemeMode.Light -> R.string.theme_light
}
private val CornerStyle.labelRes get() = when (this) {
    CornerStyle.Sharp -> R.string.corners_sharp
    CornerStyle.Soft -> R.string.corners_soft
    CornerStyle.Round -> R.string.corners_round
}
private val CoverSize.labelRes get() = when (this) {
    CoverSize.Small -> R.string.size_small
    CoverSize.Medium -> R.string.size_medium
    CoverSize.Large -> R.string.size_large
}
private val LibraryLayout.labelRes get() = when (this) {
    LibraryLayout.Grid -> R.string.layout_grid
    LibraryLayout.List -> R.string.layout_list
}
private val ListDensity.labelRes get() = when (this) {
    ListDensity.Comfortable -> R.string.density_comfortable
    ListDensity.Compact -> R.string.density_compact
}
private val MotionLevel.labelRes get() = when (this) {
    MotionLevel.Full -> R.string.motion_full
    MotionLevel.Reduced -> R.string.motion_reduced
    MotionLevel.Off -> R.string.motion_off
}
private val MiniPlayerStyle.labelRes get() = when (this) {
    MiniPlayerStyle.Floating -> R.string.mini_floating
    MiniPlayerStyle.Docked -> R.string.mini_docked
}
private val NowPlayingButton.labelRes get() = when (this) {
    NowPlayingButton.Lyrics -> R.string.player_lyrics
    NowPlayingButton.Queue -> R.string.player_queue
    NowPlayingButton.Details -> R.string.player_details
    NowPlayingButton.SleepTimer -> R.string.player_sleep_timer
    NowPlayingButton.Equalizer -> R.string.player_equalizer
}
private val HomeSection.labelRes get() = when (this) {
    HomeSection.Continue -> R.string.home_continue
    HomeSection.Shortcuts -> R.string.settings_home_shortcuts
    HomeSection.RecentTracks -> R.string.home_recent_tracks
    HomeSection.RecentAlbums -> R.string.home_recent_albums
    HomeSection.TopArtists -> R.string.home_top_artists
    HomeSection.Mixes -> R.string.home_mixes
    HomeSection.RecentlyAdded -> R.string.home_recently_added_tracks
}
private val DefaultRepeat.labelRes get() = when (this) {
    DefaultRepeat.Remember -> R.string.repeat_remember
    DefaultRepeat.Off -> R.string.repeat_off
    DefaultRepeat.All -> R.string.repeat_all
    DefaultRepeat.One -> R.string.repeat_one
}
private val PreviousBehavior.labelRes get() = when (this) {
    PreviousBehavior.RestartFirst -> R.string.previous_restart
    PreviousBehavior.AlwaysPrevious -> R.string.previous_always
}
private val OutputPreference.labelRes get() = when (this) {
    OutputPreference.Auto -> R.string.output_auto
    OutputPreference.Speaker -> R.string.output_speaker
    OutputPreference.Wired -> R.string.output_wired
    OutputPreference.Bluetooth -> R.string.output_bluetooth
    OutputPreference.Usb -> R.string.output_usb
}
private val TrackSort.labelRes get() = when (this) {
    TrackSort.Title -> R.string.sort_name
    TrackSort.Artist -> R.string.sort_artist
    TrackSort.Album -> R.string.sort_album
    TrackSort.DateAdded -> R.string.sort_date_added
    TrackSort.Duration -> R.string.sort_duration
}
private val AlbumSort.labelRes get() = when (this) {
    AlbumSort.Title -> R.string.sort_name
    AlbumSort.Artist -> R.string.sort_artist
    AlbumSort.Year -> R.string.sort_year
    AlbumSort.DateAdded -> R.string.sort_date_added
}
private val ArtistSort.labelRes get() = when (this) {
    ArtistSort.Name -> R.string.sort_name
    ArtistSort.TrackCount -> R.string.sort_track_count
}

