package com.aura.music.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.components.AuraButton
import com.aura.music.core.designsystem.components.AuraMark
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.LocalReducedMotion
import com.aura.music.core.designsystem.theme.Motion
import com.aura.music.core.designsystem.theme.Spacing
import com.aura.music.data.media.AudioPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A single, calm introduction: what AURA is, and one permission request.
 * The onboarding completes whatever the answer; the library screens handle a denial gracefully.
 */
@Composable
fun OnboardingScreen(onFinished: (granted: Boolean) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onFinished(granted)
    }
    val reduced = LocalReducedMotion.current
    val reveal = remember { List(5) { Animatable(if (reduced) 1f else 0f) } }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        reveal.forEachIndexed { index, animatable ->
            launch {
                delay(120L * index)
                animatable.animateTo(1f, tween(Motion.LONG + 200, easing = Motion.Decelerate))
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AuraTheme.colors.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = Spacing.x6),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(Spacing.x12))
            Box(Modifier.revealed(reveal[0].value)) { AuraMark(size = 64.dp) }
            Spacer(Modifier.height(Spacing.x8))
            Text(
                stringResource(R.string.onboarding_eyebrow).uppercase() + " " + stringResource(R.string.app_name),
                style = AuraTheme.type.overline,
                color = AuraTheme.colors.textSecondary,
                modifier = Modifier.revealed(reveal[1].value),
            )
            Text(
                stringResource(R.string.onboarding_title),
                style = AuraTheme.type.displayLarge,
                color = AuraTheme.colors.textPrimary,
                modifier = Modifier.padding(top = Spacing.x3).revealed(reveal[1].value),
            )
            Spacer(Modifier.height(Spacing.x10))
            Point(AuraIcons.Note, R.string.onboarding_point_offline_title, R.string.onboarding_point_offline_body, Modifier.revealed(reveal[2].value))
            Point(AuraIcons.Album, R.string.onboarding_point_atmosphere_title, R.string.onboarding_point_atmosphere_body, Modifier.revealed(reveal[3].value))
            Point(AuraIcons.Info, R.string.onboarding_point_privacy_title, R.string.onboarding_point_privacy_body, Modifier.revealed(reveal[4].value))
        }
        AuraButton(
            text = stringResource(R.string.onboarding_grant),
            onClick = { launcher.launch(AudioPermission.name) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.x6)
                .revealed(reveal[4].value),
        )
    }
}

@Composable
private fun Point(icon: ImageVector, title: Int, body: Int, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = Spacing.x3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.x4),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AuraTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AuraTheme.colors.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = AuraTheme.type.titleSmall, color = AuraTheme.colors.textPrimary)
            Text(stringResource(body), style = AuraTheme.type.bodySmall, color = AuraTheme.colors.textSecondary)
        }
    }
}

private fun Modifier.revealed(progress: Float): Modifier = graphicsLayer {
    alpha = progress
    translationY = (1f - progress) * 24.dp.toPx()
}
