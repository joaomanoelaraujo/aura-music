package com.aura.music.feature.link

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.music.R
import com.aura.music.core.common.formatDuration
import com.aura.music.core.designsystem.components.AuraIconButton
import com.aura.music.core.designsystem.components.AuraSeekBar
import com.aura.music.core.designsystem.components.AuraTopBar
import com.aura.music.core.designsystem.components.PlayPauseButton
import com.aura.music.core.designsystem.components.SectionHeader
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.link.LinkPermissions
import com.aura.music.link.RemoteAction
import com.aura.music.link.RemoteRepeat
import com.aura.music.link.RemoteTrack
import com.aura.music.navigation.LocalNavigator
import com.aura.music.ui.LocalBottomInset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Controlling a linked computer's player from the phone. */
@Composable
fun LinkRemoteScreen(deviceId: String, viewModel: LinkViewModel = hiltViewModel()) {
    val link = viewModel.link
    val state by link.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val colors = AuraTheme.colors
    val computer = state.computers.firstOrNull { it.device.deviceId == deviceId }
    LaunchedEffect(deviceId, computer?.connected) { if (computer?.connected == true) link.requestState(deviceId) }

    Column(Modifier.fillMaxSize().imePadding()) {
        AuraTopBar(title = computer?.device?.name, onBack = { navigator.back() })
        if (computer == null || !computer.connected) {
            Text(
                stringResource(R.string.link_remote_offline, computer?.device?.name.orEmpty()),
                style = AuraTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(Spacing.gutter),
            )
            return
        }
        val remote = computer.remoteState
        val granted = computer.granted ?: LinkPermissions(false, false)
        fun send(action: RemoteAction) = link.command(deviceId, action)
        val now by produceState(SystemClock.elapsedRealtime()) {
            while (true) {
                value = SystemClock.elapsedRealtime()
                delay(250)
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current + Spacing.x8)) {
            item {
                Column(Modifier.padding(horizontal = Spacing.gutter)) {
                    Text(stringResource(R.string.link_playing_on, computer.device.name), style = AuraTheme.type.label, color = colors.accent)
                    Text(stringResource(R.string.link_controlling_from, state.deviceName), style = AuraTheme.type.bodySmall, color = colors.textTertiary)
                }
            }
            item {
                val track = remote?.current
                Row(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x4), verticalAlignment = Alignment.CenterVertically) {
                    RemoteCover(viewModel, deviceId, track)
                    Column(Modifier.padding(start = Spacing.x4)) {
                        Text(track?.title ?: stringResource(R.string.link_remote_nothing), style = AuraTheme.type.headline, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track?.let { listOfNotNull(it.artist, it.album).joinToString(" · ") }.orEmpty(), style = AuraTheme.type.body, color = colors.textSecondary, maxLines = 1)
                    }
                }
            }
            if (remote != null) {
                item {
                    val position = if (remote.isPlaying) (remote.positionMs + (now - computer.receivedAt)).coerceAtMost(remote.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE) else remote.positionMs
                    Column(Modifier.padding(horizontal = Spacing.gutter)) {
                        AuraSeekBar(positionMs = position, durationMs = remote.durationMs, onSeek = { if (granted.controlPlayback) send(RemoteAction.Seek(it)) })
                        Row(Modifier.fillMaxWidth().padding(top = Spacing.x3), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            val enabled = granted.controlPlayback
                            AuraIconButton(AuraIcons.Shuffle, stringResource(R.string.link_remote_shuffle), { send(RemoteAction.SetShuffle(!remote.shuffle)) }, tint = if (remote.shuffle) colors.accent else colors.textSecondary, enabled = enabled)
                            AuraIconButton(AuraIcons.Previous, stringResource(R.string.link_remote_previous), { send(RemoteAction.Previous) }, enabled = enabled)
                            PlayPauseButton(remote.isPlaying, { if (enabled) send(if (remote.isPlaying) RemoteAction.Pause else RemoteAction.Play) })
                            AuraIconButton(AuraIcons.Next, stringResource(R.string.link_remote_next), { send(RemoteAction.Next) }, enabled = enabled)
                            AuraIconButton(
                                if (remote.repeat == RemoteRepeat.One) AuraIcons.RepeatOne else AuraIcons.Repeat, stringResource(R.string.link_remote_repeat),
                                { send(RemoteAction.SetRepeat(when (remote.repeat) { RemoteRepeat.Off -> RemoteRepeat.All; RemoteRepeat.All -> RemoteRepeat.One; RemoteRepeat.One -> RemoteRepeat.Off })) },
                                tint = if (remote.repeat != RemoteRepeat.Off) colors.accent else colors.textSecondary, enabled = enabled,
                            )
                        }
                        Text(stringResource(R.string.link_remote_volume), style = AuraTheme.type.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(top = Spacing.x3))
                        var volume by remember(remote.revision) { mutableStateOf(remote.volume) }
                        Slider(
                            value = volume, onValueChange = { volume = it }, onValueChangeFinished = { send(RemoteAction.SetVolume(volume)) },
                            enabled = granted.controlPlayback,
                            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent),
                        )
                        if (!granted.controlPlayback) Text(stringResource(R.string.link_remote_not_allowed), style = AuraTheme.type.bodySmall, color = colors.danger)
                    }
                }
                item { SectionHeader(stringResource(R.string.link_remote_queue)) }
                itemsIndexed(remote.queue, key = { _, it -> it.uid }) { i, item ->
                    val current = i == remote.index
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = granted.controlPlayback && !current) { send(RemoteAction.JumpTo(item.uid)) }
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.track.title, style = AuraTheme.type.body, color = if (current) colors.accent else colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.track.artist.orEmpty(), style = AuraTheme.type.bodySmall, color = colors.textSecondary, maxLines = 1)
                        }
                        if (granted.controlPlayback && !current) AuraIconButton(AuraIcons.Close, stringResource(R.string.link_remote_remove_from_queue), { send(RemoteAction.Remove(item.uid)) })
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.link_remote_library)) }
            item {
                if (granted.browseLibrary) RemoteLibrary(viewModel, deviceId)
                else Text(stringResource(R.string.link_remote_library_hidden), style = AuraTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(horizontal = Spacing.gutter))
            }
        }
    }
}

@Composable
private fun RemoteCover(viewModel: LinkViewModel, deviceId: String, track: RemoteTrack?) {
    val colors = AuraTheme.colors
    val bitmap by produceState<ImageBitmap?>(null, track?.id) {
        value = track?.let { t -> viewModel.link.remoteArtwork(deviceId, t.id)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }
    }
    Box(Modifier.size(96.dp).clip(Radius.medium).background(colors.surfaceElevated), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else androidx.compose.material3.Icon(AuraIcons.Note, null, tint = colors.textTertiary)
    }
}

@Composable
private fun RemoteLibrary(viewModel: LinkViewModel, deviceId: String) {
    val colors = AuraTheme.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RemoteTrack>?>(null) }
    var failed by remember { mutableStateOf(false) }
    fun run() = scope.launch {
        val r = viewModel.link.search(deviceId, query)
        failed = r == null
        results = r.orEmpty()
    }
    LaunchedEffect(deviceId) { run() }
    Column(Modifier.padding(horizontal = Spacing.gutter)) {
        BasicTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            textStyle = AuraTheme.type.body.copy(color = colors.textPrimary), cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(Radius.pill).background(colors.surfaceElevated).padding(horizontal = Spacing.x4, vertical = Spacing.x3)) {
                    if (query.isEmpty()) Text(stringResource(R.string.link_remote_search), style = AuraTheme.type.body, color = colors.textTertiary)
                    inner()
                }
            },
        )
        if (failed) Text(stringResource(R.string.link_remote_no_answer), style = AuraTheme.type.bodySmall, color = colors.danger, modifier = Modifier.padding(top = Spacing.x2))
        val list = results.orEmpty()
        list.forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.link.command(deviceId, RemoteAction.PlayTracks(list.map { it.id }, i)) }.padding(vertical = Spacing.x2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = AuraTheme.type.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(t.artist, formatDuration(t.durationMs)).joinToString(" · "), style = AuraTheme.type.bodySmall, color = colors.textSecondary, maxLines = 1)
                }
                AuraIconButton(AuraIcons.AddToQueue, stringResource(R.string.link_remote_add_to_queue), { viewModel.link.command(deviceId, RemoteAction.AddToQueue(listOf(t.id))) })
            }
        }
    }
}
