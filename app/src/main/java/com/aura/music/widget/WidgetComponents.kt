package com.aura.music.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aura.music.MainActivity
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.AtmosphereColors

/** Resolved colours for one widget render, derived from the atmosphere with guaranteed contrast. */
internal data class WidgetPalette(
    val background: Color,
    val accent: Color,
    val onAccent: Color,
    val textPrimary: Color = Color(0xFFF5F5F7),
    val textSecondary: Color = Color(0xFFA6A8B1),
    val track: Color = Color(0x26FFFFFF),
) {
    companion object {
        fun from(colors: AtmosphereColors) = WidgetPalette(
            background = Color(colors.background),
            accent = Color(colors.accent),
            onAccent = Color(colors.onAccent),
        )
    }
}

internal val WidgetCorner = 24.dp

/** Rounded, atmosphere-tinted surface; tapping anywhere outside the controls opens AURA. */
@Composable
internal fun WidgetSurface(
    context: Context,
    palette: WidgetPalette,
    openPlayer: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(WidgetCorner)
            .background(ImageProvider(R.drawable.widget_surface), colorFilter = ColorFilter.tint(ColorProvider(palette.background)))
            .clickable(openAppAction(context, openPlayer)),
    ) { content() }
}

internal fun openAppAction(context: Context, openPlayer: Boolean): Action = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_OPEN_PLAYER, openPlayer)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
)

@Composable
internal fun WidgetCover(visuals: WidgetVisuals?, size: Dp) {
    val cover = visuals?.cover
    if (cover != null) {
        Image(
            provider = ImageProvider(cover),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier.size(size),
        )
    } else {
        Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.ic_aura_mark), contentDescription = null, modifier = GlanceModifier.size(size * 0.9f))
        }
    }
}

/** The primary control: a solid accent circle, mirroring the app's play button. */
@Composable
internal fun PlayPauseControl(isPlaying: Boolean, palette: WidgetPalette, size: Dp, label: String) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .background(ImageProvider(R.drawable.widget_circle), colorFilter = ColorFilter.tint(ColorProvider(palette.accent)))
            .clickable(actionRunCallback<PlayPauseAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
            contentDescription = label,
            colorFilter = ColorFilter.tint(ColorProvider(palette.onAccent)),
            modifier = GlanceModifier.size(size * 0.46f),
        )
    }
}

@Composable
internal fun SkipControl(next: Boolean, palette: WidgetPalette, size: Dp, label: String) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .clickable(if (next) actionRunCallback<NextAction>() else actionRunCallback<PreviousAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (next) R.drawable.ic_widget_next else R.drawable.ic_widget_previous),
            contentDescription = label,
            colorFilter = ColorFilter.tint(ColorProvider(palette.textPrimary)),
            modifier = GlanceModifier.size(size * 0.5f),
        )
    }
}

@Composable
internal fun WidgetTitle(text: String, palette: WidgetPalette, size: TextUnit = 15.sp, serif: Boolean = false) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(
            color = ColorProvider(palette.textPrimary),
            fontSize = size,
            fontWeight = if (serif) FontWeight.Normal else FontWeight.Medium,
            fontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif,
        ),
    )
}

@Composable
internal fun WidgetSubtitle(text: String, palette: WidgetPalette, size: TextUnit = 13.sp) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(color = ColorProvider(palette.textSecondary), fontSize = size, fontFamily = FontFamily.SansSerif),
    )
}

/** Thin progress line; the widget re-renders on playback events and every 30 s while playing. */
@Composable
internal fun WidgetProgress(progress: Float, palette: WidgetPalette) {
    LinearProgressIndicator(
        progress = progress,
        color = ColorProvider(palette.accent),
        backgroundColor = ColorProvider(palette.track),
        modifier = GlanceModifier.fillMaxWidth().height(3.dp).cornerRadius(2.dp),
    )
}
