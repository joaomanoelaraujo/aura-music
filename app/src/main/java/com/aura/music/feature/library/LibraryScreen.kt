package com.aura.music.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.ChipTabs
import com.aura.music.core.designsystem.components.PageTitle
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.core.model.LibraryLayout
import com.aura.music.feature.common.LibraryGate
import com.aura.music.navigation.LibraryTab
import com.aura.music.navigation.LocalNavigator

@Composable
fun LibraryScreen(viewModel: LibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var tab by rememberSaveable { mutableStateOf(LibraryTab.Songs) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current

    LaunchedEffect(navigator.requestedLibraryTab) {
        navigator.requestedLibraryTab?.let {
            tab = it
            navigator.requestedLibraryTab = null
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(top = Spacing.x4, end = Spacing.x2), verticalAlignment = Alignment.Bottom) {
            PageTitle(stringResource(R.string.nav_library), Modifier.weight(1f))
            LibraryToolbar(tab, state, onSort = { sortOpen = true }, viewModel = viewModel)
        }
        ChipTabs(
            items = LibraryTab.entries,
            selected = tab,
            label = { stringResource(it.labelRes) },
            onSelect = { tab = it },
        )
        LibraryGate(state.status, viewModel::refresh) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(Motion.tween(Motion.MEDIUM, reduced)) togetherWith fadeOut(Motion.tween(Motion.SHORT, reduced)) },
                label = "libraryTab",
            ) { current ->
                when (current) {
                    LibraryTab.Songs -> SongsTab(state, viewModel)
                    LibraryTab.Albums -> AlbumsTab(state.albums, state.settings.albumLayout, state.settings.coverSize)
                    LibraryTab.Artists -> ArtistsTab(state.artists, state.settings.artistLayout, state.settings.coverSize)
                    LibraryTab.Playlists -> PlaylistsTab(state.playlists)
                    LibraryTab.Folders -> FoldersTab(state.folders)
                    LibraryTab.Genres -> GenresTab(state.genres)
                    LibraryTab.Audios -> AudiosTab(state.audios, viewModel)
                }
            }
        }
    }

    if (sortOpen) {
        SortSheet(tab, state.settings, viewModel, onDismiss = { sortOpen = false })
    }
}

/** Only shows controls that actually apply to the current tab. */
@Composable
private fun LibraryToolbar(tab: LibraryTab, state: LibraryUiState, onSort: () -> Unit, viewModel: LibraryViewModel) {
    if (tab in setOf(LibraryTab.Songs, LibraryTab.Albums, LibraryTab.Artists)) {
        AuraIconButton(AuraIcons.Sort, stringResource(R.string.cd_sort), onSort)
    }
    val layout = when (tab) {
        LibraryTab.Albums -> state.settings.albumLayout
        LibraryTab.Artists -> state.settings.artistLayout
        else -> return
    }
    val next = if (layout == LibraryLayout.Grid) LibraryLayout.List else LibraryLayout.Grid
    AuraIconButton(
        icon = if (layout == LibraryLayout.Grid) AuraIcons.ListLayout else AuraIcons.Grid,
        contentDescription = stringResource(if (next == LibraryLayout.Grid) R.string.cd_layout_grid else R.string.cd_layout_list),
        onClick = {
            if (tab == LibraryTab.Albums) viewModel.setAlbumLayout(next) else viewModel.setArtistLayout(next)
        },
    )
}

private val LibraryTab.labelRes: Int
    get() = when (this) {
        LibraryTab.Songs -> R.string.library_songs
        LibraryTab.Albums -> R.string.library_albums
        LibraryTab.Artists -> R.string.library_artists
        LibraryTab.Playlists -> R.string.library_playlists
        LibraryTab.Folders -> R.string.library_folders
        LibraryTab.Genres -> R.string.library_genres
        LibraryTab.Audios -> R.string.library_audios
    }
