package com.aura.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.aura.desktop.library.ArtworkStore
import com.aura.music.core.designsystem.atmosphere.AtmosphereColorMath
import com.aura.music.core.designsystem.atmosphere.AtmosphereColors
import com.aura.music.core.designsystem.atmosphere.Swatch
import com.aura.music.core.designsystem.icons.AuraIcons
import com.aura.music.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loads covers off the UI thread and keeps a bounded cache of bitmaps already scaled to the size
 * they're shown at, so long lists never hold full-size pictures in memory.
 */
class ArtworkLoader(private val store: ArtworkStore, private val maxEntries: Int = 400) {

    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > maxEntries
    }
    private val missing = HashSet<String>()

    fun cached(track: Track, sizePx: Int): ImageBitmap? = synchronized(cache) { cache[key(track, sizePx)] }

    suspend fun load(track: Track, sizePx: Int): ImageBitmap? {
        val key = key(track, sizePx)
        synchronized(cache) {
            cache[key]?.let { return it }
            if (key in missing) return null
        }
        val bitmap = withContext(Dispatchers.IO) {
            val file = store.fileFor(track) ?: return@withContext null
            runCatching { decodeScaled(file, sizePx) }.getOrNull()
        }
        synchronized(cache) { if (bitmap != null) cache[key] = bitmap else missing += key }
        return bitmap
    }

    /** Adaptive Atmosphere colours for [track]'s cover (null when it has none). */
    suspend fun atmosphere(track: Track): AtmosphereColors? = withContext(Dispatchers.IO) {
        val file = store.fileFor(track) ?: return@withContext null
        runCatching { AtmosphereColorMath.derive(swatches(file)) }.getOrNull()
    }

    fun invalidate() = synchronized(cache) {
        cache.clear()
        missing.clear()
    }

    private fun key(track: Track, sizePx: Int) = "${track.albumId}:${track.folder}:$sizePx"

    private fun decodeScaled(file: Path, sizePx: Int): ImageBitmap {
        Image.makeFromEncoded(Files.readAllBytes(file)).use { image ->
            val scale = minOf(1f, sizePx.toFloat() / max(image.width, image.height))
            val w = (image.width * scale).roundToInt().coerceAtLeast(1)
            val h = (image.height * scale).roundToInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(w, h).use { surface ->
                surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.MITCHELL, null, true)
                return surface.makeImageSnapshot().toComposeImageBitmap()
            }
        }
    }

    /** Coarse colour histogram of a 32×32 thumbnail: the input the shared colour math expects. */
    private fun swatches(file: Path): List<Swatch> {
        Image.makeFromEncoded(Files.readAllBytes(file)).use { image ->
            val side = 32
            val info = ImageInfo(side, side, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            Surface.makeRaster(info).use { surface ->
                surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(side.toFloat(), side.toFloat()), SamplingMode.LINEAR, null, true)
                val pixels = surface.makeImageSnapshot().use { it.readPixelsRgba(info) }
                val buckets = HashMap<Int, IntArray>()
                for (i in 0 until side * side) {
                    val r = pixels[i * 4].toInt() and 0xFF
                    val g = pixels[i * 4 + 1].toInt() and 0xFF
                    val b = pixels[i * 4 + 2].toInt() and 0xFF
                    val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
                    val acc = buckets.getOrPut(bucket) { IntArray(4) }
                    acc[0] += r; acc[1] += g; acc[2] += b; acc[3]++
                }
                return buckets.values.sortedByDescending { it[3] }.take(12).map { acc ->
                    val n = acc[3]
                    Swatch(rgb = (0xFF shl 24) or ((acc[0] / n) shl 16) or ((acc[1] / n) shl 8) or (acc[2] / n), population = n)
                }
            }
        }
    }

    private fun Image.readPixelsRgba(info: ImageInfo): ByteArray {
        val bitmap = org.jetbrains.skia.Bitmap()
        bitmap.allocPixels(info)
        readPixels(bitmap, 0, 0)
        return bitmap.readPixels(info, info.minRowBytes, 0, 0) ?: ByteArray(info.width * info.height * 4)
    }
}

/** A cover, or AURA's placeholder while loading / when there is none. */
@Composable
fun Cover(track: Track?, loader: ArtworkLoader, size: Dp, corner: Dp, modifier: Modifier = Modifier, sizePx: Int = 96) {
    val shape = RoundedCornerShape(corner)
    val bitmap by produceState(track?.let { loader.cached(it, sizePx) }, track?.albumId, track?.folder, sizePx) {
        value = track?.let { loader.load(it, sizePx) }
    }
    Box(modifier.size(size).clip(shape)) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val c = Aura.colors
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.surfaceHighest, c.surfaceElevated))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AuraIcons.Note, null, tint = c.textTertiary, modifier = Modifier.padding(size / 4).fillMaxSize())
            }
        }
    }
}
