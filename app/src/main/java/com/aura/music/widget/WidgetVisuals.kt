package com.aura.music.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.aura.music.core.designsystem.atmosphere.AtmosphereColorMath
import com.aura.music.core.designsystem.atmosphere.AtmosphereColors
import com.aura.music.core.designsystem.atmosphere.AtmosphereEngine
import com.aura.music.core.model.ArtworkSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetStateStore(): WidgetStateStore
    fun atmosphereEngine(): AtmosphereEngine
    fun imageLoader(): ImageLoader
}

internal fun Context.widgetEntryPoint(): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

/** Everything a widget draws that depends on the artwork. */
internal data class WidgetVisuals(
    val cover: Bitmap?,
    val colors: AtmosphereColors,
    /** Soft atmospheric glow drawn behind large layouts; null for compact ones. */
    val glow: Bitmap?,
) {
    companion object {
        val Neutral = WidgetVisuals(cover = null, colors = AtmosphereColorMath.Neutral, glow = null)
    }
}

/**
 * Loads widget artwork and palette off the main thread. Bitmaps are kept small (RemoteViews
 * has a memory budget) and rounded here, because rounded clipping of images is not available
 * in widgets on every Android version.
 */
internal object WidgetVisualsLoader {

    suspend fun load(
        context: Context,
        source: ArtworkSource?,
        coverPx: Int,
        cornerFraction: Float,
        withGlow: Boolean,
    ): WidgetVisuals {
        if (source == null) return WidgetVisuals.Neutral
        val deps = context.widgetEntryPoint()
        val colors = runCatching { deps.atmosphereEngine().colorsFor(source) }.getOrDefault(AtmosphereColorMath.Neutral)
        val request = ImageRequest.Builder(context).data(source).size(coverPx).allowHardware(false).build()
        val bitmap = (deps.imageLoader().execute(request) as? SuccessResult)?.image?.toBitmap()
        return withContext(Dispatchers.Default) {
            WidgetVisuals(
                cover = bitmap?.let { roundedSquare(it, coverPx, cornerFraction) },
                colors = colors,
                glow = if (withGlow) glow(colors) else null,
            )
        }
    }

    /** Centre-crops to a square of [size] px and rounds the corners. */
    private fun roundedSquare(source: Bitmap, size: Int, cornerFraction: Float): Bitmap {
        val edge = minOf(source.width, source.height)
        val output = createBitmap(size, size)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            val scale = size.toFloat() / edge
            val matrix = android.graphics.Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(-(source.width - edge) / 2f * scale, -(source.height - edge) / 2f * scale)
            setLocalMatrix(matrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val radius = size * cornerFraction
        Canvas(output).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        return output
    }

    /** A small radial glow stretched across the widget; cheap and smooth. */
    private fun glow(colors: AtmosphereColors): Bitmap {
        val size = GLOW_SIZE
        val output = createBitmap(size, size)
        val center = ColorUtils.setAlphaComponent(colors.glow, 150)
        val edge = ColorUtils.setAlphaComponent(colors.glow, 0)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(size * 0.3f, size * 0.2f, size * 0.9f, center, edge, Shader.TileMode.CLAMP)
        }
        Canvas(output).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return output
    }

    private const val GLOW_SIZE = 96
}
