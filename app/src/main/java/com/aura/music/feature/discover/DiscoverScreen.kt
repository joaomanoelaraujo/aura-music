package com.aura.music.feature.discover

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.aura.music.R
import com.aura.music.core.common.formatDuration
import com.aura.music.core.common.formatFileSize
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.AuraChip
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraSheet
import com.aura.music.core.designsystem.components.ButtonStyle
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.components.LoadingState
import com.aura.music.core.designsystem.components.PageTitle
import com.aura.music.core.designsystem.components.SearchField
import com.aura.music.core.designsystem.components.SectionHeader
import com.aura.music.core.designsystem.components.SheetHeader
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.image.RemoteCover
import com.aura.music.data.discover.AudioOption
import com.aura.music.data.discover.DiscoverException
import com.aura.music.data.discover.DiscoverItem
import com.aura.music.data.discover.DiscoverRepository
import com.aura.music.data.discover.DownloadRepository
import com.aura.music.data.discover.DownloadStatus
import com.aura.music.data.discover.DownloadUi
import com.aura.music.data.discover.ItemDetail
import com.aura.music.data.discover.SourceKind
import com.aura.music.ui.LocalBottomInset
import com.aura.music.ui.LocalMessenger
import kotlinx.coroutines.launch

/**
 * AURA Discover: finds music in open catalogs (Creative Commons / public domain) and imports
 * authorized direct links. Streaming links are recognised and opened in their official apps.
 */
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshConnectivity()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        PageTitle(stringResource(R.string.discover_title), Modifier.padding(top = Spacing.x4), eyebrow = stringResource(R.string.discover_eyebrow))
        SearchField(
            value = state.input,
            onValueChange = viewModel::setInput,
            placeholder = stringResource(R.string.discover_placeholder),
            onSearch = {
                focus.clearFocus()
                viewModel.submit()
            },
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
        )
        Row(Modifier.padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            AuraChip(stringResource(R.string.discover_paste_link), selected = false, onClick = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrBlank()) {
                        viewModel.setInput(text.trim())
                        viewModel.submit()
                    }
                }
            })
            if (state.input.isNotBlank()) AuraChip(stringResource(R.string.discover_go), selected = true, onClick = {
                focus.clearFocus()
                viewModel.submit()
            })
        }
        if (!state.online) Banner(AuraIcons.Close, stringResource(R.string.discover_offline))
        else if (state.metered) Banner(AuraIcons.Info, stringResource(R.string.discover_metered))

        Box(Modifier.weight(1f)) {
            if (state.loading) {
                LoadingState()
            } else {
                DiscoverList(state, viewModel)
            }
        }
    }

    state.detail?.let { detail -> DetailSheet(detail, state.metered, viewModel) }
    state.streaming?.let { item -> StreamingSheet(item, onDismiss = viewModel::closeStreaming) }
}

@Composable
private fun DiscoverList(state: DiscoverUiState, viewModel: DiscoverViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x6)) {
        state.error?.let { reason -> item(key = "error") { ErrorCard(reason) } }
        if (state.detailLoading) item(key = "detailLoading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.x2), color = AuraTheme.colors.accent) }

        val searched = state.searchedFor
        if (searched != null) {
            item(key = "resultsHeader") { SectionHeader(stringResource(R.string.discover_results)) }
            if (state.results.isEmpty()) {
                item(key = "noResults") {
                    EmptyState(AuraIcons.Search, stringResource(R.string.search_no_results_title), stringResource(R.string.discover_no_results, searched))
                }
            }
            items(state.results, key = { it.key }) { item -> ResultRow(item, onClick = { viewModel.open(item) }) }
            item(key = "elsewhere") { ElsewhereRow(searched) }
        } else if (state.downloads.isEmpty() && state.error == null) {
            item(key = "intro") { Intro() }
        }

        if (state.downloads.isNotEmpty()) {
            item(key = "downloadsHeader") {
                SectionHeader(stringResource(R.string.discover_transfers), actionLabel = stringResource(R.string.discover_clear_finished), onAction = viewModel::clearFinished)
            }
            items(state.downloads, key = { "d${it.id}" }) { download -> DownloadRow(download, viewModel) }
        }
    }
}

@Composable
private fun Intro() {
    Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x4), verticalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        IntroLine(AuraIcons.Search, stringResource(R.string.discover_intro_catalog))
        IntroLine(AuraIcons.Download, stringResource(R.string.discover_intro_links))
        IntroLine(AuraIcons.Play, stringResource(R.string.discover_intro_streaming))
    }
}

@Composable
private fun IntroLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.x3)) {
        Icon(icon, contentDescription = null, tint = AuraTheme.colors.accent, modifier = Modifier.size(20.dp))
        Text(text, style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary)
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String) {
    Row(
        Modifier
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x2)
            .fillMaxWidth()
            .clip(Radius.medium)
            .background(AuraTheme.colors.surface)
            .padding(Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Icon(icon, contentDescription = null, tint = AuraTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
        Text(text, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
    }
}

@Composable
private fun ErrorCard(reason: DiscoverException.Reason) {
    val text = stringResource(
        when (reason) {
            DiscoverException.Reason.Offline -> R.string.discover_error_offline
            DiscoverException.Reason.Network -> R.string.discover_error_network
            DiscoverException.Reason.NotFound -> R.string.discover_error_not_found
            DiscoverException.Reason.NotAudio -> R.string.discover_error_not_audio
            DiscoverException.Reason.TooLarge -> R.string.discover_error_too_large
            DiscoverException.Reason.Insecure -> R.string.discover_error_insecure
            DiscoverException.Reason.NoLicense -> R.string.discover_error_no_license
        },
    )
    Banner(AuraIcons.Info, text)
}

@Composable
private fun Cover(url: String?, size: Dp, fallback: ImageVector) {
    Box(Modifier.size(size).clip(Radius.small).background(AuraTheme.colors.surfaceElevated), contentAlignment = Alignment.Center) {
        Icon(fallback, contentDescription = null, tint = AuraTheme.colors.textTertiary, modifier = Modifier.size(size * 0.4f))
        if (url != null && url.startsWith("https://")) {
            AsyncImage(model = RemoteCover(url), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun sourceLabel(item: DiscoverItem): String = listOfNotNull(
    stringResource(
        when (item.source) {
            SourceKind.InternetArchive -> R.string.source_internet_archive
            SourceKind.DirectLink -> R.string.source_direct
            SourceKind.YouTube -> R.string.source_youtube
            SourceKind.Spotify -> R.string.source_spotify
        },
    ),
    item.license,
    if (!item.downloadable) stringResource(R.string.discover_streaming_only) else null,
).joinToString("  ·  ")

@Composable
private fun ResultRow(item: DiscoverItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Cover(item.coverUrl, 56.dp, AuraIcons.Note)
        Column(Modifier.weight(1f)) {
            Text(item.title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.artist?.let { Text(it, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(sourceLabel(item), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(AuraIcons.ChevronRight, contentDescription = null, tint = AuraTheme.colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

/** Searching by name in the official apps: no key, no scraping, playback stays inside them. */
@Composable
private fun ElsewhereRow(query: String) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x4)) {
        Text(stringResource(R.string.discover_elsewhere), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
        Row(Modifier.padding(top = Spacing.x2), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
            AuraButton(stringResource(R.string.source_youtube_music), { openExternal(context, DiscoverRepository.youTubeMusicSearch(query)) }, style = ButtonStyle.Secondary)
            AuraButton(stringResource(R.string.source_spotify), { openExternal(context, DiscoverRepository.spotifySearch(query)) }, style = ButtonStyle.Secondary)
        }
    }
}

@Composable
private fun DetailSheet(detail: ItemDetail, metered: Boolean, viewModel: DiscoverViewModel) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    val item = detail.item
    AuraSheet(onDismiss = viewModel::closeDetail) {
        SheetHeader(title = item.title, subtitle = listOfNotNull(item.artist, sourceLabel(item)).joinToString("  ·  "), leading = { Cover(item.coverUrl, 52.dp, AuraIcons.Note) })
        Text(
            stringResource(if (metered) R.string.discover_quality_note_metered else R.string.discover_quality_note),
            style = AuraTheme.type.bodySmall,
            color = AuraTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(detail.tracks, key = { it.title + it.trackNumber }) { track ->
                Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.x3)) {
                    Text(
                        listOfNotNull(track.trackNumber?.let { "$it." }, track.title).joinToString(" "),
                        style = AuraTheme.type.titleSmall,
                        color = AuraTheme.colors.textPrimary,
                    )
                    track.durationSec?.let {
                        Text(formatDuration((it * 1000).toLong()), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
                    }
                    FlowRow(Modifier.padding(top = Spacing.x2), horizontalArrangement = Arrangement.spacedBy(Spacing.x2), verticalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                        track.options.forEach { option ->
                            AuraChip(optionLabel(option), selected = false, onClick = {
                                viewModel.download(track, option) { result ->
                                    messenger.show(resources.getString(enqueueMessage(result), track.title))
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun optionLabel(option: AudioOption): String = listOfNotNull(
    option.formatLabel,
    option.sizeBytes?.let(::formatFileSize),
    if (option.original) stringResource(R.string.discover_original) else null,
).joinToString(" · ")

private fun enqueueMessage(result: DownloadRepository.EnqueueResult) = when (result) {
    DownloadRepository.EnqueueResult.Started -> R.string.discover_download_started
    DownloadRepository.EnqueueResult.NoSpace -> R.string.discover_download_no_space
    DownloadRepository.EnqueueResult.TooLarge -> R.string.discover_download_too_large
    DownloadRepository.EnqueueResult.Insecure -> R.string.discover_download_insecure
}

@Composable
private fun StreamingSheet(item: DiscoverItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AuraSheet(onDismiss) {
        SheetHeader(title = item.title, subtitle = listOfNotNull(item.artist, sourceLabel(item)).joinToString("  ·  "), leading = { Cover(item.coverUrl, 64.dp, AuraIcons.Play) })
        Text(
            stringResource(R.string.discover_streaming_body),
            style = AuraTheme.type.body,
            color = AuraTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
        )
        AuraButton(
            stringResource(if (item.source == SourceKind.Spotify) R.string.discover_open_spotify else R.string.discover_open_youtube),
            onClick = {
                openExternal(context, item.pageUrl)
                onDismiss()
            },
            icon = AuraIcons.Play,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x3).fillMaxWidth(),
        )
    }
}

@Composable
private fun DownloadRow(download: DownloadUi, viewModel: DiscoverViewModel) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    Column(Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = Spacing.x2, top = Spacing.x2, bottom = Spacing.x2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(download.title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusLine(download), style = AuraTheme.type.bodySmall, color = statusColor(download.status), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (download.status) {
                DownloadStatus.Queued, DownloadStatus.Running, DownloadStatus.WaitingNetwork ->
                    AuraIconButton(AuraIcons.Close, stringResource(R.string.discover_cancel, download.title), { viewModel.cancel(download.id) }, iconSize = 18.dp)
                DownloadStatus.Failed, DownloadStatus.Cancelled -> {
                    AuraIconButton(AuraIcons.Refresh, stringResource(R.string.discover_retry, download.title), {
                        viewModel.retry(download.id) { messenger.show(resources.getString(enqueueMessage(it), download.title)) }
                    }, iconSize = 18.dp)
                    AuraIconButton(AuraIcons.Trash, stringResource(R.string.discover_remove, download.title), { viewModel.remove(download.id) }, iconSize = 18.dp)
                }
                DownloadStatus.Done -> AuraIconButton(AuraIcons.Trash, stringResource(R.string.discover_remove, download.title), { viewModel.remove(download.id) }, iconSize = 18.dp)
            }
        }
        if (download.status == DownloadStatus.Running || download.status == DownloadStatus.Queued || download.status == DownloadStatus.WaitingNetwork) {
            val progress = download.progress
            val modifier = Modifier.fillMaxWidth().padding(top = Spacing.x2, end = Spacing.x3).height(3.dp).clip(Radius.pill)
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = modifier, color = AuraTheme.colors.accent, trackColor = AuraTheme.colors.surfaceElevated, drawStopIndicator = {})
            } else {
                LinearProgressIndicator(modifier = modifier, color = AuraTheme.colors.accent, trackColor = AuraTheme.colors.surfaceElevated)
            }
        }
    }
}

@Composable
private fun statusLine(d: DownloadUi): String {
    val status = stringResource(
        when (d.status) {
            DownloadStatus.Queued -> R.string.download_queued
            DownloadStatus.Running -> R.string.download_running
            DownloadStatus.WaitingNetwork -> R.string.download_waiting
            DownloadStatus.Done -> R.string.download_done
            DownloadStatus.Failed -> R.string.download_failed
            DownloadStatus.Cancelled -> R.string.download_cancelled
        },
    )
    val bytes = when {
        d.status == DownloadStatus.Running && d.totalBytes != null -> "${formatFileSize(d.downloadedBytes)} / ${formatFileSize(d.totalBytes)}"
        d.totalBytes != null -> formatFileSize(d.totalBytes)
        else -> null
    }
    return listOfNotNull(status, d.formatLabel, bytes, d.license).joinToString("  ·  ")
}

@Composable
private fun statusColor(status: DownloadStatus) = when (status) {
    DownloadStatus.Done -> AuraTheme.colors.accent
    DownloadStatus.Failed -> AuraTheme.colors.danger
    else -> AuraTheme.colors.textSecondary
}

private fun openExternal(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No app or browser can open it; nothing else to do.
    }
}

