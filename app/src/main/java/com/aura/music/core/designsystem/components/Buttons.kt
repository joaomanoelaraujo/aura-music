package com.aura.music.core.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.designsystem.theme.Sizes
import com.aura.music.core.designsystem.theme.Spacing

/** Subtle scale-down while pressed: AURA's signature tactile feedback. */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.94f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = Motion.settle(reduced),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun AuraIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AuraTheme.colors.textPrimary,
    iconSize: Dp = Sizes.icon,
    enabled: Boolean = true,
    /** The press-and-shrink feedback; list rows turn it off, since it costs two coroutines per row. */
    scaleOnPress: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(Sizes.touchTarget)
            .then(if (scaleOnPress) Modifier.pressScale(interaction) else Modifier)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** The single most important control in the app: animated morph between play and pause. */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    container: Color = AuraTheme.colors.accent,
    content: Color = AuraTheme.colors.onAccent,
) {
    val interaction = remember { MutableInteractionSource() }
    val reduced = LocalReducedMotion.current
    val label = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play)
    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction, pressedScale = 0.92f)
            .clip(CircleShape)
            .background(container)
            .clickable(interaction, ripple(color = content), role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                if (reduced) {
                    fadeIn(Motion.tween(0, true)) togetherWith fadeOut(Motion.tween(0, true))
                } else {
                    (fadeIn(Motion.tween(Motion.SHORT, false)) + scaleIn(Motion.tween(Motion.MEDIUM, false), initialScale = 0.6f)) togetherWith
                        (fadeOut(Motion.tween(Motion.SHORT, false)) + scaleOut(Motion.tween(Motion.SHORT, false), targetScale = 0.6f))
                }
            },
            label = "playPause",
        ) { playing ->
            Icon(
                imageVector = if (playing) AuraIcons.Pause else AuraIcons.Play,
                contentDescription = label,
                tint = content,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

enum class ButtonStyle { Primary, Secondary }

/** Pill button for collection actions such as "Play" and "Shuffle". */
@Composable
fun AuraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ButtonStyle = ButtonStyle.Primary,
    accent: Color = AuraTheme.colors.accent,
    onAccent: Color = AuraTheme.colors.onAccent,
    enabled: Boolean = true,
) {
    val colors = AuraTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val container = if (style == ButtonStyle.Primary) accent else Color.Transparent
    val contentColor = if (style == ButtonStyle.Primary) onAccent else colors.textPrimary
    Row(
        modifier = modifier
            .heightIn(min = Sizes.touchTarget)
            .pressScale(interaction, pressedScale = 0.96f)
            .clip(Radius.pill)
            .background(container)
            .then(if (style == ButtonStyle.Secondary) Modifier.border(1.dp, colors.outline, Radius.pill) else Modifier)
            .clickable(interaction, ripple(color = contentColor), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.x5, vertical = Spacing.x3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(Sizes.iconSmall))
        }
        Text(text, style = AuraTheme.type.label, color = contentColor)
    }
}

/** Small toggle-able chip used for filters and segmented choices. */
@Composable
fun AuraChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AuraTheme.colors.accent,
) {
    val colors = AuraTheme.colors
    val reduced = LocalReducedMotion.current
    val selection by animateFloatAsState(if (selected) 1f else 0f, Motion.tween(Motion.SHORT, reduced), label = "chip")
    Box(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(Radius.pill)
            .background(androidx.compose.ui.graphics.lerp(colors.surface, accent, selection))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = Spacing.x4, vertical = Spacing.x2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = AuraTheme.type.label,
            color = androidx.compose.ui.graphics.lerp(colors.textSecondary, colors.onAccent, selection),
            maxLines = 1,
            softWrap = false,
        )
    }
}
