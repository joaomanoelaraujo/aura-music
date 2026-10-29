package com.aura.music.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.BuildConfig
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraTopBar
import com.aura.music.core.designsystem.components.PageTitle
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.navigation.LocalNavigator
import com.aura.music.navigation.SettingsSection
import com.aura.music.ui.LocalBottomInset

/** AURA Settings: a calm list of categories, each opening its own page. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val summary = state.summary
    Column(Modifier.fillMaxSize()) {
        AuraTopBar(title = null, onBack = { navigator.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x8)) {
            item { PageTitle(stringResource(R.string.settings_title)) }
            item {
                CategoryRow(AuraIcons.Image, stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_summary)) {
                    navigator.openSettingsSection(SettingsSection.Appearance)
                }
            }
            item {
                CategoryRow(AuraIcons.Home, stringResource(R.string.settings_home), stringResource(R.string.settings_home_summary)) {
                    navigator.openSettingsSection(SettingsSection.Home)
                }
            }
            item {
                CategoryRow(AuraIcons.Play, stringResource(R.string.settings_playback), stringResource(R.string.settings_playback_summary)) {
                    navigator.openSettingsSection(SettingsSection.Playback)
                }
            }
            item {
                CategoryRow(AuraIcons.Equalizer, stringResource(R.string.player_equalizer), stringResource(R.string.settings_equalizer_summary)) {
                    navigator.openSettingsSection(SettingsSection.Equalizer)
                }
            }
            item {
                val body = summary?.let {
                    stringResource(
                        R.string.settings_library_summary,
                        pluralStringResource(R.plurals.track_count, it.tracks, it.tracks),
                        pluralStringResource(R.plurals.album_count, it.albums, it.albums),
                        pluralStringResource(R.plurals.audio_count, it.audios, it.audios),
                    )
                } ?: stringResource(R.string.settings_library_hub_summary)
                CategoryRow(AuraIcons.Library, stringResource(R.string.settings_library), body) {
                    navigator.openSettingsSection(SettingsSection.Library)
                }
            }
            item {
                CategoryRow(AuraIcons.Lyrics, stringResource(R.string.settings_lyrics), stringResource(R.string.settings_lyrics_summary)) {
                    navigator.openSettingsSection(SettingsSection.Lyrics)
                }
            }
            item {
                CategoryRow(AuraIcons.EyeOff, stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_summary)) {
                    navigator.openSettingsSection(SettingsSection.Privacy)
                }
            }
            item {
                CategoryRow(AuraIcons.Save, stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_summary)) {
                    navigator.openSettingsSection(SettingsSection.Backup)
                }
            }
            item {
                CategoryRow(AuraIcons.Together, stringResource(R.string.link_title), stringResource(R.string.link_summary)) {
                    navigator.openLink()
                }
            }

            item { SettingsSection(stringResource(R.string.settings_about)) }
            item { InfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME) }
            item { NoteText(stringResource(R.string.settings_offline)) }
            item { ActionRow(AuraIcons.Info, stringResource(R.string.settings_licenses), body = null, onClick = navigator::openLicenses) }
        }
    }
}
