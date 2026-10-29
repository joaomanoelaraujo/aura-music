package com.aura.music.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.ButtonStyle
import com.aura.music.core.designsystem.components.EmptyState
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.navigation.LocalNavigator

/** Summary line with "Play" and "Shuffle" pill buttons, shared by every track collection. */
@Composable
fun PlayShuffleRow(
    summary: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AuraTheme.colors.accent,
    onAccent: Color = AuraTheme.colors.onAccent,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2),
    ) {
        Text(
            summary,
            style = AuraTheme.type.bodySmall,
            color = AuraTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        AuraButton(stringResource(R.string.action_shuffle), onShuffle, icon = AuraIcons.Shuffle, style = ButtonStyle.Secondary)
        AuraButton(stringResource(R.string.action_play), onPlay, icon = AuraIcons.Play, accent = accent, onAccent = onAccent)
    }
}

/** Editorial header block used by album, artist, playlist and collection screens. */
@Composable
fun CollectionTitle(
    eyebrow: String,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    centered: Boolean = true,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Text(eyebrow.uppercase(), style = AuraTheme.type.overline, color = AuraTheme.colors.textSecondary)
        Text(
            title,
            style = AuraTheme.type.display,
            color = AuraTheme.colors.textPrimary,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.x1),
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                style = AuraTheme.type.bodySmall,
                color = AuraTheme.colors.textSecondary,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.padding(top = Spacing.x2),
            )
        }
    }
}

/** Shown when a navigated-to item (album, artist, playlist) no longer exists. */
@Composable
fun NotFound() {
    val navigator = LocalNavigator.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = AuraIcons.Info,
            title = stringResource(R.string.not_found_title),
            message = stringResource(R.string.not_found_body),
            actionLabel = stringResource(R.string.cd_back),
            onAction = { navigator.back() },
        )
    }
}
