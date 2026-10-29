package com.aura.music.core.designsystem.atmosphere

import android.content.Context
import android.util.LruCache
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.aura.music.core.model.ArtworkSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extracts and caches atmosphere palettes. All decoding and quantization run off the main thread. */
@Singleton
class AtmosphereEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
) {
    private val cache = LruCache<Long, AtmosphereColors>(CACHE_SIZE)

    fun cached(source: ArtworkSource): AtmosphereColors? = cache.get(source.albumId)

    /** Forgets every palette, so they are extracted again from the current artwork. */
    fun clear() = cache.evictAll()

    /** Returns the palette for [source], or [AtmosphereColorMath.Neutral] when it has no artwork. */
    suspend fun colorsFor(source: ArtworkSource): AtmosphereColors {
        cache.get(source.albumId)?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(source)
            .size(SAMPLE_SIZE)
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return AtmosphereColorMath.Neutral
        val colors = withContext(Dispatchers.Default) {
            val palette = Palette.from(result.image.toBitmap()).maximumColorCount(PALETTE_COLORS).generate()
            AtmosphereColorMath.derive(palette.swatches.map { Swatch(it.rgb, it.population) })
        }
        cache.put(source.albumId, colors)
        return colors
    }

    private companion object {
        const val CACHE_SIZE = 96
        const val SAMPLE_SIZE = 128
        const val PALETTE_COLORS = 16
    }
}
