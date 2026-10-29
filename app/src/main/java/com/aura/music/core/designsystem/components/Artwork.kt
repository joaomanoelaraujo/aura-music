package com.aura.music.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.AuraTheme
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.image.ArtworkUpdates
import com.aura.music.core.model.ArtworkSource

/**
 * Album artwork with AURA's placeholder. The placeholder is a quiet tonal gradient chosen
 * deterministically from [placeholderSeed] so artless albums still feel intentional.
 */
@Composable
fun Artwork(
    source: ArtworkSource?,
    modifier: Modifier = Modifier,
    shape: Shape = Radius.small,
    placeholderSeed: String? = null,
    placeholderIcon: ImageVector = AuraIcons.Note,
    contentDescription: String? = null,
    requestSize: Dp? = null,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
) {
    // One node per cover: the placeholder (tones and icon) is drawn behind the image, and only until
    // the image has loaded; reading [loaded] in the draw phase never recomposes the row.
    val tones = remember(placeholderSeed) { placeholderTones(placeholderSeed) }
    val iconPainter = rememberVectorPainter(placeholderIcon)
    val iconTint = AuraTheme.colors.textTertiary
    var loaded by remember(source) { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .drawBehind {
                if (loaded) return@drawBehind
                drawRect(Brush.linearGradient(listOf(tones.first, tones.second), end = Offset(size.width, size.height)))
                val edge = size.minDimension * 0.32f
                translate((size.width - edge) / 2, (size.height - edge) / 2) {
                    with(iconPainter) { draw(Size(edge, edge), colorFilter = ColorFilter.tint(iconTint)) }
                }
            },
    ) {
        if (source != null) {
            AsyncImage(
                model = rememberArtworkRequest(source, requestSize),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                filterQuality = filterQuality,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Pins the decode size when [requestSize] is given. Containers that animate their size (the
 * player cover grows from 48 dp to full width) would otherwise lock the request to the first,
 * smallest measurement and show a stretched thumbnail. Small list thumbnails skip the crossfade:
 * dozens of them fading at once while flinging cost more than they show.
 */
@Composable
fun rememberArtworkRequest(source: ArtworkSource, requestSize: Dp?): Any {
    // A cover that turns up later (found online) gets loaded without leaving the screen.
    val version = ArtworkUpdates.version
    val context = LocalContext.current
    val px = requestSize?.let { with(LocalDensity.current) { it.roundToPx() } }
    return remember(source, px, version) {
        ImageRequest.Builder(context)
            .data(source)
            .apply {
                if (version > 0) memoryCacheKeyExtra("v", version.toString())
                if (px != null) size(px) else crossfade(false)
            }
            .build()
    }
}

@Composable
internal fun ArtworkPlaceholder(seed: String?, icon: ImageVector) {
    val colors = AuraTheme.colors
    val tones = remember(seed) { placeholderTones(seed) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(tones.first, tones.second))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.fillMaxWidth(0.32f),
        )
    }
}

/** Circular variant used for artists. */
@Composable
fun ArtistArtwork(source: ArtworkSource?, name: String?, modifier: Modifier = Modifier) {
    Artwork(source, modifier, shape = CircleShape, placeholderSeed = name, placeholderIcon = AuraIcons.Artist)
}

private val placeholderPalette = listOf(
    Color(0xFF23262D) to Color(0xFF1B1D22),
    Color(0xFF26242E) to Color(0xFF1A1A20),
    Color(0xFF22272A) to Color(0xFF191C1E),
    Color(0xFF2A2626) to Color(0xFF1C1A1B),
    Color(0xFF242A28) to Color(0xFF1A1D1C),
)

private fun placeholderTones(seed: String?): Pair<Color, Color> =
    placeholderPalette[(seed?.hashCode() ?: 0).mod(placeholderPalette.size)]
