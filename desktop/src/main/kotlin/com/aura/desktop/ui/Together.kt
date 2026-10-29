package com.aura.desktop.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.desktop.together.DesktopTogether
import com.aura.desktop.together.JamRole
import com.aura.music.core.common.formatDuration
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.together.guest.GuestStatus
import com.aura.music.together.protocol.ControlMode
import com.aura.music.together.protocol.RejectReason
import com.aura.music.together.protocol.SessionState
import com.aura.music.together.transport.RelayStatus

@Composable
fun TogetherScreen(model: AppModel, together: DesktopTogether) {
    val role by together.role.collectAsState()
    when (val r = role) {
        JamRole.Idle -> IdleJam(model, together)
        is JamRole.Hosting -> HostingJam(model, together, r)
        is JamRole.Guest -> GuestJam(model, together, r)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = Aura.colors
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = AuraType.body.copy(color = c.textPrimary), cursorBrush = SolidColor(c.accent),
        modifier = modifier,
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.outline, RoundedCornerShape(12.dp)).padding(14.dp)) {
                if (value.isEmpty()) Text(placeholder, style = AuraType.body, color = c.textTertiary)
                inner()
            }
        },
    )
}

@Composable
private fun IdleJam(model: AppModel, together: DesktopTogether) {
    val c = Aura.colors
    val defaultName = model.link?.state?.value?.deviceName ?: "Computador"
    var name by remember { mutableStateOf("") }
    var democratic by remember { mutableStateOf(true) }
    var approve by remember { mutableStateOf(true) }
    var link by remember { mutableStateOf("") }
    var receiveMissing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Ouçam juntos, em sincronia: cada pessoa toca a música no próprio aparelho, celular ou computador. Uma jam é temporária e separada do vínculo dos seus aparelhos.",
                style = AuraType.body, color = c.textSecondary, modifier = Modifier.widthIn(max = 720.dp).padding(bottom = 20.dp),
            )
        }
        item {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(22.dp)) {
                Text("Criar uma jam", style = AuraType.title, color = c.textPrimary)
                Field(name, { name = it.take(60) }, "Nome da jam (opcional)", Modifier.padding(vertical = 12.dp))
                ToggleLine("Todos podem controlar (modo democrático)", democratic) { democratic = it }
                ToggleLine("Aprovar quem entra", approve) { approve = it }
                PillButton("Criar e convidar", {
                    val ok = together.startHosting(DesktopTogether.HostOptions(name.ifBlank { "Jam de $defaultName" }, defaultName, democratic, approve))
                    if (!ok) error = "Não foi possível abrir a jam neste computador."
                }, AuraIcons.Together, modifier = Modifier.padding(top = 14.dp))
            }
        }
        item {
            Column(Modifier.padding(top = 18.dp).widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(22.dp)) {
                Text("Entrar numa jam", style = AuraType.title, color = c.textPrimary)
                Field(link, { link = it }, "Cole aqui o link do convite", Modifier.padding(vertical = 12.dp))
                ToggleLine("Receber cópia temporária das músicas que faltam neste computador", receiveMissing) { receiveMissing = it }
                Text(
                    "Desligado: só tocam as músicas que você tem; as que faltam aparecem como faltando. Ligado: o anfitrião envia uma cópia que some quando a jam acaba.",
                    style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(top = 4.dp),
                )
                PillButton("Entrar", {
                    error = if (together.join(link, defaultName, receiveMissing)) null else "Esse link não é um convite do AURA Together."
                }, AuraIcons.Play, modifier = Modifier.padding(top = 14.dp))
            }
        }
        error?.let { item { Text(it, style = AuraType.body, color = c.danger, modifier = Modifier.padding(top = 12.dp)) } }
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AuraType.body, color = Aura.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked, onChange, label)
    }
}

@Composable
private fun HostingJam(model: AppModel, together: DesktopTogether, hosting: JamRole.Hosting) {
    val c = Aura.colors
    val clipboard = LocalClipboardManager.current
    val requests by together.requests.collectAsState()
    val state = hosting.state
    LazyColumn(contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(state?.sessionName ?: "Jam", style = AuraType.title, color = c.textPrimary)
            Text(
                listOfNotNull(
                    hosting.address?.let { "Rede local: $it" },
                    if (!together.relayEnabled) null else when (hosting.relay) {
                        RelayStatus.Online -> "Internet: disponível"
                        RelayStatus.Connecting -> "Internet: conectando"
                        RelayStatus.Offline -> "Internet: indisponível (só rede local)"
                    },
                    state?.let { if (it.mode == ControlMode.Democratic) "Todos controlam" else "Só o anfitrião controla" },
                ).joinToString(" · "),
                style = AuraType.small, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Copiar link do convite", {
                    clipboard.setText(AnnotatedString(hosting.link))
                    model.player.notify("Link copiado. Envie para quem vai participar.")
                }, AuraIcons.Plus)
                PillButton("Cancelar links já enviados", {
                    together.revokeInvites()
                    model.player.notify("Links antigos cancelados; quem já está na jam continua.")
                }, primary = false)
                PillButton("Encerrar jam", together::leave, primary = false)
            }
        }
        if (requests.isNotEmpty()) {
            item { Text("PEDINDO PARA ENTRAR", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)) }
            items(requests, key = { it.hashCode() }) { req ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceElevated).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(req.name, style = AuraType.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
                    PillButton("Recusar", { together.answer(req, false) }, primary = false)
                    PillButton("Aceitar", { together.answer(req, true) }, AuraIcons.Check, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        item { Text("PARTICIPANTES", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)) }
        items(state?.participants.orEmpty(), key = { it.id }) { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF7BD88F)))
                Text(p.name + if (p.isHost) " (você)" else "", style = AuraType.bodyStrong, color = c.textPrimary, modifier = Modifier.weight(1f).padding(start = 10.dp))
                if (!p.isHost) {
                    Text("Pode controlar", style = AuraType.small, color = c.textSecondary, modifier = Modifier.padding(end = 8.dp))
                    Switch(p.canControl, { together.setCanControl(p.id, it) }, "Permitir que ${p.name} controle")
                    AuraIconButton(AuraIcons.Close, "Remover ${p.name} da jam", { together.kick(p.id) })
                }
            }
        }
        state?.let { item { QueueOfJam(it) } }
    }
}

@Composable
private fun QueueOfJam(state: SessionState) {
    val c = Aura.colors
    Text("FILA DA JAM", style = AuraType.label, color = c.textTertiary, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    state.queue.forEachIndexed { i, t ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${i + 1}. ${t.title}" + (t.artist?.let { " — $it" } ?: ""),
                style = if (i == state.index) AuraType.bodyStrong else AuraType.body,
                color = if (i == state.index) c.accent else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(formatDuration(t.durationMs), style = AuraType.small, color = c.textTertiary)
        }
    }
}

@Composable
private fun GuestJam(model: AppModel, together: DesktopTogether, guest: JamRole.Guest) {
    val c = Aura.colors
    val ui = guest.ui
    val ambiguous by together.ambiguous.collectAsState()
    LazyColumn(contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(guest.invite.sessionName.ifBlank { "Jam" }, style = AuraType.title, color = c.textPrimary)
            Text(
                when (ui.status) {
                    GuestStatus.Connecting -> "Conectando a ${guest.invite.hostName}…"
                    GuestStatus.WaitingApproval -> "Esperando ${guest.invite.hostName} aceitar sua entrada…"
                    GuestStatus.Connected -> "Na jam de ${guest.invite.hostName} · ida e volta ${ui.rttMs} ms · desvio médio ${ui.averageDriftMs} ms"
                    GuestStatus.Reconnecting -> "Reconectando… a música deste computador segue normal"
                    GuestStatus.Ended -> when (ui.endReason) {
                        RejectReason.Declined -> "O anfitrião recusou a entrada."
                        RejectReason.Full -> "A jam está cheia."
                        RejectReason.Kicked -> "Você foi removido da jam."
                        RejectReason.BadVersion -> "O AURA do anfitrião é de outra versão. Atualizem os dois."
                        RejectReason.InviteRevoked -> "Esse convite foi cancelado. Peça um link novo."
                        else -> "A jam terminou."
                    }
                },
                style = AuraType.body, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            PillButton(if (ui.status == GuestStatus.Ended) "Fechar" else "Sair da jam", together::leave, primary = ui.status == GuestStatus.Ended)
        }
        val state = ui.state
        if (ui.status == GuestStatus.Connected && state != null) {
            val me = state.participants.firstOrNull { it.id == ui.participantId }
            item {
                Text(
                    if (me?.canControl == true) "Você pode tocar, pausar, pular e pôr músicas suas na fila pelo player e pelo menu das músicas."
                    else "Nesta jam, só o anfitrião controla a música.",
                    style = AuraType.small, color = c.textTertiary, modifier = Modifier.padding(top = 14.dp),
                )
            }
            if (ui.missingTrack) {
                item {
                    Text(
                        if (ui.receiving != null) "Recebendo “${state.current?.title}” do anfitrião (${((ui.receiving ?: 0f) * 100).toInt()} %)…"
                        else "“${state.current?.title}” não está neste computador. As outras pessoas continuam ouvindo; aqui ela fica em silêncio.",
                        style = AuraType.bodyStrong, color = c.danger, modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
            ambiguous?.let { (ref, candidates) ->
                item {
                    Column(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceElevated).padding(16.dp)) {
                        Text("Qual destas é “${ref.title}”?", style = AuraType.bodyStrong, color = c.textPrimary)
                        candidates.take(5).forEach { t ->
                            Text(
                                "${t.title} — ${t.artist ?: "?"} · ${t.album ?: "sem álbum"} · ${formatDuration(t.durationMs)}",
                                style = AuraType.body, color = c.accent,
                                modifier = Modifier.padding(top = 6.dp).clickable { together.chooseMatch(ref, t) }.pointerHoverIcon(PointerIcon.Hand),
                            )
                        }
                    }
                }
            }
            item { QueueOfJam(state) }
        }
    }
}

/** Join requests while hosting, shown over any screen so nobody waits unnoticed. */
@Composable
fun JamRequestsOverlay(model: AppModel) {
    val together = model.together ?: return
    val requests by together.requests.collectAsState()
    val request = requests.firstOrNull() ?: return
    if (model.screen == Screen.Together) return
    val c = Aura.colors
    Box(Modifier.fillMaxSize().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(c.surfaceElevated).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("“${request.name}” quer entrar na jam", style = AuraType.bodyStrong, color = c.textPrimary, modifier = Modifier.padding(end = 14.dp))
            PillButton("Recusar", { together.answer(request, false) }, primary = false)
            PillButton("Aceitar", { together.answer(request, true) }, AuraIcons.Check, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
