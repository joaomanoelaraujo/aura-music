package com.aura.music.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.aura.music.R
import com.aura.music.core.designsystem.atmosphere.AtmosphereColorMath

/** Visual size class of each widget; drives artwork resolution and the glow layer. */
internal enum class WidgetKind(val coverDp: Int, val cornerFraction: Float, val glow: Boolean) {
    Minimal(coverDp = 40, cornerFraction = 0.25f, glow = false),
    Compact(coverDp = 52, cornerFraction = 0.23f, glow = false),
    Standard(coverDp = 110, cornerFraction = 0.14f, glow = true),
    Immersive(coverDp = 240, cornerFraction = 0.08f, glow = true),
}

/**
 * Shared machinery: reads the snapshot written by the playback service, loads artwork and the
 * atmosphere palette for it, and renders the variant. Content recomposes when the snapshot
 * changes; nothing animates continuously.
 */
abstract class AuraWidget internal constructor(private val kind: WidgetKind) : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = context.widgetEntryPoint().widgetStateStore()
        val coverPx = (kind.coverDp * context.resources.displayMetrics.density).toInt()
        // Large rounded overlays can only be clipped by the widget frame from Android 12.
        val glow = kind.glow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        provideContent {
            val snapshot by store.snapshot.collectAsState(initial = null)
            val visuals by produceState<WidgetVisuals?>(null, snapshot?.albumId, snapshot?.trackUri) {
                value = WidgetVisualsLoader.load(context, snapshot?.artwork, coverPx, kind.cornerFraction, glow)
            }
            val current = snapshot
            val palette = WidgetPalette.from(visuals?.colors ?: AtmosphereColorMath.Neutral)
            WidgetSurface(context, palette, openPlayer = current?.hasMedia == true) {
                visuals?.glow?.let {
                    Image(ImageProvider(it), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
                }
                Content(current, visuals, palette)
            }
        }
    }

    @Composable
    internal abstract fun Content(snapshot: WidgetSnapshot?, visuals: WidgetVisuals?, palette: WidgetPalette)
}

@Composable
private fun strings() = LocalContext.current.let { context ->
    WidgetStrings(
        nothingPlaying = context.getString(R.string.widget_nothing_playing),
        openApp = context.getString(R.string.widget_open_app),
        unknownArtist = context.getString(R.string.unknown_artist),
        play = context.getString(R.string.action_play),
        pause = context.getString(R.string.action_pause),
        next = context.getString(R.string.cd_next),
        previous = context.getString(R.string.cd_previous),
    )
}

private data class WidgetStrings(
    val nothingPlaying: String,
    val openApp: String,
    val unknownArtist: String,
    val play: String,
    val pause: String,
    val next: String,
    val previous: String,
)

private fun WidgetSnapshot?.isActive() = this?.hasMedia == true

@Composable
private fun TitleAndArtist(snapshot: WidgetSnapshot?, palette: WidgetPalette, titleSize: Float = 15f, serif: Boolean = false) {
    val text = strings()
    if (snapshot.isActive()) {
        WidgetTitle(snapshot!!.title, palette, titleSize.sp, serif)
        WidgetSubtitle(snapshot.artist ?: text.unknownArtist, palette)
    } else {
        WidgetTitle(text.nothingPlaying, palette, titleSize.sp, serif)
        WidgetSubtitle(text.openApp, palette)
    }
}

/** AURA Minimal: artwork, play/pause and next in the smallest footprint. */
class MinimalWidget : AuraWidget(WidgetKind.Minimal) {
    @Composable
    override fun Content(snapshot: WidgetSnapshot?, visuals: WidgetVisuals?, palette: WidgetPalette) {
        val text = strings()
        Row(GlanceModifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(visuals, 40.dp)
            Spacer(GlanceModifier.defaultWeight())
            PlayPauseControl(snapshot?.isPlaying == true, palette, 40.dp, if (snapshot?.isPlaying == true) text.pause else text.play)
            SkipControl(next = true, palette = palette, size = 40.dp, label = text.next)
        }
    }
}

/** AURA Compact: one row with artwork, title, artist and the two essential controls. */
class CompactWidget : AuraWidget(WidgetKind.Compact) {
    @Composable
    override fun Content(snapshot: WidgetSnapshot?, visuals: WidgetVisuals?, palette: WidgetPalette) {
        val text = strings()
        Row(GlanceModifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(visuals, 48.dp)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) { TitleAndArtist(snapshot, palette) }
            Spacer(GlanceModifier.width(8.dp))
            PlayPauseControl(snapshot?.isPlaying == true, palette, 42.dp, if (snapshot?.isPlaying == true) text.pause else text.play)
            SkipControl(next = true, palette = palette, size = 40.dp, label = text.next)
        }
    }
}

/** AURA Standard: larger artwork with metadata, progress and evenly spread transport controls. */
class StandardWidget : AuraWidget(WidgetKind.Standard) {
    @Composable
    override fun Content(snapshot: WidgetSnapshot?, visuals: WidgetVisuals?, palette: WidgetPalette) {
        val text = strings()
        val size = LocalSize.current
        val cover = (size.height - 24.dp).coerceIn(64.dp, 120.dp)
        Row(GlanceModifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            WidgetCover(visuals, cover)
            Spacer(GlanceModifier.width(14.dp))
            Column(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                TitleAndArtist(snapshot, palette, titleSize = 16f)
                Spacer(GlanceModifier.height(10.dp))
                if (snapshot.isActive()) {
                    WidgetProgress(snapshot!!.progressAt(System.currentTimeMillis()), palette)
                    Spacer(GlanceModifier.height(6.dp))
                }
                TransportRow(snapshot, palette, text, playSize = 42.dp)
            }
        }
    }
}

/** AURA Immersive: editorial layout led by the artwork, over a soft atmospheric glow. */
class ImmersiveWidget : AuraWidget(WidgetKind.Immersive) {
    @Composable
    override fun Content(snapshot: WidgetSnapshot?, visuals: WidgetVisuals?, palette: WidgetPalette) {
        val text = strings()
        val size: DpSize = LocalSize.current
        val cover = minOf(size.width - 40.dp, size.height - 168.dp).coerceAtLeast(72.dp)
        Column(
            GlanceModifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                WidgetCover(visuals, cover)
            }
            Spacer(GlanceModifier.height(12.dp))
            Column(GlanceModifier.fillMaxWidth()) { TitleAndArtist(snapshot, palette, titleSize = 22f, serif = true) }
            Spacer(GlanceModifier.height(10.dp))
            if (snapshot.isActive()) {
                WidgetProgress(snapshot!!.progressAt(System.currentTimeMillis()), palette)
                Spacer(GlanceModifier.height(8.dp))
            }
            TransportRow(snapshot, palette, text, playSize = 48.dp)
        }
    }
}

@Composable
private fun TransportRow(snapshot: WidgetSnapshot?, palette: WidgetPalette, text: WidgetStrings, playSize: androidx.compose.ui.unit.Dp) {
    val playing = snapshot?.isPlaying == true
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        SkipControl(next = false, palette = palette, size = 40.dp, label = text.previous)
        Spacer(GlanceModifier.defaultWeight())
        PlayPauseControl(playing, palette, playSize, if (playing) text.pause else text.play)
        Spacer(GlanceModifier.defaultWeight())
        SkipControl(next = true, palette = palette, size = 40.dp, label = text.next)
    }
}

class MinimalWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MinimalWidget()
}

class CompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CompactWidget()
}

class StandardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StandardWidget()
}

class ImmersiveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ImmersiveWidget()
}

/** Registry used by the playback service to refresh every placed widget. */
object AuraWidgets {
    private val widgets: List<GlanceAppWidget> get() = listOf(MinimalWidget(), CompactWidget(), StandardWidget(), ImmersiveWidget())

    suspend fun updateAll(context: Context) = widgets.forEach { it.updateAll(context) }

    suspend fun anyPlaced(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return widgets.any { manager.getGlanceIds(it.javaClass).isNotEmpty() }
    }
}
