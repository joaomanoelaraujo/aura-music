package com.aura.music.feature.playlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.AuraTopBar
import com.aura.music.core.designsystem.components.SearchField
import com.aura.music.core.designsystem.components.TrackRow
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalBottomInset
import com.aura.music.ui.LocalMessenger

@Composable
fun TrackPickerScreen(viewModel: TrackPickerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    val isQueue = viewModel.playlistId == null

    Column(Modifier.fillMaxSize().background(AuraTheme.colors.background)) {
        AuraTopBar(
            title = stringResource(if (isQueue) R.string.picker_title_queue else R.string.picker_title_playlist),
            onBack = { navigator.back() },
            actions = {
                if (state.selected.isNotEmpty()) {
                    AuraButton(
                        pluralStringResource(R.plurals.picker_confirm, state.selected.size, state.selected.size),
                        onClick = {
                            viewModel.confirm { added ->
                                val message = when {
                                    isQueue -> resources.getString(R.string.msg_added_to_queue)
                                    added == 0 -> resources.getString(R.string.picker_nothing_added)
                                    else -> resources.getQuantityString(R.plurals.picker_added, added, added)
                                }
                                messenger.show(message)
                                navigator.back()
                            }
                        },
                        modifier = Modifier.padding(end = Spacing.x2),
                    )
                }
            },
        )
        SearchField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            placeholder = stringResource(R.string.picker_filter),
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x4)) {
            items(state.tracks, key = { it.id }) { track ->
                val selected = track.id in state.selected
                TrackRow(
                    track = track,
                    onClick = { viewModel.toggle(track.id) },
                    showDuration = true,
                    modifier = Modifier.semantics {
                        this.selected = selected
                        role = Role.Checkbox
                    },
                    trailing = { SelectionMark(selected) },
                )
            }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    val reduced = LocalReducedMotion.current
    val fill by animateColorAsState(
        if (selected) AuraTheme.colors.accent else Color.Transparent,
        Motion.tween(Motion.SHORT, reduced),
        label = "selection",
    )
    Box(Modifier.padding(end = Spacing.x3).size(24.dp)) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.5.dp, if (selected) Color.Transparent else AuraTheme.colors.textTertiary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(AuraIcons.Check, contentDescription = null, tint = AuraTheme.colors.onAccent, modifier = Modifier.size(16.dp))
        }
    }
}
