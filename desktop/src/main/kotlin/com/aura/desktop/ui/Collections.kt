package com.aura.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.desktop.data.AppPaths
import com.aura.music.core.common.formatDuration
import com.aura.music.core.common.totalMinutes
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.model.Track
import com.aura.music.data.lyrics.online.LrclibRecord
import kotlinx.coroutines.launch
import org.bytedeco.ffmpeg.global.avformat
import java.awt.Desktop

// ---------------------------------------------------------------- favourites and playlists

@Composable
fun FavoritesScreen(model: AppModel) {
    val data by model.userData.value.collectAsState()
    val library by model.library.state.collectAsState()
    val tracks = remember(data.favorites, library.index) { model.favoriteTracks() }
    if (tracks.isEmpty()) {
        EmptyState("Nenhuma favorita ainda", "Use o coração no player ou “Favoritar” no menu do botão direito de uma música.")
        return
    }
    CollectionList(
        model, tracks,
        header = { CollectionTitle(model, tracks, "FAVORITAS", "Suas favoritas", "${countLabel(tracks.size)} · ${totalMinutes(tracks.sumOf { it.durationMs })} min") },
        extraMenu = { _, track -> listOf(ContextMenuItem("Remover das favoritas") { model.toggleFavorite(track) }) },
    )
}

@Composable
fun PlaylistScreen(model: AppModel, id: String) {
    val c = Aura.colors
    val data by model.userData.value.collectAsState()
    val library by model.library.state.collectAsState()
    val playlist = data.playlists.firstOrNull { it.id == id } ?: return EmptyState("Playlist não encontrada", "Ela pode ter sido excluída.")
    val tracks = remember(playlist, library.index) { model.playlistTracks(id) }
    CollectionList(
        model, tracks,
        header = {
            CollectionTitle(model, tracks, "PLAYLIST", playlist.name, "${countLabel(tracks.size)} · ${totalMinutes(tracks.sumOf { it.durationMs })} min") {
                AuraIconButton(AuraIcons.Edit, "Renomear", { model.dialog = Dialog.RenamePlaylist(id, playlist.name) })
                AuraIconButton(AuraIcons.Trash, "Excluir playlist", { model.dialog = Dialog.DeletePlaylist(id, playlist.name) })
            }
            if (tracks.isEmpty()) {
                Text("Vazia por enquanto. No menu do botão direito de qualquer música, escolha “Adicionar à playlist…”.", style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(vertical = 16.dp))
            }
        },
        extraMenu = { i, _ ->
            listOfNotNull(
                ContextMenuItem("Mover para cima") { model.userData.moveInPlaylist(id, i, i - 1) }.takeIf { i > 0 },
                ContextMenuItem("Mover para baixo") { model.userData.moveInPlaylist(id, i, i + 1) }.takeIf { i < tracks.lastIndex },
                ContextMenuItem("Remover desta playlist") { model.userData.removeFromPlaylist(id, i) },
            )
        },
    )
}

/** A list of saved songs; songs whose file is missing stay visible, dimmed, and can't be played. */
@Composable
private fun CollectionList(
    model: AppModel,
    tracks: List<Track>,
    header: @Composable () -> Unit,
    extraMenu: (Int, Track) -> List<ContextMenuItem>,
) {
    val current = model.player.state.collectAsState().value.current
    LazyColumn(contentPadding = PaddingValues(start = 32.dp, end = 24.dp, top = 8.dp, bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item { header() }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.id}" }) { i, track ->
            val available = model.library.isAvailable(track.uri)
            if (available) {
                SongRow(model, track, i + 1, track.id == current?.id, showAlbum = true, onPlay = { model.playAvailable(tracks, i) }, extraMenu = extraMenu(i, track))
            } else {
                MissingRow(track, i + 1, extraMenu(i, track))
            }
        }
    }
}

@Composable
private fun MissingRow(track: Track, number: Int, menu: List<ContextMenuItem>) {
    val c = Aura.colors
    ContextMenuArea(items = { menu }) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$number", style = AuraType.small, color = c.textTertiary, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f).padding(start = 52.dp)) {
                Text(track.title, style = AuraType.bodyStrong, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Arquivo indisponível: ${track.uri}", style = AuraType.small, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CollectionTitle(
    model: AppModel,
    tracks: List<Track>,
    kind: String,
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit = {},
) {
    val c = Aura.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 22.dp), verticalAlignment = Alignment.Bottom) {
        Cover(tracks.firstOrNull { model.library.isAvailable(it.uri) }, model.artwork, 168.dp, 18.dp, sizePx = 360)
        Column(Modifier.padding(start = 26.dp).weight(1f)) {
            Text(kind, style = AuraType.label, color = c.textTertiary)
            Text(title, style = AuraType.display, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Text(subtitle, style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tracks.isNotEmpty()) {
                    PillButton("Tocar", { model.playAvailable(tracks, 0) }, AuraIcons.Play)
                    PillButton("Aleatório", {
                        if (!model.player.state.value.queue.shuffle) model.player.toggleShuffle()
                        model.playAvailable(tracks, tracks.indices.random())
                    }, AuraIcons.Shuffle, primary = false)
                }
                actions()
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs

/** Draws [AppModel.dialog] over the window. */
@Composable
fun DialogHost(model: AppModel) {
    val dialog = model.dialog ?: return
    val c = Aura.colors
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.dialog = null },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(min = 380.dp, max = 560.dp).clip(RoundedCornerShape(22.dp)).background(c.surfaceElevated)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .onPreviewKeyEvent { if (it.key == Key.Escape) { model.dialog = null; true } else false }
                .padding(24.dp),
        ) {
            when (dialog) {
                is Dialog.NewPlaylist -> NameDialog("Nova playlist", "", "Criar", { model.dialog = null }) { model.createPlaylist(it, dialog.tracks) }
                is Dialog.RenamePlaylist -> NameDialog("Renomear playlist", dialog.current, "Salvar", { model.dialog = null }) {
                    model.userData.renamePlaylist(dialog.id, it)
                    model.dialog = null
                }
                is Dialog.DeletePlaylist -> {
                    Text("Excluir “${dialog.name}”?", style = AuraType.title, color = c.textPrimary)
                    Text("Só a playlist é excluída; as músicas continuam no computador.", style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        PillButton("Cancelar", { model.dialog = null }, primary = false)
                        PillButton("Excluir", { model.deletePlaylist(dialog.id) }, AuraIcons.Trash)
                    }
                }
                is Dialog.AddToPlaylist -> AddToPlaylist(model, dialog.tracks)
                is Dialog.RenameDevice -> NameDialog(if (dialog.deviceId == null) "Nome deste computador" else "Renomear aparelho", dialog.current, "Salvar", { model.dialog = null }) {
                    if (dialog.deviceId == null) model.link?.renameThisDevice(it) else model.link?.rename(dialog.deviceId, it)
                    model.dialog = null
                }
                is Dialog.RevokeDevice -> {
                    Text("Remover o vínculo com “${dialog.name}”?", style = AuraType.title, color = c.textPrimary)
                    Text(
                        "O aparelho perde o acesso a este computador na hora e não consegue se reconectar. Para usar de novo, será preciso vincular outra vez.",
                        style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        PillButton("Cancelar", { model.dialog = null }, primary = false)
                        PillButton("Remover vínculo", {
                            model.link?.revoke(dialog.deviceId)
                            model.dialog = null
                            if ((model.screen as? Screen.Remote)?.deviceId == dialog.deviceId) model.open(Screen.Devices)
                        }, AuraIcons.Trash)
                    }
                }
                is Dialog.LyricsSearch -> LyricsSearch(model, dialog.track)
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    val c = Aura.colors
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Text(title, style = AuraType.title, color = c.textPrimary)
    BasicTextField(
        value = name,
        onValueChange = { name = it.take(80) },
        singleLine = true,
        textStyle = AuraType.body.copy(color = c.textPrimary),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name.trim()) }),
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).focusRequester(focus)
            .onPreviewKeyEvent { if (it.key == Key.Enter && name.isNotBlank()) { onConfirm(name.trim()); true } else false },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(14.dp)) {
                if (name.isEmpty()) Text("Nome da playlist", style = AuraType.body, color = c.textTertiary)
                inner()
            }
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        PillButton("Cancelar", onCancel, primary = false)
        PillButton(confirm, { if (name.isNotBlank()) onConfirm(name.trim()) })
    }
}

@Composable
private fun AddToPlaylist(model: AppModel, tracks: List<Track>) {
    val c = Aura.colors
    val data by model.userData.value.collectAsState()
    Text("Adicionar à playlist", style = AuraType.title, color = c.textPrimary)
    Text(if (tracks.size == 1) tracks.first().title else countLabel(tracks.size), style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
    LazyColumn(Modifier.heightIn(max = 320.dp)) {
        item {
            DialogRow(AuraIcons.Plus, "Nova playlist") { model.dialog = Dialog.NewPlaylist(tracks) }
        }
        items(data.playlists.sortedByDescending { it.updatedAt }, key = { it.id }) { p ->
            DialogRow(AuraIcons.Playlist, p.name, countLabel(p.paths.size)) { model.addToPlaylist(p.id, tracks) }
        }
    }
}

@Composable
private fun DialogRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    val c = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(if (hovered) c.surfaceHighest else Color.Transparent)
            .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Text(title, style = AuraType.bodyStrong, color = c.textPrimary, modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = AuraType.small, color = c.textTertiary)
    }
}

private sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Done(val results: List<LrclibRecord>) : SearchState
    data object Failed : SearchState
}

/** Manual LRCLIB search: the user sends this query on purpose and picks the right lyrics. */
@Composable
private fun LyricsSearch(model: AppModel, track: Track) {
    val c = Aura.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(model.lyrics.suggestedQuery(track)) }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    fun run() {
        if (query.isBlank()) return
        state = SearchState.Loading
        scope.launch { state = runCatching { SearchState.Done(model.lyrics.search(query)) }.getOrElse { SearchState.Failed } }
    }
    LaunchedEffect(Unit) { run() }
    Text("Procurar letra", style = AuraType.title, color = c.textPrimary)
    Text("A busca envia só o texto abaixo ao LRCLIB (lrclib.net).", style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            textStyle = AuraType.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f).onPreviewKeyEvent { if (it.key == Key.Enter) { run(); true } else false },
            decorationBox = { inner -> Box(Modifier.clip(RoundedCornerShape(12.dp)).background(c.surface).padding(12.dp)) { inner() } },
        )
        AuraIconButton(AuraIcons.Search, "Buscar", ::run, modifier = Modifier.padding(start = 8.dp))
    }
    Box(Modifier.heightIn(min = 120.dp, max = 340.dp)) {
        when (val s = state) {
            SearchState.Idle, SearchState.Loading -> Text("Procurando…", style = AuraType.body, color = c.textSecondary)
            SearchState.Failed -> Text("Não foi possível falar com o LRCLIB. Confira a internet e tente de novo.", style = AuraType.body, color = c.danger)
            is SearchState.Done -> if (s.results.isEmpty()) {
                Text("Nada encontrado. Tente só o nome da música, ou artista e música.", style = AuraType.body, color = c.textSecondary)
            } else {
                LazyColumn {
                    items(s.results.take(30), key = { it.id }) { r ->
                        val diff = if (track.durationMs > 0) kotlin.math.abs(r.durationMs - track.durationMs) / 1000 else null
                        DialogRow(
                            if (r.syncedLyrics.isNullOrBlank()) AuraIcons.FileText else AuraIcons.Lyrics,
                            "${r.trackName} — ${r.artistName}",
                            listOfNotNull(if (r.syncedLyrics.isNullOrBlank()) "sem sincronia" else "sincronizada", formatDuration(r.durationMs), diff?.takeIf { it > 2 }?.let { "±${it}s" }).joinToString(" · "),
                        ) {
                            scope.launch {
                                model.lyrics.choose(track, r)
                                model.lyricsChanged()
                                model.dialog = null
                                model.player.notify("Letra salva")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(model: AppModel) {
    val c = Aura.colors
    val settings by model.settings.settings.collectAsState()
    val library by model.library.state.collectAsState()
    LazyColumn(contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
        item { SettingsGroup("Biblioteca") }
        item {
            SettingRow("Pastas de músicas", "${library.folders.size} ${if (library.folders.size == 1) "pasta" else "pastas"} · ${countLabel(library.index.tracks.size)}") {
                PillButton("Gerenciar", { model.open(Screen.Folders) }, primary = false)
            }
        }
        item {
            SettingRow("Atualizar a biblioteca", "Relê as pastas agora. O AURA também faz isso sempre que abre.") {
                PillButton("Atualizar", { model.library.rescan() }, AuraIcons.Refresh, primary = false)
            }
        }
        item {
            SettingRow("Downloads do Descobrir", "Salvos em ${model.downloadFolder()} (essa pasta entra na biblioteca).") {
                PillButton("Escolher pasta", {
                    val chooser = javax.swing.JFileChooser().apply {
                        dialogTitle = "Pasta para downloads"
                        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    }
                    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                        model.settings.update { it.copy(downloadFolder = chooser.selectedFile.absolutePath) }
                    }
                }, AuraIcons.Folder, primary = false)
            }
        }
        item {
            SettingRow("Músicas recebidas de outros aparelhos", "Salvas em ${model.receiveFolder()} (essa pasta entra na biblioteca).") {
                PillButton("Escolher pasta", {
                    val chooser = javax.swing.JFileChooser().apply {
                        dialogTitle = "Pasta para músicas recebidas"
                        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    }
                    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                        model.settings.update { it.copy(receiveFolder = chooser.selectedFile.absolutePath) }
                    }
                }, AuraIcons.Folder, primary = false)
            }
        }
        item { SettingsGroup("Letras") }
        item {
            SettingToggle(
                "Buscar letras na internet",
                "Quando uma música não tem letra, procura no LRCLIB (lrclib.net) e guarda no computador. Envia só título, artista, álbum e duração dessa música. Desligado, nada sai do computador.",
                settings.onlineLyrics,
            ) { v -> model.settings.update { it.copy(onlineLyrics = v) } }
        }
        item { SettingsGroup("Reprodução") }
        item {
            SettingToggle("Retomar de onde parei", "Ao abrir, a última fila volta pausada na mesma posição.", settings.resumeSession) { v ->
                model.settings.update { it.copy(resumeSession = v) }
            }
        }
        item { SettingsGroup("Busca") }
        item {
            SettingToggle(
                "Personalizar resultados",
                "Entre resultados igualmente bons, mostra antes o que você mais ouve e suas favoritas. Calculado no computador, nunca enviado.",
                settings.personalizeSearch,
            ) { v -> model.settings.update { it.copy(personalizeSearch = v) } }
        }
        item { SettingsGroup("Sobre") }
        item {
            val v = avformat.avformat_version()
            SettingRow("AURA Desktop ${appVersion()}", "Áudio por FFmpeg (libavformat ${v shr 16}.${(v shr 8) and 0xFF}, LGPL) · Letras por LRCLIB · Fontes Manrope e Instrument Serif (OFL)") {}
        }
        item {
            SettingRow("Dados do AURA", AppPaths.root.toString()) {
                PillButton("Abrir pasta", { runCatching { Desktop.getDesktop().open(AppPaths.root.toFile()) } }, AuraIcons.Folder, primary = false)
            }
        }
    }
}

fun appVersion(): String = System.getProperty("jpackage.app-version") ?: "dev"

@Composable
private fun SettingsGroup(title: String) {
    Text(title.uppercase(), style = AuraType.label, color = Aura.colors.textTertiary, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
}

@Composable
private fun SettingRow(title: String, body: String, trailing: @Composable () -> Unit) {
    val c = Aura.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = AuraType.bodyStrong, color = c.textPrimary)
            Text(body, style = AuraType.small, color = c.textSecondary, modifier = Modifier.padding(top = 3.dp))
        }
        trailing()
    }
}

@Composable
private fun SettingToggle(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, body) { Switch(checked, onChange, title) }
}

@Composable
fun Switch(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val c = Aura.colors
    val offset by androidx.compose.animation.core.animateDpAsState(if (checked) 18.dp else 0.dp)
    Box(
        Modifier.size(width = 42.dp, height = 24.dp).clip(RoundedCornerShape(50)).background(if (checked) c.accent else c.surfaceHighest)
            .clickable { onChange(!checked) }.pointerHoverIcon(PointerIcon.Hand)
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "Ligado" else "Desligado"
                role = Role.Switch
            }
            .padding(3.dp),
    ) {
        Box(Modifier.padding(start = offset).size(18.dp).clip(RoundedCornerShape(50)).background(if (checked) c.onAccent else c.textSecondary))
    }
}
