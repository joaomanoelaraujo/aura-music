package com.aura.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.aura.music.core.designsystem.atmosphere.AtmosphereColors
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.data.search.PersonalSignals
import com.aura.music.data.search.SmartResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.io.File

@Composable
fun AuraApp(model: AppModel, searchFocus: FocusRequester, onAddFolder: () -> Unit) {
    val player by model.player.state.collectAsState()
    val atmosphere by produceState<AtmosphereColors?>(null, player.current?.albumId, player.current?.folder) {
        value = player.current?.let { model.artwork.atmosphere(it) }
    }
    AuraDesktopTheme(atmosphere) {
        val c = Aura.colors
        CompositionLocalProvider(
            LocalContextMenuRepresentation provides DefaultContextMenuRepresentation(
                backgroundColor = c.surfaceElevated,
                textColor = c.textPrimary,
                itemHoverColor = c.surfaceHighest,
            ),
        ) {
            var dropping by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.background)
                    .drawBehind {
                        // Adaptive Atmosphere: a soft light from the current cover, top left.
                        drawRect(Brush.radialGradient(listOf(c.glow.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width * 0.18f, 0f), radius = size.maxDimension * 0.6f))
                    }
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { true },
                        target = remember {
                            object : DragAndDropTarget {
                                override fun onEntered(event: DragAndDropEvent) { dropping = true }
                                override fun onExited(event: DragAndDropEvent) { dropping = false }
                                override fun onEnded(event: DragAndDropEvent) { dropping = false }
                                @OptIn(ExperimentalComposeUiApi::class)
                                override fun onDrop(event: DragAndDropEvent): Boolean {
                                    dropping = false
                                    val transferable = event.awtTransferable
                                    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                                    @Suppress("UNCHECKED_CAST")
                                    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                                    model.onDrop(files)
                                    return true
                                }
                            }
                        },
                    ),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        Sidebar(model, onAddFolder)
                        VerticalDivider(Modifier.fillMaxHeight())
                        Content(model, searchFocus, onAddFolder, Modifier.weight(1f))
                        val settings by model.settings.settings.collectAsState()
                        if (settings.queuePanelOpen) {
                            VerticalDivider(Modifier.fillMaxHeight())
                            QueuePanel(model)
                        }
                    }
                    Divider()
                    PlayerBar(model)
                }
                Notice(model, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp))
                if (dropping) DropOverlay()
            }
        }
    }
}

@Composable
private fun Sidebar(model: AppModel, onAddFolder: () -> Unit) {
    val c = Aura.colors
    val library by model.library.state.collectAsState()
    Column(Modifier.width(232.dp).fillMaxHeight().padding(vertical = 20.dp, horizontal = 14.dp)) {
        Row(Modifier.padding(start = 8.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            AuraMark(30.dp)
            Text("AURA", style = AuraType.heading.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(5f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.textPrimary, modifier = Modifier.padding(start = 10.dp))
        }
        Text("BIBLIOTECA", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(start = 10.dp, bottom = 8.dp))
        val screen = model.screen
        NavItem(AuraIcons.Note, "Músicas", library.index.tracks.size, screen is Screen.Songs) { model.open(Screen.Songs) }
        NavItem(AuraIcons.Album, "Álbuns", library.index.albums.size, screen is Screen.Albums || screen is Screen.Album) { model.open(Screen.Albums) }
        NavItem(AuraIcons.Artist, "Artistas", library.index.artists.size, screen is Screen.Artists || screen is Screen.Artist) { model.open(Screen.Artists) }
        NavItem(AuraIcons.Folder, "Pastas", library.folders.size, screen is Screen.Folders || screen is Screen.Folder) { model.open(Screen.Folders) }
        Spacer(Modifier.weight(1f))
        library.scanning?.let { p ->
            Text(
                if (p.total == 0) "Procurando músicas…" else "Lendo ${p.done} de ${p.total}",
                style = AuraType.small, color = c.textSecondary, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp),
            )
        }
        PillButton("Adicionar pasta", onAddFolder, AuraIcons.Plus, primary = false, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.surfaceElevated else if (hovered) c.surface else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) c.accent else c.textSecondary, modifier = Modifier.size(19.dp))
        Text(label, style = AuraType.bodyStrong, color = if (selected) c.textPrimary else c.textSecondary, modifier = Modifier.padding(start = 12.dp).weight(1f))
        if (count > 0) Text("$count", style = AuraType.small, color = c.textTertiary)
    }
}

@Composable
private fun Content(model: AppModel, searchFocus: FocusRequester, onAddFolder: () -> Unit, modifier: Modifier) {
    val c = Aura.colors
    val library by model.library.state.collectAsState()
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (model.canGoBack && model.query.isEmpty()) AuraIconButton(AuraIcons.Back, "Voltar (Alt+←)", model::back, modifier = Modifier.padding(end = 8.dp))
            Text(
                if (model.query.isNotEmpty()) "Busca" else model.screen.title,
                style = AuraType.display, color = c.textPrimary, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            SearchField(model.query, { model.query = it }, searchFocus, { model.searchFocused = it }, Modifier.width(380.dp))
        }
        library.scanning?.let { ScanningBanner(it) }
        Box(Modifier.weight(1f)) {
            when {
                !library.loaded -> Unit
                library.folders.isEmpty() -> EmptyState(
                    "Sua música, em primeiro plano.",
                    "Escolha a pasta onde estão as suas músicas. O AURA lê os arquivos no próprio computador: nada é enviado para a internet.",
                ) { PillButton("Escolher pasta de músicas", onAddFolder, AuraIcons.Folder) }
                model.query.isNotBlank() -> {
                    val query = model.query
                    val results by produceState(SmartResults(query = query), query, library.corpus) {
                        delay(80)
                        value = withContext(Dispatchers.Default) { library.corpus.search(query, PersonalSignals.None) }
                    }
                    SearchResults(model, results)
                }
                library.index.isEmpty && library.scanning == null -> when (val s = model.screen) {
                    is Screen.Folders -> FoldersScreen(model, onAddFolder)
                    else -> EmptyState(
                        "Nenhuma música por aqui",
                        if (library.unavailableFolders.isNotEmpty()) "Uma das pastas está indisponível agora. Confira em Pastas." else "As pastas escolhidas não têm arquivos de áudio que o AURA consiga ler.",
                    ) { PillButton("Ver pastas", { model.open(Screen.Folders) }, AuraIcons.Folder, primary = false) }
                }
                else -> when (val s = model.screen) {
                    Screen.Songs -> SongList(model, library.index.tracks)
                    Screen.Albums -> AlbumGrid(model, library.index.albums)
                    Screen.Artists -> ArtistList(model, library.index.artists)
                    Screen.Folders -> FoldersScreen(model, onAddFolder)
                    is Screen.Album -> AlbumDetail(model, s.id)
                    is Screen.Artist -> ArtistDetail(model, s.id)
                    is Screen.Folder -> FolderDetail(model, s.path)
                }
            }
        }
    }
}

@Composable
private fun Notice(model: AppModel, modifier: Modifier) {
    val c = Aura.colors
    val state by model.player.state.collectAsState()
    val notice = state.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500)
            model.player.dismissNotice()
        }
    }
    AnimatedVisibility(notice != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            notice.orEmpty(),
            style = AuraType.bodyStrong,
            color = c.textPrimary,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surfaceHighest).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun DropOverlay() {
    val c = Aura.colors
    Box(Modifier.fillMaxSize().background(c.background.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AuraMark(56.dp)
            Text("Solte aqui", style = AuraType.title, color = c.textPrimary)
            Text("Pastas entram na biblioteca; arquivos de áudio vão para a fila.", style = AuraType.body, color = c.textSecondary)
        }
    }
}
