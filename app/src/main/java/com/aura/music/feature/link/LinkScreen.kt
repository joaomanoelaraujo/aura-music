package com.aura.music.feature.link

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraTopBar
import com.aura.music.core.designsystem.components.ButtonStyle
import com.aura.music.core.designsystem.components.ConfirmDialog
import com.aura.music.core.designsystem.components.PageTitle
import com.aura.music.core.designsystem.components.SectionHeader
import com.aura.music.core.designsystem.components.TextInputDialog
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.feature.settings.SwitchRow
import com.aura.music.link.DiscoveredComputer
import com.aura.music.link.LinkController
import com.aura.music.link.LinkedComputer
import com.aura.music.link.PairingStage
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalBottomInset
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LinkViewModel @Inject constructor(val link: LinkController) : ViewModel() {
    init {
        link.start()
    }
}

private enum class PairMode { None, Qr, Code }

/** Settings → Dispositivos: pair with a computer and manage linked computers. */
@Composable
fun LinkScreen(viewModel: LinkViewModel = hiltViewModel()) {
    val link = viewModel.link
    val state by link.state.collectAsStateWithLifecycle()
    val found by link.discovery.found.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val colors = AuraTheme.colors
    var mode by rememberSaveable { mutableStateOf(PairMode.None) }
    var renaming by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var removing by remember { mutableStateOf<LinkedComputer?>(null) }
    DisposableEffect(Unit) {
        link.screenVisible(true)
        onDispose { link.screenVisible(false) }
    }

    // imePadding: the code fields stay above the keyboard while typing.
    Column(Modifier.fillMaxSize().imePadding()) {
        AuraTopBar(title = null, onBack = { navigator.back() })
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x8)) {
            item { PageTitle(stringResource(R.string.link_title)) }
            item {
                Text(
                    stringResource(R.string.link_intro), style = AuraTheme.type.body, color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
                )
            }
            item {
                Row(
                    Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x3).fillMaxWidth().clip(Radius.medium).background(colors.surfaceElevated).padding(Spacing.x4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(state.deviceName, style = AuraTheme.type.title, color = colors.textPrimary)
                        Text(stringResource(R.string.link_this_phone, state.fingerprint), style = AuraTheme.type.bodySmall, color = colors.textSecondary)
                    }
                    AuraIconButton(AuraIcons.Edit, stringResource(R.string.link_rename), { renaming = null to state.deviceName })
                }
            }
            item { SectionHeader(stringResource(R.string.link_pair_computer)) }
            item {
                Row(Modifier.padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                    AuraButton(stringResource(R.string.link_scan_qr), { mode = if (mode == PairMode.Qr) PairMode.None else PairMode.Qr }, icon = AuraIcons.Image, style = if (mode == PairMode.Qr) ButtonStyle.Primary else ButtonStyle.Secondary)
                    AuraButton(stringResource(R.string.link_type_code), { mode = if (mode == PairMode.Code) PairMode.None else PairMode.Code }, icon = AuraIcons.Edit, style = if (mode == PairMode.Code) ButtonStyle.Primary else ButtonStyle.Secondary)
                }
            }
            if (mode == PairMode.Qr) {
                item {
                    var invalid by remember { mutableStateOf(false) }
                    Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x3)) {
                        Text(stringResource(R.string.link_scan_hint), style = AuraTheme.type.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(bottom = Spacing.x3))
                        QrScanner(
                            onResult = { text ->
                                if (link.pairWithQr(text)) mode = PairMode.None else invalid = true
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                        if (invalid) Text(stringResource(R.string.link_not_aura_code), style = AuraTheme.type.bodySmall, color = colors.danger, modifier = Modifier.padding(top = Spacing.x2))
                    }
                }
            }
            if (mode == PairMode.Code) {
                item { CodeEntry(found.values.filter { it.pairing }.sortedBy { it.name }) { host, port, code -> link.pairWithCode(host, port, code); mode = PairMode.None } }
            }
            state.message?.let { message ->
                item {
                    Row(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2).clip(Radius.small).background(colors.surfaceElevated).padding(Spacing.x3), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, style = AuraTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        AuraIconButton(AuraIcons.Close, stringResource(R.string.link_ok), link::dismissMessage)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.link_linked_devices)) }
            if (state.computers.isEmpty()) {
                item { Text(stringResource(R.string.link_none), style = AuraTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = Spacing.gutter)) }
            }
            item { TransfersSection(link) }
            items(state.computers, key = { it.device.deviceId }) { computer ->
                ComputerCard(
                    computer = computer,
                    onControl = { navigator.openLinkRemote(computer.device.deviceId) },
                    onRename = { renaming = computer.device.deviceId to computer.device.name },
                    onPermissions = { link.setPermissions(computer.device.deviceId, it) },
                    onPause = { link.pause(computer.device.deviceId) },
                    onResume = { link.resume(computer.device.deviceId) },
                    onRemove = { removing = computer },
                )
            }
        }
    }

    PairingDialogs(state.pairing, onAnswer = link::answerOffer, onClose = link::clearPairing)
    IncomingTransferDialog(link)

    renaming?.let { (id, current) ->
        TextInputDialog(
            title = stringResource(if (id == null) R.string.link_rename_this_title else R.string.link_rename_device_title),
            initialValue = current,
            placeholder = stringResource(R.string.link_name_placeholder),
            confirmLabel = stringResource(R.string.link_save),
            dismissLabel = stringResource(R.string.link_cancel),
            onConfirm = { name ->
                if (id == null) link.renameThisPhone(name) else link.rename(id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    removing?.let { computer ->
        ConfirmDialog(
            title = stringResource(R.string.link_remove_title, computer.device.name),
            message = stringResource(R.string.link_remove_body),
            confirmLabel = stringResource(R.string.link_remove),
            dismissLabel = stringResource(R.string.link_cancel),
            onConfirm = {
                link.revoke(computer.device.deviceId)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun TransfersSection(link: LinkController) {
    val colors = AuraTheme.colors
    val jobs by link.transfers.jobs.collectAsStateWithLifecycle()
    if (jobs.isEmpty()) return
    Column {
        SectionHeader(stringResource(R.string.link_transfers), actionLabel = stringResource(R.string.link_transfers_clear).takeIf { jobs.any { !it.active } }, onAction = link.transfers::clearFinished)
        jobs.forEach { job ->
            val what = if (job.offer.files.size == 1) job.offer.files.first().title ?: job.offer.files.first().name
            else stringResource(R.string.link_transfer_songs, job.offer.files.size)
            Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x1).fillMaxWidth().clip(Radius.medium).background(colors.surfaceElevated).padding(Spacing.x4)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (job.outgoing) R.string.link_transfer_to else R.string.link_transfer_from, job.peerName, what),
                            style = AuraTheme.type.body, color = colors.textPrimary, maxLines = 1,
                        )
                        Text(
                            listOfNotNull(
                                stringResource(
                                    when (job.status) {
                                        com.aura.music.link.PhoneTransferStatus.Preparing -> R.string.link_transfer_preparing
                                        com.aura.music.link.PhoneTransferStatus.WaitingAnswer -> R.string.link_transfer_waiting
                                        com.aura.music.link.PhoneTransferStatus.Running -> if (job.outgoing) R.string.link_transfer_sending else R.string.link_transfer_receiving
                                        com.aura.music.link.PhoneTransferStatus.Done -> if (job.outgoing) R.string.link_transfer_sent else R.string.link_transfer_received
                                        com.aura.music.link.PhoneTransferStatus.Failed -> R.string.link_transfer_failed
                                        com.aura.music.link.PhoneTransferStatus.Cancelled -> R.string.link_transfer_cancelled
                                        com.aura.music.link.PhoneTransferStatus.Refused -> R.string.link_transfer_refused
                                    },
                                ),
                                "${com.aura.music.core.common.formatFileSize(job.doneBytes)} / ${com.aura.music.core.common.formatFileSize(job.offer.totalBytes)}",
                                job.error,
                            ).joinToString(" · "),
                            style = AuraTheme.type.bodySmall,
                            color = if (job.status == com.aura.music.link.PhoneTransferStatus.Failed) colors.danger else colors.textSecondary,
                        )
                    }
                    if (job.active) AuraIconButton(AuraIcons.Close, stringResource(R.string.link_transfer_cancel), { link.transfers.cancel(job.id) })
                    if (job.outgoing && job.status == com.aura.music.link.PhoneTransferStatus.Failed) {
                        AuraIconButton(AuraIcons.Refresh, stringResource(R.string.link_transfer_retry), { link.transfers.retry(job.id) })
                    }
                }
                if (job.active && job.offer.totalBytes > 0) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (job.doneBytes.toFloat() / job.offer.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.x2),
                        color = colors.accent,
                        trackColor = colors.surface,
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomingTransferDialog(link: LinkController) {
    val colors = AuraTheme.colors
    val incoming by link.transfers.incoming.collectAsStateWithLifecycle()
    val offer = incoming ?: return
    var always by remember(offer.offer.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        containerColor = colors.surfaceElevated,
        title = { Text(stringResource(R.string.link_incoming_title, offer.peerName), style = AuraTheme.type.title, color = colors.textPrimary) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.link_incoming_body,
                        stringResource(R.string.link_transfer_songs, offer.offer.files.size),
                        com.aura.music.core.common.formatFileSize(offer.offer.totalBytes),
                    ),
                    style = AuraTheme.type.body, color = colors.textSecondary,
                )
                offer.offer.files.take(5).forEach { f ->
                    Text("• " + listOfNotNull(f.title ?: f.name, f.artist).joinToString(" — "), style = AuraTheme.type.bodySmall, color = colors.textSecondary, maxLines = 1)
                }
                SwitchRow(stringResource(R.string.link_incoming_always), null, always, { always = it })
            }
        },
        confirmButton = { AuraButton(stringResource(R.string.link_incoming_accept), { link.transfers.answerIncoming(true, always) }) },
        dismissButton = { AuraButton(stringResource(R.string.link_incoming_decline), { link.transfers.answerIncoming(false, false) }, style = ButtonStyle.Secondary) },
    )
}

@Composable
private fun CodeEntry(computers: List<DiscoveredComputer>, onPair: (String, Int, String) -> Unit) {
    val colors = AuraTheme.colors
    var selected by remember { mutableStateOf<DiscoveredComputer?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x3)) {
        Text(stringResource(R.string.link_computers_waiting), style = AuraTheme.type.label, color = colors.textSecondary)
        if (computers.isEmpty()) {
            Text(stringResource(R.string.link_no_computers_found), style = AuraTheme.type.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(vertical = Spacing.x2))
        }
        computers.forEach { c ->
            val isSelected = selected?.serviceName == c.serviceName
            Row(
                Modifier.fillMaxWidth().padding(vertical = Spacing.x1).clip(Radius.small)
                    .background(if (isSelected) colors.accent.copy(alpha = 0.16f) else colors.surfaceElevated)
                    .clickable { selected = c; address = "" }.padding(Spacing.x3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.name, style = AuraTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Text(c.host, style = AuraTheme.type.bodySmall, color = colors.textTertiary)
            }
        }
        Text(stringResource(R.string.link_manual_address), style = AuraTheme.type.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(top = Spacing.x3))
        Field(address, { address = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ':' }; selected = null }, stringResource(R.string.link_address_placeholder), KeyboardType.Uri)
        Field(code, { code = it.filter(Char::isDigit).take(8) }, stringResource(R.string.link_code_placeholder), KeyboardType.Number)
        val target = selected?.let { it.host to it.port } ?: address.takeIf { it.isNotBlank() }?.let {
            it.substringBefore(':') to (it.substringAfter(':', "").toIntOrNull() ?: LinkController.DEFAULT_PORT)
        }
        AuraButton(
            stringResource(R.string.link_connect),
            { target?.let { (h, p) -> onPair(h, p, code) } },
            Modifier.padding(top = Spacing.x3),
            enabled = target != null && code.length == 8,
        )
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, keyboard: KeyboardType) {
    val colors = AuraTheme.colors
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = AuraTheme.type.body.copy(color = colors.textPrimary, fontSize = 18.sp),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.x2),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(Radius.small).background(colors.surfaceElevated).padding(Spacing.x3)) {
                if (value.isEmpty()) Text(placeholder, style = AuraTheme.type.body, color = colors.textTertiary)
                inner()
            }
        },
    )
}

@Composable
private fun ComputerCard(
    computer: LinkedComputer,
    onControl: () -> Unit,
    onRename: () -> Unit,
    onPermissions: (com.aura.music.link.LinkPermissions) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = AuraTheme.colors
    var expanded by rememberSaveable(computer.device.deviceId) { mutableStateOf(false) }
    val d = computer.device
    Column(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x1).fillMaxWidth().clip(Radius.medium).background(colors.surfaceElevated).padding(Spacing.x4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (computer.connected) Color(0xFF7BD88F) else if (computer.paused) colors.danger else colors.textTertiary))
            Column(Modifier.weight(1f).padding(horizontal = Spacing.x3)) {
                Text(d.name, style = AuraTheme.type.title, color = colors.textPrimary)
                Text(
                    stringResource(
                        when {
                            computer.connected -> R.string.link_status_connected
                            computer.connecting -> R.string.link_status_connecting
                            computer.paused -> R.string.link_status_paused
                            else -> R.string.link_status_offline
                        },
                    ),
                    style = AuraTheme.type.bodySmall, color = colors.textSecondary,
                )
            }
            AuraIconButton(if (expanded) AuraIcons.ChevronDown else AuraIcons.More, stringResource(R.string.link_permissions_title, d.name), { expanded = !expanded })
        }
        computer.remoteState?.current?.let { t ->
            Text(
                stringResource(R.string.link_playing_on, d.name) + " · " + t.title,
                style = AuraTheme.type.bodySmall, color = colors.accent, maxLines = 1, modifier = Modifier.padding(top = Spacing.x2),
            )
        }
        if (computer.connected && computer.granted?.controlPlayback == true) {
            AuraButton(stringResource(R.string.link_control), onControl, Modifier.padding(top = Spacing.x3), icon = AuraIcons.Play)
        }
        if (expanded) {
            Text(stringResource(R.string.link_permissions_title, d.name), style = AuraTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(top = Spacing.x4))
            SwitchRow(stringResource(R.string.link_perm_control), stringResource(R.string.link_perm_control_body), d.permissions.controlPlayback, { onPermissions(d.permissions.copy(controlPlayback = it)) })
            SwitchRow(stringResource(R.string.link_perm_library), stringResource(R.string.link_perm_library_body), d.permissions.browseLibrary, { onPermissions(d.permissions.copy(browseLibrary = it)) })
            Text(stringResource(R.string.link_identity, d.fingerprint), style = AuraTheme.type.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = Spacing.x2))
            Row(Modifier.padding(top = Spacing.x3), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                AuraButton(stringResource(R.string.link_rename), onRename, style = ButtonStyle.Secondary)
                if (computer.paused) AuraButton(stringResource(R.string.link_resume), onResume, style = ButtonStyle.Secondary)
                else AuraButton(stringResource(R.string.link_pause), onPause, style = ButtonStyle.Secondary)
            }
            AuraButton(stringResource(R.string.link_remove), onRemove, Modifier.padding(top = Spacing.x2), icon = AuraIcons.Trash, style = ButtonStyle.Secondary)
        }
    }
}

@Composable
private fun PairingDialogs(stage: PairingStage?, onAnswer: (Boolean) -> Unit, onClose: () -> Unit) {
    val colors = AuraTheme.colors
    when (stage) {
        null -> Unit
        PairingStage.Connecting, PairingStage.WaitingForComputer -> AlertDialog(
            onDismissRequest = {},
            containerColor = colors.surfaceElevated,
            confirmButton = {},
            text = {
                Text(
                    stringResource(if (stage == PairingStage.Connecting) R.string.link_connecting else R.string.link_waiting_computer),
                    style = AuraTheme.type.body, color = colors.textPrimary,
                )
            },
        )
        is PairingStage.Confirm -> AlertDialog(
            onDismissRequest = {},
            containerColor = colors.surfaceElevated,
            title = { Text(stringResource(R.string.link_confirm_title, stage.offer.name), style = AuraTheme.type.title, color = colors.textPrimary) },
            text = {
                Column {
                    Text(stringResource(R.string.link_confirm_compare), style = AuraTheme.type.body, color = colors.textSecondary)
                    Text(
                        stage.offer.verificationCode.chunked(3).joinToString(" "),
                        style = AuraTheme.type.display.copy(letterSpacing = 6.sp), color = colors.accent,
                        modifier = Modifier.padding(vertical = Spacing.x3),
                    )
                    Text(stringResource(R.string.link_confirm_warning), style = AuraTheme.type.bodySmall, color = colors.textTertiary)
                }
            },
            confirmButton = { AuraButton(stringResource(R.string.link_accept), { onAnswer(true) }) },
            dismissButton = { AuraButton(stringResource(R.string.link_decline), { onAnswer(false) }, style = ButtonStyle.Secondary) },
        )
        is PairingStage.Done -> AlertDialog(
            onDismissRequest = onClose,
            containerColor = colors.surfaceElevated,
            text = { Text(stringResource(R.string.link_paired, stage.name), style = AuraTheme.type.body, color = colors.textPrimary) },
            confirmButton = { AuraButton(stringResource(R.string.link_ok), onClose) },
        )
        is PairingStage.Failed -> AlertDialog(
            onDismissRequest = onClose,
            containerColor = colors.surfaceElevated,
            text = { Text(stage.message, style = AuraTheme.type.body, color = colors.textPrimary) },
            confirmButton = { AuraButton(stringResource(R.string.link_ok), onClose) },
        )
    }
}
