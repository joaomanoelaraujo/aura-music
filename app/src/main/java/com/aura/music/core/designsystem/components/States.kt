package com.aura.music.core.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalDecorativeMotion
import com.aura.music.core.designsystem.theme.Spacing

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.x10, vertical = Spacing.x12),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.x3),
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AuraTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AuraTheme.colors.textSecondary, modifier = Modifier.size(28.dp))
        }
        Text(title, style = AuraTheme.type.title, color = AuraTheme.colors.textPrimary, textAlign = TextAlign.Center)
        Text(message, style = AuraTheme.type.body, color = AuraTheme.colors.textSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            AuraButton(actionLabel, onAction, modifier = Modifier.padding(top = Spacing.x3))
        }
    }
}

/** AURA's mark, breathing gently. Used for loading moments instead of a generic spinner. */
@Composable
fun AuraMark(modifier: Modifier = Modifier, size: Dp = 56.dp, color: Color = AuraTheme.colors.textPrimary, animate: Boolean = true) {
    val shouldAnimate = animate && LocalDecorativeMotion.current
    val transition = rememberInfiniteTransition(label = "mark")
    val pulse by if (shouldAnimate) {
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }
    val accent = AuraTheme.colors.accent
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 2f
        val stroke = Stroke(width = unit * 0.06f)
        drawCircle(color.copy(alpha = 0.12f + 0.16f * pulse), radius = unit * (0.88f + 0.06f * pulse), style = stroke)
        drawCircle(color.copy(alpha = 0.45f + 0.2f * pulse), radius = unit * 0.56f, style = stroke)
        drawCircle(accent, radius = unit * 0.24f)
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.loading)
    Box(modifier.fillMaxSize().semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        AuraMark()
    }
}
