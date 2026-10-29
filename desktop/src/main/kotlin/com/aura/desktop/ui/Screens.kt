package com.aura.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.desktop.library.ScanProgress
import com.aura.music.core.common.formatDuration
import com.aura.music.core.common.totalMinutes
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.model.Album
import com.aura.music.core.model.Artist
import com.aura.music.core.model.Track
import com.aura.music.data.search.SmartResults
import com.aura.music.data.search.TopResult

private val ContentPadding = PaddingValues(start = 32.dp, end = 24.dp, top = 8.dp, bottom = 32.dp)

/** Song list with a header row; double-click (or Enter on a focused row) plays from there. */
@Composable
fun SongList(
    model: AppModel,
    tracks: List<Track>,
    header: (@Composable () -> Unit)? = null,
    showAlbum: Boolean = true,
    state: LazyListState = rememberLazyListState(),
) {
    val current = model.player.state.value.current
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = ContentPadding, modifier = Modifier.fillMaxSize()) {
            if (header != null) item(key = "header", contentType = "header") { header() }
            item(key = "columns", contentType = "columns") { SongColumns(showAlbum) }
            itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "song" }) { i, track ->
                SongRow(model, track, number = i + 1, playing = track.id == current?.id, showAlbum = showAlbum, onPlay = { model.player.playTracks(tracks, i) })
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp))
    }
}

@Composable
private fun SongColumns(showAlbum: Boolean) {
    val c = Aura.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("#", style = AuraType.label, color = c.textTertiary, modifier = Modifier.width(36.dp))
        Text("TÍTULO", style = AuraType.label, color = c.textTertiary, modifier = Modifier.weight(1.4f).padding(start = 52.dp))
        if (showAlbum) Text("ÁLBUM", style = AuraType.label, color = c.textTertiary, modifier = Modifier.weight(1f))
        Text("DURAÇÃO", style = AuraType.label, color = c.textTertiary, modifier = Modifier.width(72.dp))
    }
    Divider(Modifier.padding(bottom = 6.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    model: AppModel,
    track: Track,
    number: Int,
    playing: Boolean,
    showAlbum: Boolean,
    onPlay: () -> Unit,
    extraMenu: List<ContextMenuItem> = emptyList(),
) {
    val c = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ContextMenuArea(items = { songMenu(model, track, onPlay, extraMenu) }) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (hovered) c.surface else Color.Transparent)
                .hoverable(interaction)
                .combinedClickable(interactionSource = interaction, indication = null, onDoubleClick = onPlay, onClick = {})
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(36.dp)) {
                when {
                    hovered -> Icon(AuraIcons.Play, "Tocar", tint = c.textPrimary, modifier = Modifier.size(16.dp).clickable(onClick = onPlay).pointerHoverIcon(PointerIcon.Hand))
                    playing -> Icon(AuraIcons.Wave, "Tocando", tint = c.accent, modifier = Modifier.size(16.dp))
                    else -> Text("$number", style = AuraType.small, color = c.textTertiary)
                }
            }
            Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
                Cover(track, model.artwork, 40.dp, 6.dp)
                Column(Modifier.padding(start = 12.dp, end = 16.dp)) {
                    Text(track.title, style = AuraType.bodyStrong, color = if (playing) c.accent else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist ?: "Artista desconhecido", style = AuraType.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (showAlbum) Text(track.album ?: "—", style = AuraType.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp))
            Text(if (track.durationMs > 0) formatDuration(track.durationMs) else "—", style = AuraType.small, color = c.textTertiary, modifier = Modifier.width(72.dp))
        }
    }
}

private fun songMenu(model: AppModel, track: Track, onPlay: () -> Unit, extra: List<ContextMenuItem>) = listOf(
    ContextMenuItem("Tocar", onPlay),
    ContextMenuItem("Tocar a seguir") { model.player.playNext(listOf(track)) },
    ContextMenuItem("Adicionar à fila") { model.player.addToQueue(listOf(track)) },
    ContextMenuItem("Adicionar à playlist…") { model.dialog = Dialog.AddToPlaylist(listOf(track)) },
    ContextMenuItem(if (model.isFavorite(track)) "Remover das favoritas" else "Favoritar") { model.toggleFavorite(track) },
) + extra + sendTargets(model).map { (id, name) ->
    ContextMenuItem("Enviar para $name") { model.link?.sendFiles(id, listOf(track)) }
} + listOf(
    ContextMenuItem("Abrir pasta do arquivo") { model.showInFolder(track) },
)

/** Connected devices that accept files: (deviceId, name). */
fun sendTargets(model: AppModel): List<Pair<String, String>> =
    model.link?.state?.value?.peers.orEmpty()
        .filter { it.connected && com.aura.music.link.LinkCapabilities.FILES in it.capabilities }
        .map { it.device.deviceId to it.device.name }

@Composable
fun AlbumGrid(model: AppModel, albums: List<Album>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(176.dp),
        contentPadding = ContentPadding,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }, contentType = { "album" }) { album ->
            AlbumCard(model, album) { model.open(Screen.Album(album.id)) }
        }
    }
}

@Composable
private fun AlbumCard(model: AppModel, album: Album, onOpen: () -> Unit) {
    val c = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val cover = remember(album.id) { model.library.state.value.index.albumTracks(album.id).firstOrNull() }
    Column(
        Modifier.hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onOpen).pointerHoverIcon(PointerIcon.Hand),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(cover, model.artwork, maxWidth, 14.dp, sizePx = 360)
            if (hovered) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(10.dp).size(44.dp).clip(CircleShape).background(c.accent)
                        .clickable { model.player.playTracks(model.library.state.value.index.albumTracks(album.id), 0) },
                    contentAlignment = Alignment.Center,
                ) { Icon(AuraIcons.Play, "Tocar álbum", tint = c.onAccent, modifier = Modifier.size(20.dp)) }
            }
        }
        Text(album.title ?: "Sem álbum", style = AuraType.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(album.artist ?: "Vários artistas", style = AuraType.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AlbumDetail(model: AppModel, albumId: Long) {
    val index = model.library.state.value.index
    val album = index.album(albumId) ?: return EmptyState("Álbum não encontrado", "Ele pode ter saído da biblioteca.")
    val tracks = index.albumTracks(albumId)
    SongList(model, tracks, showAlbum = false, header = {
        CollectionHeader(
            model = model,
            cover = tracks.firstOrNull(),
            kind = "ÁLBUM",
            title = album.title ?: "Sem álbum",
            subtitle = listOfNotNull(album.artist, album.year?.toString(), countLabel(tracks.size), "${totalMinutes(album.durationMs)} min").joinToString(" · "),
            tracks = tracks,
        )
    })
}

@Composable
fun ArtistList(model: AppModel, artists: List<Artist>) {
    val c = Aura.colors
    val state = rememberLazyListState()
    LazyColumn(state = state, contentPadding = ContentPadding, modifier = Modifier.fillMaxSize()) {
        items(artists, key = { it.id }, contentType = { "artist" }) { artist ->
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val sample = remember(artist.id) { model.library.state.value.index.artistTracks(artist.id).firstOrNull() }
            Row(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (hovered) c.surface else Color.Transparent)
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) { model.open(Screen.Artist(artist.id)) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(sample, model.artwork, 44.dp, 22.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(artist.name ?: "Artista desconhecido", style = AuraType.bodyStrong, color = c.textPrimary, maxLines = 1)
                    Text("${countLabel(artist.trackCount)} · ${artist.albumCount} ${if (artist.albumCount == 1) "álbum" else "álbuns"}", style = AuraType.small, color = c.textSecondary)
                }
            }
        }
    }
}

@Composable
fun ArtistDetail(model: AppModel, artistId: Long) {
    val index = model.library.state.value.index
    val artist = index.artist(artistId) ?: return EmptyState("Artista não encontrado", "Pode ter saído da biblioteca.")
    val tracks = index.artistTracks(artistId).sortedWith(compareBy({ it.album ?: "" }, { it.discNumber }, { it.trackNumber }))
    SongList(model, tracks, header = {
        CollectionHeader(
            model = model,
            cover = tracks.firstOrNull(),
            kind = "ARTISTA",
            title = artist.name ?: "Artista desconhecido",
            subtitle = "${countLabel(tracks.size)} · ${artist.albumCount} ${if (artist.albumCount == 1) "álbum" else "álbuns"}",
            tracks = tracks,
            round = true,
        )
    })
}

@Composable
fun FolderDetail(model: AppModel, path: String) {
    val tracks = model.library.state.value.index.folderTracks(path)
    SongList(model, tracks, header = {
        CollectionHeader(model, tracks.firstOrNull(), "PASTA", path.trimEnd('/').substringAfterLast('/'), "${countLabel(tracks.size)} · $path", tracks)
    })
}

@Composable
private fun CollectionHeader(
    model: AppModel,
    cover: Track?,
    kind: String,
    title: String,
    subtitle: String,
    tracks: List<Track>,
    round: Boolean = false,
) {
    val c = Aura.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 24.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Cover(cover, model.artwork, 196.dp, if (round) 98.dp else 18.dp, sizePx = 420)
        Column(Modifier.padding(start = 28.dp).weight(1f)) {
            Text(kind, style = AuraType.label, color = c.textTertiary)
            Text(title, style = AuraType.display, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Text(subtitle, style = AuraType.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Tocar", { model.player.playTracks(tracks, 0) }, AuraIcons.Play)
                PillButton("Aleatório", {
                    if (!model.player.state.value.queue.shuffle) model.player.toggleShuffle()
                    model.player.playTracks(tracks, tracks.indices.random())
                }, AuraIcons.Shuffle, primary = false)
                PillButton("Adicionar à fila", { model.player.addToQueue(tracks) }, AuraIcons.AddToQueue, primary = false)
                AuraIconButton(AuraIcons.Playlist, "Adicionar tudo a uma playlist", { model.dialog = Dialog.AddToPlaylist(tracks) })
                sendTargets(model).forEach { (id, name) ->
                    AuraIconButton(AuraIcons.Download, "Enviar tudo para $name", { model.link?.sendFiles(id, tracks) })
                }
            }
        }
    }
}

@Composable
fun FoldersScreen(model: AppModel, onAddFolder: () -> Unit) {
    val c = Aura.colors
    val state = model.library.state.value
    LazyColumn(contentPadding = ContentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Text("PASTAS DA BIBLIOTECA", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
        }
        items(state.folders, key = { "root:$it" }) { folder ->
            val unavailable = folder in state.unavailableFolders
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AuraIcons.Folder, null, tint = if (unavailable) c.textTertiary else c.accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(folder, style = AuraType.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (unavailable) "Indisponível agora (disco desconectado?). As músicas voltam quando ela voltar." else "Monitorada pelo AURA",
                        style = AuraType.small,
                        color = if (unavailable) c.danger else c.textSecondary,
                    )
                }
                AuraIconButton(AuraIcons.Close, "Remover pasta da biblioteca (os arquivos não são apagados)", { model.library.removeFolder(folder) })
            }
        }
        item {
            Row(Modifier.padding(top = 14.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Adicionar pasta", onAddFolder, AuraIcons.Plus)
                PillButton("Atualizar biblioteca", { model.library.rescan() }, AuraIcons.Refresh, primary = false)
            }
        }
        if (state.index.folders.isNotEmpty()) {
            item { Text("PASTAS COM MÚSICAS", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(bottom = 10.dp)) }
            items(state.index.folders, key = { "f:${it.path}" }) { folder ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (hovered) c.surface else Color.Transparent)
                        .hoverable(interaction)
                        .clickable(interactionSource = interaction, indication = null) { model.open(Screen.Folder(folder.path)) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AuraIcons.Folder, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                    Text(folder.name, style = AuraType.bodyStrong, color = c.textPrimary, modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
                    Text(folder.path, style = AuraType.small, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f).padding(horizontal = 12.dp))
                    Text(countLabel(folder.trackCount), style = AuraType.small, color = c.textSecondary)
                }
            }
        }
    }
}

@Composable
fun SearchResults(model: AppModel, results: SmartResults) {
    val c = Aura.colors
    if (results.isEmpty) {
        EmptyState("Nada encontrado para “${results.query}”", "A busca ignora acentos e aceita pequenos erros de digitação. Tente outra palavra.")
        return
    }
    val tracks = results.tracks
    LazyColumn(contentPadding = ContentPadding, modifier = Modifier.fillMaxSize()) {
        results.top?.let { top ->
            item(key = "top") {
                Text("MELHOR RESULTADO", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                TopResultCard(model, top, tracks)
            }
        }
        if (results.artists.isNotEmpty() || results.albums.isNotEmpty()) {
            item(key = "groups") {
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    results.artists.take(4).forEach { a -> Chip(a.name ?: "Artista", AuraIcons.Artist) { model.open(Screen.Artist(a.id)) } }
                    results.albums.take(4).forEach { a -> Chip(a.title ?: "Álbum", AuraIcons.Album) { model.open(Screen.Album(a.id)) } }
                }
            }
        }
        if (tracks.isNotEmpty()) {
            item(key = "songs-title") { Text("MÚSICAS", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(bottom = 6.dp)) }
            itemsIndexed(tracks, key = { _, t -> "s:${t.id}" }) { i, t ->
                SongRow(model, t, i + 1, t.id == model.player.state.value.current?.id, showAlbum = true, onPlay = { model.player.playTracks(tracks, i) })
            }
        }
    }
}

@Composable
private fun TopResultCard(model: AppModel, top: TopResult, tracks: List<Track>) {
    val c = Aura.colors
    val index = model.library.state.value.index
    val (title, subtitle, cover, action) = when (top) {
        is TopResult.OfTrack -> Quad(top.track.title, "Música · ${top.track.artist ?: "Artista desconhecido"}", top.track) {
            model.player.playTracks(tracks, tracks.indexOf(top.track).coerceAtLeast(0))
        }
        is TopResult.OfArtist -> Quad(top.artist.name ?: "Artista", "Artista · ${countLabel(top.artist.trackCount)}", index.artistTracks(top.artist.id).firstOrNull()) {
            model.open(Screen.Artist(top.artist.id))
        }
        is TopResult.OfAlbum -> Quad(top.album.title ?: "Álbum", "Álbum · ${top.album.artist ?: "Vários artistas"}", index.albumTracks(top.album.id).firstOrNull()) {
            model.open(Screen.Album(top.album.id))
        }
    }
    Row(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .clickable(onClick = action).pointerHoverIcon(PointerIcon.Hand).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(cover, model.artwork, 88.dp, 12.dp, sizePx = 200)
        Column(Modifier.padding(start = 18.dp).weight(1f)) {
            Text(title, style = AuraType.title, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = AuraType.body, color = c.textSecondary, maxLines = 1)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(if (top is TopResult.OfTrack) AuraIcons.Play else AuraIcons.ChevronRight, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
        }
    }
}

private data class Quad(val a: String, val b: String, val c: Track?, val d: () -> Unit)

@Composable
private fun Chip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = Aura.colors
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(c.surface).clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(16.dp))
        Text(text, style = AuraType.bodyStrong, color = c.textPrimary, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val c = Aura.colors
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 460.dp)) {
            AuraMark(64.dp)
            Text(title, style = AuraType.title, color = c.textPrimary, modifier = Modifier.padding(top = 20.dp))
            Text(body, style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(22.dp))
                action()
            }
        }
    }
}

@Composable
fun ScanningBanner(progress: ScanProgress) {
    val c = Aura.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
        Text(
            if (progress.total == 0) "Procurando músicas…" else "Atualizando a biblioteca · ${progress.done} de ${progress.total} arquivos",
            style = AuraType.small,
            color = c.textSecondary,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

fun countLabel(n: Int) = if (n == 1) "1 música" else "$n músicas"
