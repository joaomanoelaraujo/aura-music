package com.aura.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.desktop.playback.QueueItem
import com.aura.desktop.playback.Repeat
import com.aura.music.core.common.formatDuration
import com.aura.music.core.designsystem.icons.AuraIcons

/** The persistent player at the bottom of the window. */
@Composable
fun PlayerBar(model: AppModel) {
    val c = Aura.colors
    val state by model.player.state.collectAsState()
    val position by model.player.position.collectAsState()
    val track = state.current
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    Row(
        Modifier.fillMaxWidth().height(88.dp).background(c.surface.copy(alpha = 0.92f)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Now playing
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Cover(
                track, model.artwork, 56.dp, 10.dp, sizePx = 140,
                modifier = Modifier.clickable(enabled = track != null) {
                    if (model.screen == Screen.NowPlaying) model.back() else model.open(Screen.NowPlaying)
                }.pointerHoverIcon(PointerIcon.Hand),
            )
            Column(Modifier.padding(start = 14.dp, end = 12.dp)) {
                model.link?.let { link ->
                    val controller = link.state.collectAsState().value.controlledBy
                    if (controller != null) Text("Controlado por: $controller", style = AuraType.label, color = c.accent, maxLines = 1)
                }
                Text(track?.title ?: "Nada tocando", style = AuraType.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    track?.let { listOfNotNull(it.artist ?: "Artista desconhecido", it.album).joinToString(" · ") } ?: "Escolha uma música para começar",
                    style = AuraType.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (track != null) {
                val favorite = model.userData.value.collectAsState().value.favorites.contains(track.uri)
                AuraIconButton(if (favorite) AuraIcons.HeartFilled else AuraIcons.Heart, if (favorite) "Remover das favoritas" else "Favoritar", { model.toggleFavorite(track) }, active = favorite, iconSize = 18.dp)
            }
        }

        // Transport and seek
        Column(Modifier.weight(1.4f).widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuraIconButton(AuraIcons.Shuffle, if (state.queue.shuffle) "Aleatório: ligado" else "Aleatório: desligado", model.player::toggleShuffle, active = state.queue.shuffle, iconSize = 18.dp)
                AuraIconButton(AuraIcons.Previous, "Anterior (Ctrl+←)", model.player::previous, enabled = track != null, tint = c.textPrimary)
                PlayButton(state.isPlaying, enabled = track != null, onClick = model.player::togglePlayPause)
                AuraIconButton(AuraIcons.Next, "Próxima (Ctrl+→)", model.player::next, enabled = track != null, tint = c.textPrimary)
                AuraIconButton(
                    if (state.queue.repeat == Repeat.One) AuraIcons.RepeatOne else AuraIcons.Repeat,
                    when (state.queue.repeat) { Repeat.Off -> "Repetir: desligado"; Repeat.All -> "Repetir: a fila"; Repeat.One -> "Repetir: esta música" },
                    model.player::cycleRepeat,
                    active = state.queue.repeat != Repeat.Off,
                    iconSize = 18.dp,
                )
            }
            val duration = state.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0
            val shown = scrubbing?.let { (it * duration).toLong() } ?: position
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text(formatDuration(shown), style = AuraType.small, color = c.textTertiary, modifier = Modifier.width(48.dp))
                ScrubBar(
                    fraction = if (duration > 0) shown.toFloat() / duration else 0f,
                    onChange = { scrubbing = it },
                    onCommit = { f -> scrubbing = null; if (duration > 0) model.player.seekTo((f * duration).toLong()) },
                    label = "Posição da música",
                    modifier = Modifier.weight(1f),
                )
                Text(if (duration > 0) formatDuration(duration) else "—", style = AuraType.small, color = c.textTertiary, modifier = Modifier.width(48.dp).padding(start = 10.dp))
            }
        }

        // Volume and queue
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val settings by model.settings.settings.collectAsState()
            AuraIconButton(AuraIcons.Lyrics, "Letra", { model.toggleSidePanel("lyrics") }, active = settings.queuePanelOpen && settings.sidePanel == "lyrics")
            AuraIconButton(AuraIcons.Queue, "Fila", { model.toggleSidePanel("queue") }, active = settings.queuePanelOpen && settings.sidePanel == "queue")
            Box(Modifier.width(8.dp))
            Icon(AuraIcons.Wave, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
            ScrubBar(
                fraction = state.volume,
                onChange = model.player::setVolume,
                onCommit = { v -> model.settings.update { it.copy(volume = v) } },
                label = "Volume do AURA",
                modifier = Modifier.width(120.dp).padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (enabled) (if (hovered) c.accent.copy(alpha = 0.88f) else c.accent) else c.surfaceHighest)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = if (playing) "Pausar (Espaço)" else "Tocar (Espaço)" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) AuraIcons.Pause else AuraIcons.Play, null, tint = if (enabled) c.onAccent else c.textTertiary, modifier = Modifier.size(20.dp))
    }
}

/** The side panel: the queue, or the lyrics of what's playing. */
@Composable
fun SidePanel(model: AppModel, tab: String) {
    val c = Aura.colors
    val state by model.player.state.collectAsState()
    Column(Modifier.fillMaxHeight().width(340.dp).background(c.surface.copy(alpha = 0.55f))) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelTab("Fila", tab == "queue") { model.settings.update { it.copy(sidePanel = "queue") } }
            PanelTab("Letra", tab == "lyrics") { model.settings.update { it.copy(sidePanel = "lyrics") } }
            Box(Modifier.weight(1f))
            if (tab == "queue" && state.queue.upcoming.isNotEmpty()) AuraIconButton(AuraIcons.Trash, "Limpar as próximas", model.player::clearUpcoming, iconSize = 18.dp)
            if (tab == "lyrics" && state.current != null) AuraIconButton(AuraIcons.Expand, "Abrir em tela grande", { model.open(Screen.NowPlaying) }, iconSize = 18.dp)
        }
        if (tab == "lyrics") LyricsView(model, state.current, large = false) else QueueList(model)
    }
}

@Composable
private fun PanelTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Aura.colors
    Text(
        label,
        style = AuraType.title.copy(fontSize = androidx.compose.ui.unit.TextUnit(26f, androidx.compose.ui.unit.TextUnitType.Sp)),
        color = if (selected) c.textPrimary else c.textTertiary,
        modifier = Modifier.padding(end = 16.dp).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
    )
}

@Composable
private fun QueueList(model: AppModel) {
    val c = Aura.colors
    val state by model.player.state.collectAsState()
    val queue = state.queue
    Column(Modifier.fillMaxSize()) {
        if (queue.isEmpty) {
            Text(
                "A fila está vazia. Dê dois cliques numa música, ou use “Adicionar à fila” no menu do botão direito.",
                style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(20.dp),
            )
            return@Column
        }
        val listState = rememberLazyListState()
        // Keep the playing song in view when it changes.
        LaunchedEffect(queue.current?.uid) { if (queue.index > 0) listState.animateScrollToItem((queue.index - 1).coerceAtLeast(0)) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, bottom = 20.dp)) {
            itemsIndexed(queue.items, key = { _, item -> item.uid }) { i, item ->
                when {
                    i == queue.index -> {
                        Text("TOCANDO AGORA", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 6.dp))
                        QueueRow(model, item, i, current = true, played = false, last = i == queue.items.lastIndex)
                        if (i < queue.items.lastIndex) Text("A SEGUIR", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 6.dp))
                    }
                    else -> QueueRow(model, item, i, current = false, played = i < queue.index, last = i == queue.items.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun QueueRow(model: AppModel, item: QueueItem, position: Int, current: Boolean, played: Boolean, last: Boolean) {
    val c = Aura.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val track = item.track
    ContextMenuArea(items = {
        listOfNotNull(
            ContextMenuItem("Tocar agora") { model.player.jumpTo(position) }.takeIf { !current },
            ContextMenuItem("Mover para cima") { model.player.moveInQueue(position, position - 1) }.takeIf { position > 0 },
            ContextMenuItem("Mover para baixo") { model.player.moveInQueue(position, position + 1) }.takeIf { !last },
            ContextMenuItem("Remover da fila") { model.player.removeFromQueue(position) },
            ContextMenuItem("Abrir pasta do arquivo") { model.showInFolder(track) },
        )
    }) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp))
                .background(if (current) c.accent.copy(alpha = 0.10f) else if (hovered) c.surfaceElevated else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { if (!current) model.player.jumpTo(position) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(track, model.artwork, 40.dp, 6.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, style = AuraType.bodyStrong, color = if (current) c.accent else if (played) c.textTertiary else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist ?: "Artista desconhecido", style = AuraType.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hovered) AuraIconButton(AuraIcons.Close, "Remover da fila", { model.player.removeFromQueue(position) }, size = 28.dp, iconSize = 14.dp)
        }
    }
}
