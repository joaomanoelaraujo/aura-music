package com.aura.music.playback.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.aura.music.core.model.ArtworkSource
import com.aura.music.playback.queue.MediaItemMapper
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future

/**
 * Supplies artwork for the media notification, lock screen and connected devices using the
 * same offline pipeline as the UI, so every surface shows identical covers.
 */
@UnstableApi
class ArtworkBitmapLoader(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val scope: CoroutineScope,
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future(Dispatchers.IO) {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Undecodable artwork")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future(Dispatchers.IO) {
        context.contentResolver.loadThumbnail(uri, android.util.Size(SIZE, SIZE), null)
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val source = MediaItemMapper.artworkSource(metadata) ?: return super.loadBitmapFromMetadata(metadata)
        return scope.future { load(source) }
    }

    private suspend fun load(source: ArtworkSource): Bitmap {
        val request = ImageRequest.Builder(context)
            .data(source)
            .size(SIZE)
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request) as? SuccessResult ?: error("No artwork")
        return result.image.toBitmap()
    }

    private companion object {
        const val SIZE = 512
    }
}
