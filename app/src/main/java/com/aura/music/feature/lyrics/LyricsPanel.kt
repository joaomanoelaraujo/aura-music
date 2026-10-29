package com.aura.music.feature.lyrics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.Atmosphere
import com.aura.music.core.designsystem.atmosphere.LocalGradientIntensity
import com.aura.music.core.designsystem.atmosphere.atmosphereBackground
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraSheet
import com.aura.music.core.designsystem.components.ButtonStyle
import com.aura.music.core.designsystem.components.PlayPauseButton
import com.aura.music.core.designsystem.components.SearchField
import com.aura.music.core.designsystem.components.SheetAction
import com.aura.music.core.designsystem.components.SheetHeader
import com.aura.music.core.designsystem.components.artistLabel
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.Track
import com.aura.music.data.lyrics.ImportResult
import com.aura.music.data.lyrics.LrcParser
import com.aura.music.data.lyrics.LyricsSource
import com.aura.music.data.lyrics.online.LrclibRecord
import com.aura.music.playback.controller.PlaybackPosition
import com.aura.music.ui.LocalMessenger

/** Things the lyrics surfaces ask their host to do. */
class LyricsHostActions(
    val onToggleFullscreen: () -> Unit,
    val onShowCover: () -> Unit,
)

/**
 * Lyrics in place of the cover inside Now Playing. Shares state, editor and file picker with
 * the full-screen view through [LyricsViewModel].
 */
@Composable
fun LyricsPanel(viewModel: LyricsViewModel, atmosphere: Atmosphere, host: LyricsHostActions, modifier: Modifier = Modifier) {
    LyricsSurface(viewModel, atmosphere, large = false, host = host, modifier = modifier)
}

/** Full-screen AURA Lyrics over the atmosphere, with essential playback controls. */
@Composable
fun LyricsFullScreen(
    viewModel: LyricsViewModel,
    track: Track,
    atmosphere: Atmosphere,
    host: LyricsHostActions,
    modifier: Modifier = Modifier,
) {
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    Column(
        modifier
            .fillMaxSize()
            .atmosphereBackground(atmosphere, glowCenterFraction = 0.1f, glowStrength = LocalGradientIntensity.current)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.x2, vertical = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
            AuraIconButton(AuraIcons.Shrink, stringResource(R.string.cd_lyrics_exit_fullscreen), host.onToggleFullscreen)
            Column(Modifier.weight(1f).padding(horizontal = Spacing.x2)) {
                Text(track.title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artistLabel(track.artist), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1)
            }
            PlayPauseButton(isPlaying, viewModel::togglePlayPause, size = 44.dp, container = atmosphere.accent, content = atmosphere.onAccent)
        }
        LyricsSurface(viewModel, atmosphere, large = true, host = host, modifier = Modifier.weight(1f).padding(horizontal = Spacing.gutter), inFullScreen = true)
    }
}

@Composable
private fun LyricsSurface(
    viewModel: LyricsViewModel,
    atmosphere: Atmosphere,
    large: Boolean,
    host: LyricsHostActions,
    modifier: Modifier = Modifier,
    inFullScreen: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val autoSearching by viewModel.autoSearching.collectAsStateWithLifecycle()
    val fx by viewModel.fx.collectAsStateWithLifecycle()
    val karaoke by viewModel.karaoke.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle(PlaybackPosition())
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.import(uri) { result ->
                messenger.show(
                    resources.getString(
                        when (result) {
                            ImportResult.Saved -> R.string.msg_lyrics_saved
                            ImportResult.TooLarge -> R.string.msg_lyrics_too_large
                            ImportResult.NoLyrics -> R.string.msg_lyrics_no_lyrics
                            ImportResult.Unreadable -> R.string.msg_lyrics_unreadable
                        },
                    ),
                )
            }
        }
    }
    val pickFile = { picker.launch(arrayOf("*/*")) }
    val lyricsState = state.lyrics

    Column(modifier) {
        if (!state.loading && lyricsState.lyrics.isEmpty) {
            LyricsEmptyState(
                searching = autoSearching,
                onSearchOnline = { viewModel.searchOnline() },
                onPickFile = pickFile,
                onWrite = { editing = true },
                modifier = Modifier.weight(1f),
            )
        } else {
            LyricsLines(
                lyrics = lyricsState.lyrics,
                offsetMs = lyricsState.totalOffsetMs,
                positionMs = position.positionMs,
                large = large,
                onLineClick = viewModel::seekTo,
                fx = fx,
                isPlaying = isPlaying,
                accent = atmosphere.accent,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = Spacing.x3),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (lyricsState.lyrics.synced) {
                OffsetControl(lyricsState.userOffsetMs, viewModel::nudgeOffset, viewModel::resetOffset, atmosphere.accent)
            } else if (!lyricsState.lyrics.isEmpty) {
                Text(stringResource(R.string.lyrics_unsynced_note), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textTertiary)
            }
            Spacer(Modifier.weight(1f))
            AuraIconButton(
                AuraIcons.Mic,
                stringResource(if (karaoke) R.string.cd_karaoke_off else R.string.cd_karaoke_on),
                {
                    if (!karaoke) messenger.show(resources.getString(R.string.msg_karaoke_on))
                    viewModel.toggleKaraoke()
                },
                tint = if (karaoke) atmosphere.accent else AuraTheme.colors.textSecondary,
            )
            AuraIconButton(AuraIcons.More, stringResource(R.string.cd_lyrics_options), { menuOpen = true }, tint = AuraTheme.colors.textSecondary)
            if (!inFullScreen) {
                AuraIconButton(AuraIcons.Expand, stringResource(R.string.cd_lyrics_fullscreen), host.onToggleFullscreen, tint = AuraTheme.colors.textSecondary)
                AuraIconButton(AuraIcons.Image, stringResource(R.string.cd_lyrics_show_cover), host.onShowCover, tint = AuraTheme.colors.textSecondary)
            }
        }
    }

    if (menuOpen) {
        AuraSheet(onDismiss = { menuOpen = false }) {
            SheetHeader(stringResource(R.string.lyrics_title), sourceLabel(lyricsState.source))
            SheetAction(
                AuraIcons.Search,
                stringResource(if (lyricsState.source == LyricsSource.Online) R.string.lyrics_wrong_search_again else R.string.lyrics_search_online),
                onClick = {
                    menuOpen = false
                    viewModel.searchOnline()
                },
            )
            if (lyricsState.source == LyricsSource.Online) {
                SheetAction(AuraIcons.Trash, stringResource(R.string.lyrics_remove_online), onClick = {
                    menuOpen = false
                    viewModel.removeOnline { messenger.show(resources.getString(R.string.msg_lyrics_online_removed)) }
                })
            }
            SheetAction(AuraIcons.FileText, stringResource(R.string.lyrics_pick_file), onClick = {
                menuOpen = false
                pickFile()
            })
            SheetAction(AuraIcons.Edit, stringResource(if (lyricsState.lyrics.isEmpty) R.string.lyrics_write else R.string.lyrics_edit), onClick = {
                menuOpen = false
                editing = true
            })
            if (lyricsState.source == LyricsSource.Custom) {
                SheetAction(AuraIcons.Refresh, stringResource(R.string.lyrics_revert), onClick = {
                    menuOpen = false
                    viewModel.revert { messenger.show(resources.getString(R.string.msg_lyrics_reverted)) }
                })
            }
        }
    }

    search?.let { current ->
        LyricsSearchSheet(
            state = current,
            initialQuery = viewModel.defaultQuery(),
            onSearch = viewModel::searchOnline,
            onChoose = { record -> viewModel.choose(record) { messenger.show(resources.getString(R.string.msg_lyrics_online_saved)) } },
            onDismiss = viewModel::closeSearch,
        )
    }

    if (editing) {
        LyricsEditor(
            initialText = lyricsState.rawText.ifEmpty { "" },
            positionMs = { position.positionMs },
            onSave = { text ->
                viewModel.save(text) { messenger.show(resources.getString(R.string.msg_lyrics_saved)) }
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun sourceLabel(source: LyricsSource): String? = when (source) {
    LyricsSource.Custom -> stringResource(R.string.lyrics_source_custom)
    LyricsSource.File -> stringResource(R.string.lyrics_source_file)
    LyricsSource.Embedded -> stringResource(R.string.lyrics_source_embedded)
    LyricsSource.Online -> stringResource(R.string.lyrics_source_online)
    LyricsSource.None -> null
}

/** Results from LRCLIB for the user to pick the right lyrics; the search text can be corrected. */
@Composable
private fun LyricsSearchSheet(
    state: LyricsSearchUi,
    initialQuery: String,
    onSearch: (String) -> Unit,
    onChoose: (LrclibRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    AuraSheet(onDismiss) {
        SheetHeader(stringResource(R.string.lyrics_search_title), stringResource(R.string.lyrics_search_note))
        SearchField(query, { query = it }, stringResource(R.string.lyrics_search_hint), Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2), onSearch = { onSearch(query) })
        when (state) {
            LyricsSearchUi.Searching -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AuraTheme.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            LyricsSearchUi.Offline -> SearchMessage(stringResource(R.string.lyrics_search_offline))
            LyricsSearchUi.Failed -> SearchMessage(stringResource(R.string.lyrics_search_failed))
            is LyricsSearchUi.Results -> if (state.records.isEmpty()) {
                SearchMessage(stringResource(R.string.lyrics_search_none))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(state.records, key = { it.id }) { record -> LyricsResultRow(record) { onChoose(record) } }
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(text: String) {
    Text(text, style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(Spacing.x6))
}

@Composable
private fun LyricsResultRow(record: LrclibRecord, onClick: () -> Unit) {
    val synced = !record.syncedLyrics.isNullOrBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(record.trackName, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(record.artistName, record.albumName?.takeIf(String::isNotBlank), formatDuration(record.durationMs)).joinToString(" · "),
                style = AuraTheme.type.bodySmall,
                color = AuraTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.x3))
        Text(
            stringResource(if (synced) R.string.lyrics_result_synced else R.string.lyrics_result_plain),
            style = AuraTheme.type.label,
            color = if (synced) AuraTheme.colors.accent else AuraTheme.colors.textTertiary,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Plain-text LRC editor. "Mark current time" writes the playback position as the stamp of the
 * line under the cursor and moves to the next line, so a song can be synced by tapping along.
 */
@Composable
private fun LyricsEditor(initialText: String, positionMs: () -> Long, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initialText)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(AuraTheme.colors.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Spacing.gutter),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.x2), verticalAlignment = Alignment.CenterVertically) {
                AuraIconButton(AuraIcons.Close, stringResource(R.string.action_cancel), onDismiss)
                Text(stringResource(R.string.lyrics_editor_title), style = AuraTheme.type.title, color = AuraTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                AuraButton(stringResource(R.string.action_save), onClick = { onSave(value.text) })
            }
            Text(stringResource(R.string.lyrics_editor_hint), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = Spacing.x3)
                    .clip(Radius.medium)
                    .background(AuraTheme.colors.surface)
                    .border(1.dp, AuraTheme.colors.outline, Radius.medium)
                    .padding(Spacing.x4),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = AuraTheme.type.body.copy(color = AuraTheme.colors.textPrimary, fontFeatureSettings = "tnum"),
                    cursorBrush = SolidColor(AuraTheme.colors.accent),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(bottom = Spacing.x3), horizontalArrangement = Arrangement.End) {
                AuraButton(
                    stringResource(R.string.lyrics_editor_stamp),
                    onClick = { value = stampCurrentLine(value, positionMs()) },
                    icon = AuraIcons.Timer,
                    style = ButtonStyle.Secondary,
                )
            }
        }
    }
}

/** Replaces (or adds) the time stamp of the cursor's line and moves the cursor to the next line. */
internal fun stampCurrentLine(value: TextFieldValue, positionMs: Long): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
    val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
    val body = text.substring(lineStart, lineEnd).replace(STAMPS, "")
    val stamped = LrcParser.formatStamp(positionMs) + body
    val newText = text.substring(0, lineStart) + stamped + text.substring(lineEnd)
    val next = (lineStart + stamped.length + 1).coerceAtMost(newText.length)
    return TextFieldValue(newText, TextRange(next))
}

private val STAMPS = Regex("""^(\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+""")
