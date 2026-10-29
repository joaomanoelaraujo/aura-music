package com.aura.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.desktop.discover.DesktopDiscover
import com.aura.desktop.discover.DesktopDownloads
import com.aura.desktop.discover.DownloadStatus
import com.aura.desktop.discover.LinkResult
import com.aura.music.core.common.formatDuration
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.data.discover.AudioOption
import com.aura.music.data.discover.DiscoverException
import com.aura.music.data.discover.DiscoverItem
import com.aura.music.data.discover.Http
import com.aura.music.data.discover.ItemDetail
import com.aura.music.data.discover.LinkAnalyzer
import com.aura.music.data.discover.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Desktop
import java.net.URI

/** Remote cover images (search results), kept small and cached for the session. */
private object RemoteImages {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 120
    }

    suspend fun load(url: String): ImageBitmap? {
        synchronized(cache) { cache[url]?.let { return it } }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val connection = Http.open(url, "GET")
                try {
                    val bytes = connection.inputStream.use { it.readNBytes(2 * 1024 * 1024) }
                    com.aura.desktop.library.ArtworkStore.shrink(bytes, 240)?.let { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        } ?: return null
        synchronized(cache) { cache[url] = bitmap }
        return bitmap
    }
}

private sealed interface DiscoverView {
    data object Idle : DiscoverView
    data object Loading : DiscoverView
    data class Results(val items: List<DiscoverItem>) : DiscoverView
    data class Detail(val detail: ItemDetail) : DiscoverView
    data class Streaming(val item: DiscoverItem) : DiscoverView
    data class Error(val message: String) : DiscoverView
}

@Composable
fun DiscoverScreen(model: AppModel, discover: DesktopDiscover, downloads: DesktopDownloads) {
    val c = Aura.colors
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var view by remember { mutableStateOf<DiscoverView>(DiscoverView.Idle) }
    var back by remember { mutableStateOf<DiscoverView?>(null) }

    fun failure(e: Throwable) = DiscoverView.Error(
        (e as? DiscoverException)?.let { DesktopDownloads.reasonText(it.reason) } ?: "Não foi possível falar com a origem. Confira a internet.",
    )

    fun run() {
        val text = input.trim()
        if (text.isEmpty()) return
        view = DiscoverView.Loading
        back = null
        scope.launch {
            view = runCatching {
                if (LinkAnalyzer.looksLikeLink(text)) {
                    when (val r = discover.analyze(text)) {
                        is LinkResult.Downloadable -> DiscoverView.Detail(r.detail)
                        is LinkResult.Streaming -> DiscoverView.Streaming(r.item)
                        is LinkResult.Invalid -> DiscoverView.Error(r.message)
                    }
                } else {
                    DiscoverView.Results(discover.search(text))
                }
            }.getOrElse(::failure)
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            BasicTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                textStyle = AuraType.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { if (it.key == Key.Enter) { run(); true } else false },
                decorationBox = { inner ->
                    Row(Modifier.clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.outline, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AuraIcons.Discover, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                        Box(Modifier.weight(1f).padding(start = 10.dp)) {
                            if (input.isEmpty()) Text("Música, artista ou álbum — ou cole um link (Enter)", style = AuraType.body, color = c.textTertiary)
                            inner()
                        }
                    }
                },
            )
            Text(
                "A busca vai para o Internet Archive (archive.org) e só mostra itens com licença aberta declarada por quem os publicou. Sua biblioteca e seu histórico não são enviados.",
                style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            Box(Modifier.weight(1f)) {
                when (val v = view) {
                    DiscoverView.Idle -> EmptyState("Descobrir", "Encontre músicas com licença aberta para baixar, ou cole o link de um arquivo de áudio. Links do YouTube e do Spotify abrem no app oficial.")
                    DiscoverView.Loading -> EmptyState("Procurando…", "")
                    is DiscoverView.Error -> EmptyState("Não deu certo", v.message)
                    is DiscoverView.Results -> if (v.items.isEmpty()) {
                        EmptyState("Nada encontrado", "Tente outras palavras. Só aparecem itens com licença aberta.")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                            items(v.items, key = { it.key }) { item ->
                                ResultRow(item) {
                                    back = v
                                    view = DiscoverView.Loading
                                    scope.launch { view = runCatching { DiscoverView.Detail(discover.detail(item)) }.getOrElse(::failure) }
                                }
                            }
                        }
                    }
                    is DiscoverView.Detail -> DetailView(model, v.detail, downloads, onBack = back?.let { b -> { view = b; back = null } })
                    is DiscoverView.Streaming -> StreamingCard(v.item)
                }
            }
        }
        DownloadsPanel(downloads, Modifier.padding(start = 24.dp))
    }
}

@Composable
private fun RemoteCoverImage(url: String?, size: Int) {
    val c = Aura.colors
    val bitmap by produceState<ImageBitmap?>(null, url) { value = url?.let { RemoteImages.load(it) } }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceElevated), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(AuraIcons.Note, null, tint = c.textTertiary, modifier = Modifier.size((size / 3).dp))
    }
}

@Composable
private fun ResultRow(item: DiscoverItem, onOpen: () -> Unit) {
    val c = Aura.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).pointerHoverIcon(PointerIcon.Hand).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteCoverImage(item.coverUrl, 56)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(item.title, style = AuraType.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(item.artist, "Internet Archive").joinToString(" · "), style = AuraType.small, color = c.textSecondary, maxLines = 1)
        }
        item.license?.let { LicenseChip(it) }
    }
}

@Composable
private fun LicenseChip(label: String) {
    val c = Aura.colors
    Text(label, style = AuraType.label, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.accent.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun DetailView(model: AppModel, detail: ItemDetail, downloads: DesktopDownloads, onBack: (() -> Unit)?) {
    val c = Aura.colors
    val item = detail.item
    val targets = sendTargets(model)
    var sendTo by remember(item.key) { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) AuraIconButton(AuraIcons.Back, "Voltar aos resultados", onBack)
                RemoteCoverImage(item.coverUrl, 120)
                Column(Modifier.padding(start = 18.dp).weight(1f)) {
                    Text(item.title, style = AuraType.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(item.artist, if (item.source == SourceKind.InternetArchive) "Internet Archive" else "Link direto").joinToString(" · "), style = AuraType.body, color = c.textSecondary)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item.license?.let { LicenseChip("Licença declarada: $it") }
                        PillButton("Abrir página de origem", { runCatching { Desktop.getDesktop().browse(URI(item.pageUrl)) } }, primary = false)
                    }
                }
            }
            Text(
                if (item.source == SourceKind.InternetArchive) "A licença é a que quem publicou declarou no Internet Archive; o AURA não a verifica. Confira na página de origem antes de usar além do pessoal."
                else "Você está baixando de um endereço que informou. Baixe só o que você tem direito de baixar.",
                style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(vertical = 12.dp).widthIn(max = 720.dp),
            )
            if (targets.isNotEmpty()) {
                Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Destino:", style = AuraType.bodyStrong, color = c.textPrimary)
                    DestinationChip("Só este computador", sendTo == null) { sendTo = null }
                    targets.forEach { (id, name) -> DestinationChip("Computador + $name", sendTo == id) { sendTo = id } }
                }
            }
            if (detail.tracks.isEmpty()) Text("Esse item não tem arquivos de áudio que o AURA possa baixar.", style = AuraType.body, color = c.textSecondary)
        }
        items(detail.tracks, key = { it.title + it.trackNumber }) { track ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(14.dp)) {
                Text(listOfNotNull(track.trackNumber?.toString(), track.title).joinToString(". "), style = AuraType.bodyStrong, color = c.textPrimary)
                track.durationSec?.let { Text(formatDuration((it * 1000).toLong()), style = AuraType.small, color = c.textTertiary) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    track.options.take(5).forEach { option ->
                        PillButton(optionLabel(option), { downloads.enqueue(item, track, option, sendTo) }, AuraIcons.Download, primary = option == track.options.first())
                    }
                }
            }
        }
    }
}

/** What the source says about a file; never invents quality it didn't state. */
private fun optionLabel(o: AudioOption): String = listOf(
    o.formatLabel + if (o.original) " (original)" else "",
    o.sizeBytes?.let(::formatBytes) ?: "tamanho não informado",
).joinToString(" · ")

@Composable
private fun DestinationChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Aura.colors
    Text(
        label, style = AuraType.bodyStrong, color = if (selected) c.onAccent else c.textPrimary,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent else c.surfaceElevated)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun StreamingCard(item: DiscoverItem) {
    val c = Aura.colors
    val service = if (item.source == SourceKind.YouTube) "YouTube" else "Spotify"
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteCoverImage(item.coverUrl, 96)
        Column(Modifier.padding(start = 18.dp).weight(1f)) {
            Text(item.title, style = AuraType.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.artist?.let { Text(it, style = AuraType.body, color = c.textSecondary) }
            Text("O $service não permite baixar arquivos. Ouça no app ou site oficial.", style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(vertical = 8.dp))
            PillButton("Abrir no $service", { runCatching { Desktop.getDesktop().browse(URI(item.pageUrl)) } }, AuraIcons.Play)
        }
    }
}

@Composable
private fun DownloadsPanel(downloads: DesktopDownloads, modifier: Modifier) {
    val c = Aura.colors
    val list by downloads.downloads.collectAsState()
    Column(modifier.widthIn(min = 300.dp, max = 340.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", style = AuraType.title, color = c.textPrimary, modifier = Modifier.weight(1f))
            if (list.any { !it.active }) AuraIconButton(AuraIcons.Trash, "Limpar concluídos", downloads::clearFinished, iconSize = 18.dp)
        }
        if (list.isEmpty()) Text("Os arquivos baixados entram na biblioteca.", style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
        LazyColumn {
            items(list, key = { it.id }) { d ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, style = AuraType.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    when (d.status) {
                                        DownloadStatus.Queued -> "Na fila"
                                        DownloadStatus.Running -> "Baixando"
                                        DownloadStatus.Done -> if (d.sendTo != null) "Salvo · enviando ao aparelho" else "Salvo na biblioteca"
                                        DownloadStatus.Failed -> "Falhou"
                                        DownloadStatus.Cancelled -> "Cancelado"
                                    },
                                    "${formatBytes(d.doneBytes)}${d.totalBytes?.let { " de " + formatBytes(it) } ?: ""}",
                                    d.error,
                                ).joinToString(" · "),
                                style = AuraType.small, color = if (d.status == DownloadStatus.Failed) c.danger else c.textSecondary,
                            )
                        }
                        if (d.active) AuraIconButton(AuraIcons.Close, "Cancelar download", { downloads.cancel(d.id) }, size = 28.dp, iconSize = 14.dp)
                        if (d.status == DownloadStatus.Failed) AuraIconButton(AuraIcons.Refresh, "Tentar de novo", { downloads.retry(d.id) }, size = 28.dp, iconSize = 14.dp)
                    }
                    val total = d.totalBytes
                    if (d.active && total != null && total > 0) {
                        ScrubBar(d.doneBytes.toFloat() / total, onChange = {}, onCommit = {}, label = "Progresso do download", modifier = Modifier.fillMaxWidth().height(14.dp).padding(top = 6.dp))
                    }
                }
            }
        }
    }
}
