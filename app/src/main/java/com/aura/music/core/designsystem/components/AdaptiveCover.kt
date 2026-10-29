package com.aura.music.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import coil3.compose.AsyncImage
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.designsystem.theme.Radius
import com.aura.music.core.model.ArtworkSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class CoverQuality { High, Medium, Low }

/**
 * How a cover is shown in a large slot, decided from the pixels that actually exist.
 *
 * @property fit Crop for (near-)square art; Fit (letterboxed on the atmosphere) for other aspect ratios,
 *   so artwork is never deformed or heavily cut.
 * @property insetFraction share of the slot the image occupies; below 1 for low-resolution art,
 *   which is presented smaller instead of being blown up into a blurry full-width image.
 */
@Immutable
data class CoverPresentation(val quality: CoverQuality, val fit: ContentScale, val insetFraction: Float) {
    companion object {
        val Full = CoverPresentation(CoverQuality.High, ContentScale.Crop, 1f)
    }
}

object CoverPresentationPolicy {
    /** Aspect ratios within this tolerance of 1:1 are treated as square and cropped. */
    const val SQUARE_TOLERANCE = 0.04f

    /** Below this pixels-per-slot ratio, enlarging becomes visibly soft. */
    const val LOW_RESOLUTION_RATIO = 0.55f

    /** Low-resolution art is enlarged at most this much: honest detail, no invented sharpness. */
    const val MAX_UPSCALE = 2f

    /** Even tiny art keeps a dignified presence in the slot. */
    const val MIN_INSET_FRACTION = 0.42f

    fun decide(imageWidth: Int, imageHeight: Int, slotPx: Int): CoverPresentation {
        if (imageWidth <= 0 || imageHeight <= 0 || slotPx <= 0) return CoverPresentation.Full
        val aspect = imageWidth.toFloat() / imageHeight
        val isSquare = abs(aspect - 1f) <= SQUARE_TOLERANCE
        // Crop fills the slot with the short side; Fit fills it with the long side.
        val fillingSide = if (isSquare) min(imageWidth, imageHeight) else max(imageWidth, imageHeight)
        val ratio = fillingSide.toFloat() / slotPx
        val quality = when {
            ratio >= 1f -> CoverQuality.High
            ratio >= LOW_RESOLUTION_RATIO -> CoverQuality.Medium
            else -> CoverQuality.Low
        }
        val inset = if (quality == CoverQuality.Low) (ratio * MAX_UPSCALE).coerceIn(MIN_INSET_FRACTION, 1f) else 1f
        return CoverPresentation(quality, if (isSquare) ContentScale.Crop else ContentScale.Fit, inset)
    }
}

/**
 * Large cover that adapts to the real resolution of the artwork.
 *
 * [expansion] (0..1) blends from a plain thumbnail (mini-player) to the adaptive presentation, so
 * the cover can morph continuously. The atmosphere [tint] fills any space the image doesn't cover.
 */
@Composable
fun AdaptiveCover(
    source: ArtworkSource,
    slot: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    expansion: Float = 1f,
    shape: Shape = Radius.xLarge,
    placeholderSeed: String? = null,
) {
    val slotPx = with(LocalDensity.current) { slot.roundToPx() }
    var imageSize by remember(source) { mutableStateOf<IntSize?>(null) }
    val presentation = imageSize?.let { CoverPresentationPolicy.decide(it.width, it.height, slotPx) } ?: CoverPresentation.Full
    val inset = lerp(1f, presentation.insetFraction, expansion)
    val showsTile = expansion > 0f && (presentation.insetFraction < 1f || presentation.fit == ContentScale.Fit)

    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (imageSize == null) ArtworkPlaceholder(placeholderSeed, AuraIcons.Note)
        if (showsTile) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = expansion }
                    .background(tint),
            )
        }
        AsyncImage(
            model = rememberArtworkRequest(source, slot),
            contentDescription = null,
            contentScale = if (expansion < 0.5f) ContentScale.Crop else presentation.fit,
            // One high-quality scaling step from the decoded size to the slot; no extra passes.
            filterQuality = FilterQuality.High,
            onSuccess = { imageSize = IntSize(it.result.image.width, it.result.image.height) },
            modifier = Modifier
                .fillMaxSize(inset)
                .clip(if (inset < 1f) Radius.small else RectangleShape),
        )
    }
}
