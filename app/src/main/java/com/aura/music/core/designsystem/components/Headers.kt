package com.aura.music.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Spacing

/** Compact top bar. [titleAlpha] lets scrolling screens reveal the title as their hero collapses. */
@Composable
fun AuraTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleAlpha: Float = 1f,
    background: Color = Color.Transparent,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = Spacing.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            AuraIconButton(AuraIcons.Back, stringResource(R.string.cd_back), onBack)
        } else {
            Box(Modifier.padding(start = Spacing.x4))
        }
        Text(
            text = title.orEmpty(),
            style = AuraTheme.type.title,
            color = AuraTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.x1)
                .alpha(titleAlpha)
                .semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Editorial page title used by top-level destinations. */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, eyebrow: String? = null) {
    androidx.compose.foundation.layout.Column(
        modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.x2),
    ) {
        if (eyebrow != null) {
            Text(eyebrow.uppercase(), style = AuraTheme.type.overline, color = AuraTheme.colors.textTertiary)
        }
        Text(
            text,
            style = AuraTheme.type.display,
            color = AuraTheme.colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.gutter, end = Spacing.x2, top = Spacing.x6, bottom = Spacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = AuraTheme.type.headline,
            color = AuraTheme.colors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                style = AuraTheme.type.label,
                color = AuraTheme.colors.textSecondary,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(horizontal = Spacing.x3, vertical = Spacing.x3),
            )
        }
    }
}
