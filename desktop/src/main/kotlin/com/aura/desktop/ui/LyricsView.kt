package com.aura.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.desktop.lyrics.LoadedLyrics
import com.aura.desktop.lyrics.LyricsOrigin
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.model.Track
import com.aura.music.data.lyrics.LrcParser
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object Searching : LyricsState
    data class Found(val loaded: LoadedLyrics) : LyricsState
    data class Missing(val searchedOnline: Boolean) : LyricsState
}

/**
 * The lyrics of [track]: synced lyrics follow the song (click a line to jump there); plain lyrics
 * simply scroll. [large] is the Now Playing size.
 */
@Composable
fun LyricsView(model: AppModel, track: Track?, large: Boolean, modifier: Modifier = Modifier) {
    val c = Aura.colors
    if (track == null) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Toque uma música para ver a letra.", style = AuraType.body, color = c.textSecondary)
        }
        return
    }
    val settings by model.settings.settings.collectAsState()
    val state by produceState<LyricsState>(LyricsState.Loading, track.id, model.lyricsVersion, settings.onlineLyrics) {
        value = LyricsState.Loading
        val local = model.lyrics.local(track)
        if (local != null) {
            value = LyricsState.Found(local)
            return@produceState
        }
        if (!settings.onlineLyrics) {
            value = LyricsState.Missing(searchedOnline = false)
            return@produceState
        }
        value = LyricsState.Searching
        value = model.lyrics.fetchOnline(track)?.let { LyricsState.Found(it) } ?: LyricsState.Missing(searchedOnline = true)
    }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                LyricsState.Loading -> Unit
                LyricsState.Searching -> Centered("Procurando a letra no LRCLIB…")
                is LyricsState.Missing -> MissingLyrics(model, track, s.searchedOnline, settings.onlineLyrics)
                is LyricsState.Found -> if (s.loaded.lyrics.synced) SyncedLyrics(model, s.loaded, large) else PlainLyrics(s.loaded, large)
            }
        }
        (state as? LyricsState.Found)?.let { found -> LyricsFooter(model, track, found.loaded.origin) }
    }
}

@Composable
private fun SyncedLyrics(model: AppModel, loaded: LoadedLyrics, large: Boolean) {
    val c = Aura.colors
    val position by model.player.position.collectAsState()
    val lines = loaded.lyrics.lines
    val active by remember(lines) { derivedStateOf { LrcParser.activeIndex(lines, position + loaded.lyrics.fileOffsetMs) } }
    val listState = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val third = with(LocalDensity.current) { (maxHeight / 3).roundToPx() }
        LaunchedEffect(active) {
            if (active >= 0) listState.animateScrollToItem(active, scrollOffset = -third)
        }
        val style = if (large) TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 30.sp, lineHeight = 38.sp)
        else TextStyle(fontFamily = Manrope, fontWeight = FontWeight(800), fontSize = 19.sp, lineHeight = 25.sp)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = maxHeight / 3, horizontal = if (large) 8.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(if (large) 18.dp else 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines) { i, line ->
                val isActive = i == active
                val color by animateColorAsState(
                    when {
                        isActive -> c.textPrimary
                        i < active -> c.textPrimary.copy(alpha = 0.30f)
                        else -> c.textPrimary.copy(alpha = 0.42f)
                    },
                )
                val scale by animateFloatAsState(if (isActive) 1.03f else 1f)
                val text = line.text.ifBlank { "♪" }
                Text(
                    text,
                    style = style,
                    color = color,
                    modifier = Modifier
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                        .then(
                            line.timeMs?.let { t ->
                                Modifier.clickable { model.player.seekTo((t - loaded.lyrics.fileOffsetMs).coerceAtLeast(0)) }.pointerHoverIcon(PointerIcon.Hand)
                            } ?: Modifier,
                        ),
                )
            }
        }
    }
}

@Composable
private fun PlainLyrics(loaded: LoadedLyrics, large: Boolean) {
    val c = Aura.colors
    val style = if (large) AuraType.title.copy(fontFamily = Manrope, fontWeight = FontWeight(700), fontSize = 22.sp, lineHeight = 32.sp)
    else AuraType.body.copy(fontSize = 15.sp, lineHeight = 24.sp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (large) 8.dp else 20.dp, vertical = 16.dp)) {
        Text("Letra sem sincronia", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(bottom = 12.dp))
        Text(loaded.lyrics.lines.joinToString("\n") { it.text }, style = style, color = c.textPrimary.copy(alpha = 0.88f))
    }
}

@Composable
private fun MissingLyrics(model: AppModel, track: Track, searchedOnline: Boolean, onlineEnabled: Boolean) {
    val c = Aura.colors
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Sem letra", style = AuraType.title, color = c.textPrimary)
        Text(
            when {
                searchedOnline -> "Não está no arquivo nem no LRCLIB. Você pode procurar com outras palavras ou importar um .lrc."
                onlineEnabled -> "Não há letra no arquivo."
                else -> "Não há letra no arquivo. A busca automática no LRCLIB está desligada; ative em Configurações, ou procure só esta."
            },
            style = AuraType.body,
            color = c.textSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Procurar letra", { model.dialog = Dialog.LyricsSearch(track) }, AuraIcons.Search)
            PillButton("Importar .lrc", { chooseLyricsFile()?.let { model.importLyrics(track, it) } }, AuraIcons.FileText, primary = false)
        }
    }
}

@Composable
private fun LyricsFooter(model: AppModel, track: Track, origin: LyricsOrigin) {
    val c = Aura.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
        Text(
            when (origin) {
                LyricsOrigin.Custom -> "Letra escolhida por você"
                LyricsOrigin.Sidecar -> "Arquivo .lrc ao lado da música"
                LyricsOrigin.Embedded -> "Letra dentro do arquivo"
                LyricsOrigin.Online -> "Letra do LRCLIB, salva no computador"
            },
            style = AuraType.small,
            color = c.textTertiary,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        AuraIconButton(AuraIcons.Search, "Procurar outra letra", { model.dialog = Dialog.LyricsSearch(track) }, size = 30.dp, iconSize = 16.dp)
        AuraIconButton(AuraIcons.FileText, "Importar arquivo .lrc", { chooseLyricsFile()?.let { model.importLyrics(track, it) } }, size = 30.dp, iconSize = 16.dp)
        if (origin == LyricsOrigin.Custom || origin == LyricsOrigin.Online) {
            AuraIconButton(AuraIcons.Trash, "Remover a letra salva", { model.removeSavedLyrics(track) }, size = 30.dp, iconSize = 16.dp)
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = AuraType.body, color = Aura.colors.textSecondary)
    }
}

fun chooseLyricsFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Importar letra"
        fileFilter = FileNameExtensionFilter("Letras (.lrc, .txt)", "lrc", "txt")
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** The expanded player: big cover on the left, lyrics on the right, tinted by the cover. */
@Composable
fun NowPlayingScreen(model: AppModel) {
    val c = Aura.colors
    val state by model.player.state.collectAsState()
    val track = state.current
    if (track == null) {
        EmptyState("Nada tocando", "Escolha uma música na biblioteca.")
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 8.dp)) {
        val coverSize = minOf(maxWidth * 0.36f, maxHeight * 0.72f, 460.dp)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.padding(end = 40.dp)) {
                Cover(track, model.artwork, coverSize, 22.dp, sizePx = 720)
                Text(track.title, style = AuraType.display, color = c.textPrimary, maxLines = 2, modifier = Modifier.padding(top = 22.dp).width(coverSize))
                Text(listOfNotNull(track.artist ?: "Artista desconhecido", track.album).joinToString(" · "), style = AuraType.body, color = c.textSecondary, maxLines = 1)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val favorite = model.userData.value.collectAsState().value.favorites.contains(track.uri)
                    PillButton(if (favorite) "Favorita" else "Favoritar", { model.toggleFavorite(track) }, if (favorite) AuraIcons.HeartFilled else AuraIcons.Heart, primary = false)
                    PillButton("Adicionar à playlist", { model.dialog = Dialog.AddToPlaylist(listOf(track)) }, AuraIcons.Playlist, primary = false)
                }
            }
            LyricsView(model, track, large = true, modifier = Modifier.weight(1f))
        }
    }
}
