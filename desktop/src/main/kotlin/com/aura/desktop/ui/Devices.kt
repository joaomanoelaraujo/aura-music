package com.aura.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.desktop.link.LinkService
import com.aura.desktop.link.PeerStatus
import com.aura.music.core.common.formatDuration
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.link.LinkCapabilities
import com.aura.music.link.LinkPermissions
import com.aura.music.link.Platform
import com.aura.music.link.RemoteAction
import com.aura.music.link.RemoteRepeat
import com.aura.music.link.RemoteTrack
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun DevicesScreen(model: AppModel) {
    val link = model.link ?: return EmptyState("Dispositivos indisponíveis", "O AURA Link não pôde iniciar neste computador.")
    val c = Aura.colors
    val state by link.state.collectAsState()
    LazyColumn(contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Vincule seu celular para controlar a música de um aparelho pelo outro. O vínculo é só entre os seus aparelhos e não tem relação com convites de jam.",
                style = AuraType.body, color = c.textSecondary, modifier = Modifier.widthIn(max = 720.dp).padding(bottom = 18.dp),
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AuraIcons.Wave, null, tint = c.accent, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(state.deviceName, style = AuraType.bodyStrong, color = c.textPrimary)
                    Text("Este computador · identidade ${state.fingerprint}", style = AuraType.small, color = c.textSecondary)
                }
                PillButton("Renomear", { model.dialog = Dialog.RenameDevice(null, state.deviceName) }, AuraIcons.Edit, primary = false)
            }
        }
        item {
            val pairing = state.pairing
            if (pairing == null) {
                Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Vincular um celular", { link.openPairing() }, AuraIcons.Plus)
                }
            } else {
                PairingPanel(link, pairing)
            }
        }
        state.message?.let { msg ->
            item {
                Row(Modifier.padding(bottom = 14.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceElevated).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = AuraType.body, color = c.textPrimary, modifier = Modifier.weight(1f, fill = false))
                    AuraIconButton(AuraIcons.Close, "Fechar aviso", link::dismissMessage, size = 28.dp, iconSize = 14.dp)
                }
            }
        }
        item {
            Text("APARELHOS VINCULADOS", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
            if (state.peers.isEmpty()) Text("Nenhum ainda.", style = AuraType.body, color = c.textSecondary)
        }
        items(state.peers, key = { it.device.deviceId }) { peer -> PeerCard(model, link, peer) }
        link.transfers?.let { transfers -> item { TransfersSection(link, transfers) } }
        item {
            Text(
                "Na primeira vez, o Windows pode perguntar se o AURA pode usar a rede: permita em redes privadas para o celular encontrar este computador. As conexões são cifradas e só aparelhos vinculados são aceitos.",
                style = AuraType.small, color = c.textTertiary, modifier = Modifier.widthIn(max = 720.dp).padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun PairingPanel(link: LinkService, pairing: com.aura.desktop.link.PairingWindow) {
    val c = Aura.colors
    val remaining by produceState(pairing.expiresAt - System.currentTimeMillis(), pairing.expiresAt) {
        while (true) {
            value = (pairing.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
            delay(1_000)
        }
    }
    Row(
        Modifier.padding(vertical = 18.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QrCode(pairing.qrPayload, Modifier.size(212.dp))
        Column(Modifier.padding(start = 28.dp).weight(1f)) {
            Text("Vincular um celular", style = AuraType.title, color = c.textPrimary)
            Text(
                "No AURA do celular, abra Configurações → Dispositivos → Vincular computador e aponte a câmera para o código. Sem câmera? Escolha este computador na lista e digite:",
                style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )
            Text(
                pairing.code.chunked(4).joinToString(" "),
                style = AuraType.display.copy(fontFamily = Manrope, fontSize = 40.sp, letterSpacing = 4.sp),
                color = c.textPrimary,
                modifier = Modifier.semantics { contentDescription = "Código de vínculo ${pairing.code}" },
            )
            Text(
                "Vale por ${formatDuration(remaining)} · ${pairing.attemptsLeft} tentativas · ${if (pairing.addresses.isEmpty()) "sem rede local detectada" else pairing.addresses.joinToString()}",
                style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Gerar outro código", { link.openPairing() }, AuraIcons.Refresh, primary = false)
                PillButton("Cancelar", { link.closePairing() }, primary = false)
            }
        }
    }
}

/** A QR code drawn dark-on-light (scanners expect that), from the pairing payload. */
@Composable
private fun QrCode(payload: String, modifier: Modifier) {
    val matrix = remember(payload) {
        QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp).semantics { contentDescription = "QR Code de vínculo" }) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) drawRect(Color(0xFF101114), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun PeerCard(model: AppModel, link: LinkService, peer: PeerStatus) {
    val c = Aura.colors
    val d = peer.device
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (peer.connected) Color(0xFF7BD88F) else if (peer.paused) c.danger else c.textTertiary))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(d.name, style = AuraType.bodyStrong, color = c.textPrimary)
                Text(
                    listOfNotNull(
                        platformLabel(d.platform),
                        when {
                            peer.connected -> "Conectado" + (peer.address?.let { " ($it)" } ?: "")
                            peer.paused -> "Desconectado por você"
                            else -> "Fora de alcance — abra o AURA no aparelho, na mesma rede"
                        },
                    ).joinToString(" · "),
                    style = AuraType.small, color = c.textSecondary,
                )
            }
            val canControl = peer.connected && peer.granted?.controlPlayback == true && LinkCapabilities.PLAYER in peer.capabilities
            if (canControl) PillButton("Controlar", { model.open(Screen.Remote(d.deviceId)) }, AuraIcons.Play)
            AuraIconButton(if (expanded) AuraIcons.ChevronDown else AuraIcons.More, if (expanded) "Fechar opções" else "Opções do aparelho", { expanded = !expanded })
        }
        if (expanded) {
            Divider(Modifier.padding(vertical = 14.dp))
            Text("O QUE “${d.name.uppercase()}” PODE FAZER NESTE COMPUTADOR", style = AuraType.label, color = c.textTertiary)
            PermissionRow("Controlar a reprodução (tocar, pausar, pular, volume, fila)", d.permissions.controlPlayback) {
                link.setPermissions(d.deviceId, d.permissions.copy(controlPlayback = it))
            }
            PermissionRow("Ver e tocar músicas da biblioteca deste computador", d.permissions.browseLibrary) {
                link.setPermissions(d.deviceId, d.permissions.copy(browseLibrary = it))
            }
            Text("Identidade do aparelho: ${d.fingerprint}", style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Renomear", { model.dialog = Dialog.RenameDevice(d.deviceId, d.name) }, AuraIcons.Edit, primary = false)
                if (peer.paused) PillButton("Permitir conexão", { link.resume(d.deviceId) }, AuraIcons.Refresh, primary = false)
                else PillButton("Desconectar por agora", { link.pause(d.deviceId) }, primary = false)
                PillButton("Remover vínculo", { model.dialog = Dialog.RevokeDevice(d.deviceId, d.name) }, AuraIcons.Trash, primary = false)
            }
        }
    }
}

@Composable
private fun TransfersSection(link: LinkService, transfers: com.aura.desktop.link.DesktopTransfers) {
    val c = Aura.colors
    val jobs by transfers.jobs.collectAsState()
    if (jobs.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("TRANSFERÊNCIAS", style = AuraType.label, color = c.textTertiary, modifier = Modifier.weight(1f))
        if (jobs.any { !it.active }) PillButton("Limpar concluídas", transfers::clearFinished, primary = false)
    }
    jobs.forEach { job ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (job.outgoing) AuraIcons.ArrowUp else AuraIcons.ArrowDown, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    val what = if (job.offer.files.size == 1) job.offer.files.first().title ?: job.offer.files.first().name else countLabel(job.offer.files.size)
                    Text((if (job.outgoing) "Para ${job.peerName}: " else "De ${job.peerName}: ") + what, style = AuraType.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(transferStatusText(job), "${formatBytes(job.doneBytes)} de ${formatBytes(job.totalBytes)}", job.error).joinToString(" · "),
                        style = AuraType.small, color = if (job.status == com.aura.desktop.link.TransferStatus.Failed) c.danger else c.textSecondary,
                    )
                }
                if (job.active) AuraIconButton(AuraIcons.Close, "Cancelar transferência", { link.cancelTransfer(job.id) })
                if (job.outgoing && job.status == com.aura.desktop.link.TransferStatus.Failed) AuraIconButton(AuraIcons.Refresh, "Tentar de novo (continua de onde parou)", { link.retryTransfer(job.id) })
            }
            if (job.totalBytes > 0 && job.status != com.aura.desktop.link.TransferStatus.Done) {
                ScrubBar(job.doneBytes.toFloat() / job.totalBytes, onChange = {}, onCommit = {}, label = "Progresso da transferência", modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

private fun transferStatusText(job: com.aura.desktop.link.TransferJob) = when (job.status) {
    com.aura.desktop.link.TransferStatus.Preparing -> "Preparando (conferindo os arquivos)"
    com.aura.desktop.link.TransferStatus.WaitingAnswer -> "Aguardando o aparelho aceitar"
    com.aura.desktop.link.TransferStatus.WaitingConnection -> "Aceito, aguardando conexão"
    com.aura.desktop.link.TransferStatus.Running -> if (job.outgoing) "Enviando" else "Recebendo"
    com.aura.desktop.link.TransferStatus.Done -> if (job.outgoing) "Enviado e conferido" else "Recebido e conferido"
    com.aura.desktop.link.TransferStatus.Failed -> "Falhou"
    com.aura.desktop.link.TransferStatus.Cancelled -> "Cancelado"
    com.aura.desktop.link.TransferStatus.Refused -> "Não aceito"
}

fun formatBytes(bytes: Long): String = com.aura.music.core.common.formatFileSize(bytes)

/** Shown over any screen when a linked device wants to send files here. */
@Composable
fun IncomingTransferOverlay(model: AppModel) {
    val transfers = model.link?.transfers ?: return
    val incoming by transfers.incoming.collectAsState()
    val offer = incoming ?: return
    val c = Aura.colors
    var always by remember(offer.offer.id) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(22.dp)).background(c.surfaceElevated).padding(26.dp)) {
            Text("Receber músicas de “${offer.peerName}”?", style = AuraType.title, color = c.textPrimary)
            Text(
                "${countLabel(offer.offer.files.size)} · ${formatBytes(offer.offer.totalBytes)}. Ficam em ${model.receiveFolder()} e entram na biblioteca.",
                style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            offer.offer.files.take(6).forEach { f ->
                Text("• " + listOfNotNull(f.title ?: f.name, f.artist).joinToString(" — "), style = AuraType.small, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (offer.offer.files.size > 6) Text("… e mais ${offer.offer.files.size - 6}", style = AuraType.small, color = c.textTertiary)
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(always, { always = it }, "Sempre aceitar deste aparelho")
                Text("Sempre aceitar deste aparelho", style = AuraType.body, color = c.textPrimary, modifier = Modifier.padding(start = 10.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Recusar", { transfers.answerIncoming(false, false) }, primary = false)
                PillButton("Receber", { transfers.answerIncoming(true, always) }, AuraIcons.Download)
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AuraType.body, color = Aura.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked, onChange, label)
    }
}

fun platformLabel(p: Platform) = when (p) {
    Platform.Android -> "Android"
    Platform.Windows -> "Windows"
    Platform.MacOS -> "macOS"
    Platform.Linux -> "Linux"
}

/** Shown over any screen when a device passed the code check and waits for the user. */
@Composable
fun PairingOfferOverlay(model: AppModel) {
    val link = model.link ?: return
    val state by link.state.collectAsState()
    val offer = state.offer ?: return
    val c = Aura.colors
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(22.dp)).background(c.surfaceElevated).padding(26.dp)) {
            Text("Vincular “${offer.name}”?", style = AuraType.title, color = c.textPrimary)
            Text("${platformLabel(offer.platform)} · identidade ${offer.fingerprint}", style = AuraType.small, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
            Text("Confira se o aparelho mostra o mesmo número:", style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 18.dp))
            Text(
                offer.verificationCode.chunked(3).joinToString(" "),
                style = AuraType.display.copy(fontFamily = Manrope, fontSize = 44.sp, letterSpacing = 6.sp),
                color = c.accent, modifier = Modifier.padding(vertical = 10.dp),
            )
            Text("Se os números forem diferentes, recuse: outra pessoa pode estar tentando se passar pelo seu aparelho.", style = AuraType.small, color = c.textTertiary)
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                PillButton("Recusar", { link.answerOffer(false) }, primary = false)
                PillButton("Vincular", { link.answerOffer(true) }, AuraIcons.Check)
            }
        }
    }
}

// ---------------------------------------------------------------- remote control

/** Controlling a paired device's player: what it plays, its queue, and its library. */
@Composable
fun RemoteScreen(model: AppModel, deviceId: String) {
    val link = model.link ?: return
    val c = Aura.colors
    val state by link.state.collectAsState()
    val peer = state.peers.firstOrNull { it.device.deviceId == deviceId } ?: return EmptyState("Aparelho não encontrado", "O vínculo pode ter sido removido.")
    LaunchedEffect(deviceId, peer.connected) { if (peer.connected) link.requestState(deviceId) }
    if (!peer.connected) {
        EmptyState("“${peer.device.name}” está fora de alcance", "Abra o AURA no aparelho, na mesma rede Wi-Fi ou hotspot. A música deste computador continua normalmente.")
        return
    }
    val remote = peer.remoteState
    val granted = peer.granted ?: LinkPermissions(false, false)
    fun send(action: RemoteAction) = link.command(deviceId, action)
    val now by produceState(LinkService.monotonicMs()) { while (true) { value = LinkService.monotonicMs(); delay(250) } }
    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f).padding(end = 28.dp)) {
            Text("TOCANDO EM: ${peer.device.name.uppercase()}", style = AuraType.label, color = c.accent)
            Text("Controlando por: ${state.deviceName}", style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
            val track = remote?.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteCover(link, deviceId, track, 132)
                Column(Modifier.padding(start = 20.dp)) {
                    Text(track?.title ?: "Nada tocando", style = AuraType.title, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track?.let { listOfNotNull(it.artist, it.album).joinToString(" · ") } ?: "", style = AuraType.body, color = c.textSecondary, maxLines = 1)
                }
            }
            if (remote != null) {
                val position = if (remote.isPlaying) (remote.positionMs + (now - peer.stateReceivedAt)).coerceAtMost(remote.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE) else remote.positionMs
                var scrub by remember { mutableStateOf<Float?>(null) }
                val shown = scrub?.let { (it * remote.durationMs).toLong() } ?: position
                Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(shown), style = AuraType.small, color = c.textTertiary, modifier = Modifier.width(48.dp))
                    ScrubBar(
                        fraction = if (remote.durationMs > 0) shown.toFloat() / remote.durationMs else 0f,
                        onChange = { scrub = it },
                        onCommit = { f -> scrub = null; if (granted.controlPlayback) send(RemoteAction.Seek((f * remote.durationMs).toLong())) },
                        label = "Posição no aparelho remoto",
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatDuration(remote.durationMs), style = AuraType.small, color = c.textTertiary, modifier = Modifier.width(48.dp).padding(start = 10.dp))
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val enabled = granted.controlPlayback
                    AuraIconButton(AuraIcons.Shuffle, "Aleatório no aparelho", { send(RemoteAction.SetShuffle(!remote.shuffle)) }, active = remote.shuffle, enabled = enabled)
                    AuraIconButton(AuraIcons.Previous, "Anterior no aparelho", { send(RemoteAction.Previous) }, enabled = enabled, tint = c.textPrimary)
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(if (enabled) c.accent else c.surfaceHighest)
                            .clickable(enabled = enabled) { send(if (remote.isPlaying) RemoteAction.Pause else RemoteAction.Play) }.pointerHoverIcon(PointerIcon.Hand)
                            .semantics { contentDescription = if (remote.isPlaying) "Pausar no aparelho" else "Tocar no aparelho" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (remote.isPlaying) AuraIcons.Pause else AuraIcons.Play, null, tint = c.onAccent, modifier = Modifier.size(22.dp)) }
                    AuraIconButton(AuraIcons.Next, "Próxima no aparelho", { send(RemoteAction.Next) }, enabled = enabled, tint = c.textPrimary)
                    AuraIconButton(
                        if (remote.repeat == RemoteRepeat.One) AuraIcons.RepeatOne else AuraIcons.Repeat, "Repetir no aparelho",
                        { send(RemoteAction.SetRepeat(when (remote.repeat) { RemoteRepeat.Off -> RemoteRepeat.All; RemoteRepeat.All -> RemoteRepeat.One; RemoteRepeat.One -> RemoteRepeat.Off })) },
                        active = remote.repeat != RemoteRepeat.Off, enabled = enabled,
                    )
                    Spacer(Modifier.width(18.dp))
                    Icon(AuraIcons.Wave, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                    ScrubBar(remote.volume, onChange = {}, onCommit = { send(RemoteAction.SetVolume(it)) }, label = "Volume do AURA no aparelho", modifier = Modifier.width(140.dp).padding(start = 8.dp))
                }
                if (!granted.controlPlayback) Text("Esse aparelho não permite controle por este computador. Ajuste em Dispositivos, no aparelho.", style = AuraType.small, color = c.danger, modifier = Modifier.padding(top = 10.dp))
                Text("FILA NO APARELHO", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(top = 26.dp, bottom = 8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(remote.queue, key = { _, it -> it.uid }) { i, item ->
                        val current = i == remote.index
                        Row(
                            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(if (current) c.accent.copy(alpha = 0.1f) else Color.Transparent)
                                .clickable(enabled = granted.controlPlayback && !current) { send(RemoteAction.JumpTo(item.uid)) }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.track.title, style = AuraType.bodyStrong, color = if (current) c.accent else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.track.artist ?: "", style = AuraType.small, color = c.textSecondary, maxLines = 1)
                            }
                            if (granted.controlPlayback && !current) AuraIconButton(AuraIcons.Close, "Remover da fila do aparelho", { send(RemoteAction.Remove(item.uid)) }, size = 28.dp, iconSize = 14.dp)
                        }
                    }
                }
            }
        }
        Column(Modifier.width(380.dp)) {
            if (granted.browseLibrary) RemoteLibrary(link, deviceId) else {
                Text("Biblioteca do aparelho", style = AuraType.title, color = c.textPrimary)
                Text("Esse aparelho não compartilha a biblioteca com este computador.", style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun RemoteCover(link: LinkService, deviceId: String, track: RemoteTrack?, sizeDp: Int) {
    val c = Aura.colors
    val bitmap by produceState<ImageBitmap?>(null, track?.id) {
        value = track?.let { t -> link.remoteArtwork(deviceId, t.id)?.let { bytes -> runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() } }
    }
    Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceElevated), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(AuraIcons.Note, null, tint = c.textTertiary, modifier = Modifier.size((sizeDp / 3).dp))
    }
}

@Composable
private fun RemoteLibrary(link: LinkService, deviceId: String) {
    val c = Aura.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RemoteTrack>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run() = scope.launch {
        error = null
        results = link.search(deviceId, query) ?: run { error = "O aparelho não respondeu."; emptyList() }
    }
    LaunchedEffect(deviceId) { run() }
    Text("Biblioteca do aparelho", style = AuraType.title, color = c.textPrimary)
    BasicTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        textStyle = AuraType.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).onPreviewKeyEvent { if (it.key == Key.Enter) { run(); true } else false },
        decorationBox = { inner ->
            Row(Modifier.clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.outline, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AuraIcons.Search, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                Box(Modifier.padding(start = 8.dp)) {
                    if (query.isEmpty()) Text("Buscar no aparelho (Enter)", style = AuraType.body, color = c.textTertiary)
                    inner()
                }
            }
        },
    )
    error?.let { Text(it, style = AuraType.small, color = c.danger) }
    val list = results.orEmpty()
    LazyColumn(Modifier.heightIn(max = 2000.dp)) {
        itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
            Row(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(10.dp)).clickable { link.command(deviceId, RemoteAction.PlayTracks(list.map { it.id }, i)) }
                    .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = AuraType.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(t.artist, formatDuration(t.durationMs)).joinToString(" · "), style = AuraType.small, color = c.textSecondary, maxLines = 1)
                }
                AuraIconButton(AuraIcons.PlayNext, "Tocar a seguir no aparelho", { link.command(deviceId, RemoteAction.PlayNext(listOf(t.id))) }, size = 30.dp, iconSize = 16.dp)
                AuraIconButton(AuraIcons.AddToQueue, "Adicionar à fila do aparelho", { link.command(deviceId, RemoteAction.AddToQueue(listOf(t.id))) }, size = 30.dp, iconSize = 16.dp)
            }
        }
    }
    if (results != null && list.isEmpty() && error == null) Text("Nada encontrado.", style = AuraType.body, color = c.textSecondary)
}
