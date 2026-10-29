package com.aura.music.feature.together

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.LocalGradientIntensity
import com.aura.music.core.designsystem.atmosphere.atmosphereBackground
import com.aura.music.core.designsystem.atmosphere.rememberAtmosphere
import com.aura.music.core.designsystem.components.AdaptiveCover
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.AuraChip
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraSeekBar
import com.aura.music.core.designsystem.components.AuraSheet
import com.aura.music.core.designsystem.components.AuraTopBar
import com.aura.music.core.designsystem.components.ButtonStyle
import com.aura.music.core.designsystem.components.PageTitle
import com.aura.music.core.designsystem.components.PlayPauseButton
import com.aura.music.core.designsystem.components.SearchField
import com.aura.music.core.designsystem.components.SectionHeader
import com.aura.music.core.designsystem.components.SheetHeader
import com.aura.music.core.designsystem.components.TrackRow
import com.aura.music.core.designsystem.components.artistLabel
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraDarkSurface
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.navigation.LocalNavigator
import com.aura.music.playback.controller.PlaybackPosition
import com.aura.music.together.StartResult
import com.aura.music.together.TogetherRole
import com.aura.music.together.guest.GuestStatus
import com.aura.music.together.protocol.Command
import com.aura.music.together.protocol.ControlMode
import com.aura.music.together.protocol.IncomingTrack
import com.aura.music.together.protocol.Participant
import com.aura.music.together.protocol.Reactions
import com.aura.music.together.protocol.RejectReason
import com.aura.music.together.protocol.SessionState
import com.aura.music.together.transport.RelayStatus
import com.aura.music.ui.LocalBottomInset
import com.aura.music.ui.LocalMessenger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TogetherScreen(viewModel: TogetherViewModel = hiltViewModel()) {
    val role by viewModel.role.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    when (val r = role) {
        TogetherRole.Idle -> Column(Modifier.fillMaxSize()) {
            AuraTopBar(title = null, onBack = { navigator.back() })
            IdleContent(viewModel)
        }
        is TogetherRole.Hosting -> SessionContent(viewModel, r.state, isHost = true, hosting = r, guest = null, onBack = { navigator.back() })
        is TogetherRole.Guest -> SessionContent(viewModel, r.ui.state, isHost = false, hosting = null, guest = r, onBack = { navigator.back() })
    }
}

@Composable
private fun IdleContent(viewModel: TogetherViewModel) {
    val savedName by viewModel.displayName.collectAsStateWithLifecycle()
    var sessionName by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable(savedName) { mutableStateOf(savedName) }
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x8)) {
        item { PageTitle(stringResource(R.string.together_title), eyebrow = stringResource(R.string.together_eyebrow)) }
        item {
            Text(
                stringResource(R.string.together_intro),
                style = AuraTheme.type.body,
                color = AuraTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
            )
        }
        item { SectionHeader(stringResource(R.string.together_create)) }
        item { Field(name, { name = it }, stringResource(R.string.together_your_name)) }
        item { Field(sessionName, { sessionName = it }, stringResource(R.string.together_session_name)) }
        item {
            AuraButton(
                stringResource(R.string.together_start),
                onClick = {
                    if (name != savedName) viewModel.setDisplayName(name)
                    viewModel.create(sessionName) { result ->
                        when (result) {
                            StartResult.Started -> Unit
                            StartResult.Failed -> messenger.show(resources.getString(R.string.together_start_failed))
                        }
                    }
                },
                icon = AuraIcons.Together,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x3).fillMaxWidth(),
            )
        }
        item {
            Text(
                stringResource(R.string.together_network_note),
                style = AuraTheme.type.bodySmall,
                color = AuraTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = Spacing.gutter),
            )
        }
        item { SectionHeader(stringResource(R.string.together_join)) }
        item { Field(link, { link = it }, stringResource(R.string.together_paste_hint)) }
        item {
            Row(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x3), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
                AuraButton(stringResource(R.string.discover_paste_link), onClick = {
                    scope.launch {
                        clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let { link = it }
                    }
                }, style = ButtonStyle.Secondary)
                AuraButton(stringResource(R.string.together_join_action), onClick = {
                    if (name != savedName) viewModel.setDisplayName(name)
                    if (!viewModel.joinFromText(link)) messenger.show(resources.getString(R.string.together_invalid_link))
                })
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String) {
    Box(
        Modifier
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x1)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(Radius.medium)
            .background(AuraTheme.colors.surface)
            .border(1.dp, AuraTheme.colors.outline, Radius.medium)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x3),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, style = AuraTheme.type.body, color = AuraTheme.colors.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = AuraTheme.type.body.copy(color = AuraTheme.colors.textPrimary),
            cursorBrush = SolidColor(AuraTheme.colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SessionContent(
    viewModel: TogetherViewModel,
    state: SessionState?,
    isHost: Boolean,
    hosting: TogetherRole.Hosting?,
    guest: TogetherRole.Guest?,
    onBack: () -> Unit,
) {
    val player by viewModel.playerState.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle(PlaybackPosition())
    val ambiguous by viewModel.ambiguous.collectAsStateWithLifecycle()
    val track = player.current
    val atmosphere = rememberAtmosphere(track?.artwork)
    val me = guest?.ui?.participantId
    val canControl = isHost || state?.participants?.firstOrNull { it.id == me }?.canControl == true
    var adding by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

    AuraDarkSurface {
        Box(Modifier.fillMaxSize().atmosphereBackground(atmosphere, glowCenterFraction = 0.18f, glowStrength = LocalGradientIntensity.current)) {
            ReactionOverlay(viewModel)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x8)) {
                item {
                    AuraTopBar(title = state?.sessionName ?: guest?.invite?.sessionName, onBack = onBack, actions = {
                        AuraIconButton(AuraIcons.Close, stringResource(if (isHost) R.string.together_end else R.string.together_leave), { confirmEnd = true })
                    })
                }
                item { ConnectionLine(isHost, hosting, guest) }
                if (isHost && hosting != null) item { InviteActions(hosting.link) }
                if (guest != null && guest.ui.status != GuestStatus.Connected) item { GuestStatusCard(guest) }

                if (state != null && state.current != null) {
                    item {
                        Box(Modifier.padding(horizontal = 48.dp, vertical = Spacing.x4).fillMaxWidth().aspectRatio(1f)) {
                            if (track != null && guest?.ui?.missingTrack != true) {
                                AdaptiveCover(source = track.artwork, slot = 300.dp, tint = atmosphere.glow, expansion = 1f, shape = Radius.xLarge, placeholderSeed = track.album, modifier = Modifier.fillMaxSize())
                            } else {
                                Box(Modifier.fillMaxSize().clip(Radius.xLarge).background(atmosphere.glow.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                                    MissingTrackNotice(guest?.ui?.streamFailed == true, guest?.ui?.receiving)
                                }
                            }
                        }
                    }
                    item {
                        val ref = state.current!!
                        Column(Modifier.padding(horizontal = Spacing.gutter)) {
                            Text(ref.title, style = AuraTheme.type.display, color = AuraTheme.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(artistLabel(ref.artist), style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary)
                            AuraSeekBar(
                                positionMs = position.positionMs,
                                durationMs = position.durationMs.takeIf { it > 0 } ?: ref.durationMs,
                                onSeek = { if (canControl) viewModel.command(Command.Seek(it)) },
                                accent = atmosphere.accent,
                            )
                            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.x2), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                AuraIconButton(AuraIcons.Previous, stringResource(R.string.cd_previous), { viewModel.command(Command.Previous) }, enabled = canControl, iconSize = 28.dp)
                                PlayPauseButton(
                                    isPlaying = state.isPlaying,
                                    onClick = { if (canControl) viewModel.command(if (state.isPlaying) Command.Pause else Command.Play) },
                                    size = 68.dp,
                                    container = if (canControl) atmosphere.accent else atmosphere.accent.copy(alpha = 0.35f),
                                    content = atmosphere.onAccent,
                                )
                                AuraIconButton(AuraIcons.Next, stringResource(R.string.cd_next), { viewModel.command(Command.Next) }, enabled = canControl, iconSize = 28.dp)
                            }
                            if (!canControl) Text(stringResource(R.string.together_view_only), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textTertiary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            ReactionBar(viewModel::react)
                        }
                    }
                } else if (state != null) {
                    item {
                        Text(
                            stringResource(if (isHost) R.string.together_empty_host else R.string.together_empty_guest),
                            style = AuraTheme.type.body,
                            color = AuraTheme.colors.textSecondary,
                            modifier = Modifier.padding(Spacing.gutter),
                        )
                    }
                }

                ambiguous?.let { (ref, candidates) ->
                    item { AmbiguityCard(candidates.size) }
                    item {
                        Column(Modifier.padding(horizontal = Spacing.gutter)) {
                            candidates.forEach { candidate ->
                                AuraChip(candidate.folder + candidate.displayName, selected = false, onClick = { viewModel.chooseMatch(ref, candidate) }, modifier = Modifier.padding(vertical = Spacing.x1))
                            }
                        }
                    }
                }

                if (state != null) {
                    item { SectionHeader(stringResource(R.string.together_participants, state.participants.size, state.maxParticipants)) }
                    if (isHost) item { ModeRow(state.mode, viewModel::setMode) }
                    items(state.participants, key = { "p${it.id}" }) { p -> ParticipantRow(p, isHost, me, viewModel) }

                    item {
                        SectionHeader(
                            stringResource(R.string.player_queue),
                            actionLabel = if (canControl) stringResource(R.string.queue_add) else null,
                            onAction = if (canControl) ({ adding = true }) else null,
                        )
                    }
                    itemsIndexed(state.queue, key = { i, _ -> "q$i" }) { index, ref ->
                        QueueRow(index, ref.title, artistLabel(ref.artist), index == state.index, canControl, state.queue.size, viewModel)
                    }
                    itemsIndexed(state.incoming, key = { i, _ -> "in$i" }) { _, incoming -> IncomingRow(incoming) }
                }
            }
        }
    }

    if (adding) AddSheet(viewModel, onDismiss = { adding = false })
    if (confirmEnd) {
        com.aura.music.core.designsystem.components.ConfirmDialog(
            title = stringResource(if (isHost) R.string.together_end else R.string.together_leave),
            message = stringResource(if (isHost) R.string.together_end_confirm else R.string.together_leave_confirm),
            confirmLabel = stringResource(if (isHost) R.string.together_end else R.string.together_leave),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmEnd = false
                viewModel.leave()
            },
            onDismiss = { confirmEnd = false },
        )
    }
}

/** The current song isn't on this device: it's being received from the host, or couldn't be. */
@Composable
private fun MissingTrackNotice(failed: Boolean, progress: Float?) {
    if (failed) {
        Text(stringResource(R.string.together_missing_file), style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(Spacing.x6))
        return
    }
    Column(Modifier.padding(Spacing.x6), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.together_receiving), style = AuraTheme.type.body, color = AuraTheme.colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.x4))
        val bar = Modifier.fillMaxWidth().clip(CircleShape)
        if (progress == null) {
            LinearProgressIndicator(bar, color = AuraTheme.colors.accent, trackColor = AuraTheme.colors.surfaceElevated)
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = bar, color = AuraTheme.colors.accent, trackColor = AuraTheme.colors.surfaceElevated, drawStopIndicator = {})
        }
        Spacer(Modifier.height(Spacing.x3))
        Text(stringResource(R.string.together_receiving_note), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun hostLine(hosting: TogetherRole.Hosting?): Pair<String, Color> {
    val address = hosting?.address
    val online = hosting?.relay == RelayStatus.Online
    return when {
        address != null && online -> stringResource(R.string.together_live_both, address) to AuraTheme.colors.accent
        address != null -> stringResource(R.string.together_live_lan_only, address) to AuraTheme.colors.accent
        online -> stringResource(R.string.together_live_internet) to AuraTheme.colors.accent
        else -> stringResource(R.string.together_relay_offline) to AuraTheme.colors.danger
    }
}

@Composable
private fun ConnectionLine(isHost: Boolean, hosting: TogetherRole.Hosting?, guest: TogetherRole.Guest?) {
    val (label, color) = when {
        isHost -> hostLine(hosting)
        guest?.ui?.status == GuestStatus.Connected -> {
            val ui = guest.ui
            stringResource(R.string.together_sync_info, ui.averageDriftMs, ui.rttMs) to AuraTheme.colors.accent
        }
        guest?.ui?.status == GuestStatus.Reconnecting -> stringResource(R.string.together_reconnecting) to AuraTheme.colors.danger
        else -> stringResource(R.string.together_connecting) to AuraTheme.colors.textSecondary
    }
    Column(Modifier.padding(horizontal = Spacing.gutter)) {
        guest?.invite?.hostName?.let {
            Text(stringResource(R.string.together_hosted_by, it), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(Spacing.x2))
            Text(label, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun InviteActions(link: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shareText = stringResource(R.string.together_share_text, link)
    Row(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x3), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        AuraButton(stringResource(R.string.together_invite), onClick = {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
            context.startActivity(Intent.createChooser(send, null))
        }, icon = AuraIcons.Plus)
        AuraButton(stringResource(R.string.together_copy_link), onClick = {
            scope.launch {
                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("AURA", link)))
                messenger.show(resources.getString(R.string.together_link_copied))
            }
        }, style = ButtonStyle.Secondary)
    }
}

@Composable
private fun GuestStatusCard(guest: TogetherRole.Guest) {
    val text = when (guest.ui.status) {
        GuestStatus.Connecting -> stringResource(R.string.together_connecting_to, guest.invite.hostName)
        GuestStatus.WaitingApproval -> stringResource(R.string.together_waiting_approval, guest.invite.hostName)
        GuestStatus.Reconnecting -> stringResource(R.string.together_reconnecting_body)
        GuestStatus.Ended -> stringResource(
            when (guest.ui.endReason) {
                RejectReason.Declined -> R.string.together_end_declined
                RejectReason.Full -> R.string.together_end_full
                RejectReason.Kicked -> R.string.together_end_kicked
                RejectReason.BadVersion -> R.string.together_end_version
                else -> R.string.together_end_generic
            },
        )
        GuestStatus.Connected -> return
    }
    Text(
        text,
        style = AuraTheme.type.body,
        color = AuraTheme.colors.textPrimary,
        modifier = Modifier
            .padding(Spacing.gutter)
            .fillMaxWidth()
            .clip(Radius.medium)
            .background(AuraTheme.colors.surface.copy(alpha = 0.7f))
            .padding(Spacing.x4),
    )
}

@Composable
private fun AmbiguityCard(count: Int) {
    Text(
        stringResource(R.string.together_ambiguous, count),
        style = AuraTheme.type.bodySmall,
        color = AuraTheme.colors.textSecondary,
        modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
    )
}

@Composable
private fun ModeRow(mode: ControlMode, onSelect: (ControlMode) -> Unit) {
    Row(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2), horizontalArrangement = Arrangement.spacedBy(Spacing.x2)) {
        AuraChip(stringResource(R.string.together_mode_democratic), mode == ControlMode.Democratic, onClick = { onSelect(ControlMode.Democratic) })
        AuraChip(stringResource(R.string.together_mode_host), mode == ControlMode.HostControl, onClick = { onSelect(ControlMode.HostControl) })
    }
}

@Composable
private fun ParticipantRow(p: Participant, isHost: Boolean, me: String?, viewModel: TogetherViewModel) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = Spacing.gutter, end = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(AuraTheme.colors.surfaceElevated), contentAlignment = Alignment.Center) {
            Text(p.name.take(1).uppercase(), style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary)
        }
        Column(Modifier.weight(1f).padding(horizontal = Spacing.x3)) {
            Text(p.name + if (p.id == me) " " + stringResource(R.string.together_you) else "", style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary)
            Text(
                stringResource(if (p.isHost) R.string.together_role_host else if (p.canControl) R.string.together_role_controls else R.string.together_role_listens),
                style = AuraTheme.type.bodySmall,
                color = AuraTheme.colors.textSecondary,
            )
        }
        if (isHost && !p.isHost) {
            Switch(
                checked = p.canControl,
                onCheckedChange = { viewModel.setCanControl(p.id, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = AuraTheme.colors.accent, checkedThumbColor = AuraTheme.colors.onAccent),
            )
            AuraIconButton(AuraIcons.Close, stringResource(R.string.together_kick, p.name), { viewModel.kick(p.id) }, iconSize = 18.dp, tint = AuraTheme.colors.textSecondary)
        }
    }
}

/** Quick emoji reactions everyone in the jam sees. */
@Composable
private fun ReactionBar(onReact: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.x3), horizontalArrangement = Arrangement.SpaceEvenly) {
        Reactions.allowed.forEach { emoji ->
            val label = stringResource(R.string.cd_react, emoji)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AuraTheme.colors.surface.copy(alpha = 0.6f))
                    .clickable(role = Role.Button, onClickLabel = label) { onReact(emoji) },
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 22.sp)
            }
        }
    }
}

private class FloatingReaction(val id: Long, val emoji: String, val from: String, val x: Float)

/** Reactions rise from the bottom of the screen and fade, with the name of who sent them. */
@Composable
private fun ReactionOverlay(viewModel: TogetherViewModel) {
    val floating = remember { mutableStateListOf<FloatingReaction>() }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(Unit) {
        var next = 0L
        viewModel.reactions.collect { r ->
            val item = FloatingReaction(next++, r.emoji, r.from, 0.15f + kotlin.random.Random.nextFloat() * 0.7f)
            floating += item
            launch {
                delay(REACTION_MS)
                floating.remove(item)
            }
        }
    }
    // Drawn above the session's content; it has no touch handling, so taps reach what's below.
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(1f)) {
        val height = maxHeight
        val width = maxWidth
        floating.forEach { r ->
            key(r.id) {
                val progress = remember { Animatable(0f) }
                LaunchedEffect(Unit) { progress.animateTo(1f, tween(REACTION_MS.toInt(), easing = LinearOutSlowInEasing)) }
                Column(
                    Modifier
                        .offset(x = width * r.x - 32.dp, y = height * 0.82f)
                        .graphicsLayer {
                            translationY = if (reduced) 0f else -progress.value * height.toPx() * 0.45f
                            alpha = (1f - progress.value).coerceIn(0f, 1f)
                            val s = if (reduced) 1f else 0.8f + 0.4f * minOf(1f, progress.value * 4)
                            scaleX = s
                            scaleY = s
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(r.emoji, fontSize = 40.sp)
                    Text(r.from, style = AuraTheme.type.label, color = AuraTheme.colors.textPrimary, maxLines = 1)
                }
            }
        }
    }
}

private const val REACTION_MS = 2_600L

/** A song a guest added that the host is still receiving; it joins the queue when complete. */
@Composable
private fun IncomingRow(incoming: IncomingTrack) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
    ) {
        Text(incoming.track.title, style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(R.string.together_incoming, incoming.from, (incoming.progress * 100).toInt()),
            style = AuraTheme.type.bodySmall,
            color = AuraTheme.colors.textTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.height(Spacing.x2))
        LinearProgressIndicator(
            progress = { incoming.progress },
            modifier = Modifier.fillMaxWidth().clip(CircleShape),
            color = AuraTheme.colors.accent,
            trackColor = AuraTheme.colors.surfaceElevated,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun QueueRow(index: Int, title: String, artist: String, current: Boolean, canControl: Boolean, size: Int, viewModel: TogetherViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = canControl && !current, role = Role.Button) { viewModel.command(Command.SkipTo(index)) }
            .padding(start = Spacing.gutter, end = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraTheme.type.titleSmall, color = if (current) AuraTheme.colors.accent else AuraTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary, maxLines = 1)
        }
        if (canControl) {
            AuraIconButton(AuraIcons.ArrowUp, stringResource(R.string.a11y_move_up), { viewModel.command(Command.MoveInQueue(index, index - 1)) }, enabled = index > 0, iconSize = 18.dp)
            AuraIconButton(AuraIcons.ArrowDown, stringResource(R.string.a11y_move_down), { viewModel.command(Command.MoveInQueue(index, index + 1)) }, enabled = index < size - 1, iconSize = 18.dp)
        }
    }
}

@Composable
private fun AddSheet(viewModel: TogetherViewModel, onDismiss: () -> Unit) {
    val query by viewModel.addQueryText.collectAsStateWithLifecycle()
    val candidates by viewModel.addCandidates.collectAsStateWithLifecycle()
    val messenger = LocalMessenger.current
    val resources = LocalResources.current
    AuraSheet(onDismiss) {
        SheetHeader(stringResource(R.string.queue_add), stringResource(R.string.together_add_note))
        SearchField(query, viewModel::setAddQuery, stringResource(R.string.picker_filter), Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            items(candidates, key = { it.id }) { track ->
                TrackRow(track = track, onClick = {
                    viewModel.add(track)
                    messenger.show(resources.getString(R.string.msg_added_to_queue))
                })
            }
        }
    }
}
