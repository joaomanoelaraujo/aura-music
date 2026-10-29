package com.aura.music.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aura.music.R
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.data.repository.MixKind

fun mixTitle(kind: MixKind): Int = when (kind) {
    MixKind.Calm -> R.string.mix_calm
    MixKind.Energy -> R.string.mix_energy
    MixKind.MostPlayed -> R.string.mix_most_played
    MixKind.Rediscover -> R.string.mix_rediscover
}

fun mixSubtitle(kind: MixKind): Int = when (kind) {
    MixKind.Calm -> R.string.mix_calm_body
    MixKind.Energy -> R.string.mix_energy_body
    MixKind.MostPlayed -> R.string.mix_most_played_body
    MixKind.Rediscover -> R.string.mix_rediscover_body
}

private fun mixColors(kind: MixKind): List<Color> = when (kind) {
    MixKind.Calm -> listOf(Color(0xFF3B6E8F), Color(0xFF1B2A3A))
    MixKind.Energy -> listOf(Color(0xFFE0533D), Color(0xFF4A1530))
    MixKind.MostPlayed -> listOf(Color(0xFF8E6BD8), Color(0xFF26193F))
    MixKind.Rediscover -> listOf(Color(0xFFC9A04A), Color(0xFF3A2A12))
}

private fun mixIcon(kind: MixKind): ImageVector = when (kind) {
    MixKind.Calm -> AuraIcons.Wave
    MixKind.Energy -> AuraIcons.Equalizer
    MixKind.MostPlayed -> AuraIcons.Heart
    MixKind.Rediscover -> AuraIcons.History
}

/** A mix's cover: its own colours and symbol, so mixes read apart from albums at a glance. */
@Composable
fun MixCover(kind: MixKind, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(Radius.xLarge).background(Brush.linearGradient(mixColors(kind))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(mixIcon(kind), contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(48.dp))
    }
}
