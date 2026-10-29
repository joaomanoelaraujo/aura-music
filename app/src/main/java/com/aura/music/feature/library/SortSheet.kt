package com.aura.music.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraChip
import com.aura.music.core.designsystem.components.AuraSheet
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.AlbumSort
import com.aura.music.core.model.ArtistSort
import com.aura.music.core.model.SortSpec
import com.aura.music.core.model.TrackSort
import com.aura.music.data.local.AppSettings
import com.aura.music.navigation.LibraryTab

@Composable
fun SortSheet(tab: LibraryTab, settings: AppSettings, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    AuraSheet(onDismiss) {
        Text(
            stringResource(R.string.sort_title),
            style = AuraTheme.type.headline,
            color = AuraTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        )
        when (tab) {
            LibraryTab.Songs -> SortOptions(settings.trackSort, trackOptions, viewModel::setTrackSort)
            LibraryTab.Albums -> SortOptions(settings.albumSort, albumOptions, viewModel::setAlbumSort)
            LibraryTab.Artists -> SortOptions(settings.artistSort, artistOptions, viewModel::setArtistSort)
            else -> Unit
        }
    }
}

@Composable
private fun <T : Enum<T>> SortOptions(current: SortSpec<T>, options: List<Pair<T, Int>>, onChange: (SortSpec<T>) -> Unit) {
    options.forEach { (key, label) ->
        SortOption(label, selected = current.key == key, onClick = { onChange(current.copy(key = key)) })
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        AuraChip(stringResource(R.string.sort_ascending), current.ascending, onClick = { onChange(current.copy(ascending = true)) })
        AuraChip(stringResource(R.string.sort_descending), !current.ascending, onClick = { onChange(current.copy(ascending = false)) })
    }
}

@Composable
private fun SortOption(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(label),
            style = AuraTheme.type.body,
            color = if (selected) AuraTheme.colors.accent else AuraTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(24.dp)) {
            if (selected) Icon(AuraIcons.Check, contentDescription = null, tint = AuraTheme.colors.accent)
        }
    }
}

private val trackOptions = listOf(
    TrackSort.Title to R.string.sort_name,
    TrackSort.Artist to R.string.sort_artist,
    TrackSort.Album to R.string.sort_album,
    TrackSort.DateAdded to R.string.sort_date_added,
    TrackSort.Duration to R.string.sort_duration,
)

private val albumOptions = listOf(
    AlbumSort.Title to R.string.sort_name,
    AlbumSort.Artist to R.string.sort_artist,
    AlbumSort.Year to R.string.sort_year,
    AlbumSort.DateAdded to R.string.sort_date_added,
)

private val artistOptions = listOf(
    ArtistSort.Name to R.string.sort_name,
    ArtistSort.TrackCount to R.string.sort_track_count,
)
