package com.aura.music.core.image

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import com.aura.music.BuildConfig
import com.aura.music.core.model.ArtworkSource
import com.aura.music.data.metadata.OnlineCovers
import java.util.concurrent.ConcurrentHashMap
import okio.Buffer

/**
 * Loads album artwork offline, choosing the source by the size actually needed:
 *
 * - Small and medium targets (lists, grids) use MediaStore thumbnails: fast and cached by the system.
 * - Large targets (Now Playing, album hero, notification) use the original picture embedded in the
 *   audio file. The raw bytes are handed to Coil, which decodes them once, downsampled to the
 *   target with proper filtering and aspect ratio preserved — never a small thumbnail stretched up.
 *
 * Albums without any artwork are remembered to avoid repeated disk work while scrolling.
 */
class ArtworkFetcher(
    private val source: ArtworkSource,
    private val options: Options,
    private val context: Context,
    private val missing: MutableSet<Long>,
) : Fetcher {

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun fetch(): FetchResult? {
        if (source.albumId in missing) throw NoArtworkException(source.albumId)
        sidecarCover()?.let { return it }
        onlineCover()?.let { return it }
        val edge = bucketed(targetPx(options))
        if (edge > THUMBNAIL_LIMIT) embeddedOriginal()?.let { return it }
        val thumbnail = loadThumbnail(albumUri(source.albumId), edge) ?: loadThumbnail(source.trackUri.toUri(), edge)
        if (thumbnail != null) {
            debugLog { "thumbnail ${thumbnail.width}x${thumbnail.height} for target $edge (album ${source.albumId})" }
            return ImageFetchResult(image = thumbnail.asImage(), isSampled = true, dataSource = DataSource.DISK)
        }
        // Some files carry art the system thumbnailer can't read; try the original before giving up.
        if (edge <= THUMBNAIL_LIMIT) embeddedOriginal()?.let { return it }
        missing += source.albumId
        throw NoArtworkException(source.albumId)
    }

    /** A cover found online for an album that had none (see MetadataRepository). */
    private fun onlineCover(): FetchResult? {
        if (source.albumId <= 0) return null
        val cover = OnlineCovers.file(context, source.albumId).takeIf { it.isFile } ?: return null
        return SourceFetchResult(
            source = ImageSource(Buffer().write(cover.readBytes()), options.fileSystem),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    /** A song received in an AURA Together jam carries its cover as `<file>.jpg` beside it. */
    private fun sidecarCover(): FetchResult? {
        if (!source.trackUri.startsWith("file:")) return null
        val cover = java.io.File(source.trackUri.toUri().path + ".jpg").takeIf { it.isFile } ?: return null
        return SourceFetchResult(
            source = ImageSource(Buffer().write(cover.readBytes()), options.fileSystem),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    /** The full-resolution picture stored inside the audio file, or null when there is none. */
    private fun embeddedOriginal(): FetchResult? {
        val bytes = readEmbeddedPicture(source.trackUri.toUri()) ?: return null
        debugLog { "embedded original (${bytes.size / 1024} KB) for album ${source.albumId}" }
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private fun readEmbeddedPicture(uri: Uri): ByteArray? {
        if (source.trackUri.isEmpty()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun loadThumbnail(uri: Uri, edge: Int): Bitmap? =
        runCatching { resolver.loadThumbnail(uri, Size(edge, edge), null) }.getOrNull()

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    class Factory(private val context: Context) : Fetcher.Factory<ArtworkSource> {
        private val missing: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        override fun create(data: ArtworkSource, options: Options, imageLoader: ImageLoader): Fetcher =
            ArtworkFetcher(data, options, context, missing)

        /** Forget negative results, e.g. after the library is rescanned. */
        fun clearMissing() = missing.clear()
    }

    /** Memory-cache key shared by every track of the same album at the same size bucket. */
    class Key : Keyer<ArtworkSource> {
        override fun key(data: ArtworkSource, options: Options): String =
            "album:${data.albumId}:${bucketed(targetPx(options))}"
    }

    class NoArtworkException(albumId: Long) : IllegalStateException("No artwork for album $albumId")

    companion object {
        private const val TAG = "ArtworkFetcher"
        private const val DEFAULT_SIZE = 512

        /** Above this edge the system thumbnail is not trusted for quality; the original is used. */
        const val THUMBNAIL_LIMIT = 512
        private val BUCKETS = intArrayOf(128, 256, 512, 1024, 1536)

        /** Rounds requested sizes up to a few buckets so the same album is decoded only a few times. */
        fun bucketed(px: Int): Int = BUCKETS.firstOrNull { px <= it } ?: BUCKETS.last()

        private fun targetPx(options: Options): Int = maxOf(
            options.size.width.pxOrElse { DEFAULT_SIZE },
            options.size.height.pxOrElse { DEFAULT_SIZE },
        )

        fun albumUri(albumId: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
    }
}
