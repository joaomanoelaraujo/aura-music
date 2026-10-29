package com.aura.music.feature.actions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraSheet
import com.aura.music.core.designsystem.components.SearchField
import com.aura.music.core.designsystem.components.SheetAction
import com.aura.music.core.designsystem.components.SheetHeader
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.Track
import com.aura.music.data.metadata.MbRecording
import com.aura.music.data.metadata.MetadataRepository
import com.aura.music.ui.LocalMessenger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class FixInfoViewModel @Inject constructor(private val metadata: MetadataRepository) : ViewModel() {
    var result by mutableStateOf<MetadataRepository.SearchResult?>(null)
        private set
    var searching by mutableStateOf(false)
        private set

    fun defaultQuery(track: Track) = metadata.defaultQuery(track)

    fun search(track: Track, text: String) {
        searching = true
        viewModelScope.launch {
            result = metadata.search(track, text)
            searching = false
        }
    }

    fun apply(track: Track, recording: MbRecording, onDone: () -> Unit) = viewModelScope.launch {
        metadata.apply(track, recording)
        onDone()
    }

    fun reset(track: Track, onDone: () -> Unit) = viewModelScope.launch {
        metadata.reset(track.id)
        onDone()
    }
}

/** Picks the right title and artist (and cover) for a badly tagged song, from MusicBrainz. */
@Composable
internal fun FixInfoSheet(track: Track, onDismiss: () -> Unit, viewModel: FixInfoViewModel = hiltViewModel()) {
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    var query by rememberSaveable(track.id) { mutableStateOf(viewModel.defaultQuery(track)) }
    LaunchedEffect(track.id) { viewModel.search(track, query) }
    AuraSheet(onDismiss) {
        SheetHeader(stringResource(R.string.fix_info_title), stringResource(R.string.fix_info_note))
        SearchField(query, { query = it }, stringResource(R.string.fix_info_hint), Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2), onSearch = { viewModel.search(track, query) })
        val result = viewModel.result
        when {
            viewModel.searching -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AuraTheme.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            result is MetadataRepository.SearchResult.Offline -> Message(stringResource(R.string.fix_info_offline))
            result is MetadataRepository.SearchResult.Failed -> Message(stringResource(R.string.fix_info_failed))
            result is MetadataRepository.SearchResult.Found && result.recordings.isEmpty() -> Message(stringResource(R.string.fix_info_none))
            result is MetadataRepository.SearchResult.Found -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(result.recordings, key = { it.id }) { recording ->
                    RecordingRow(recording) {
                        viewModel.apply(track, recording) {
                            messenger.show(resources.getString(R.string.fix_info_saved))
                            onDismiss()
                        }
                    }
                }
            }
        }
        SheetAction(AuraIcons.Refresh, stringResource(R.string.fix_info_reset), onClick = {
            viewModel.reset(track) { onDismiss() }
        })
    }
}

@Composable
private fun Message(text: String) {
    Text(text, style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(Spacing.x6))
}

@Composable
private fun RecordingRow(recording: MbRecording, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(recording.title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                recording.artist.ifBlank { null },
                recording.releases.firstOrNull()?.title,
                recording.length?.let { "%d:%02d".format(it / 60_000, it / 1000 % 60) },
            ).joinToString(" · ")
            Text(details, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
