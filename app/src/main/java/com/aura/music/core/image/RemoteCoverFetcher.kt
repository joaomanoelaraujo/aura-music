package com.aura.music.core.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * Cover image of an online catalog result (AURA Discover). Kept apart from [ArtworkFetcher], so
 * the library's artwork pipeline never touches the network. HTTPS only, size-capped.
 */
data class RemoteCover(val url: String)

class RemoteCoverFetcher(private val cover: RemoteCover, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        require(cover.url.startsWith("https://")) { "Only HTTPS covers are loaded" }
        val connection = (URL(cover.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "AURA-Music/3.0 (Android)")
        }
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            val buffer = Buffer()
            connection.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    check(buffer.size <= MAX_BYTES) { "Cover too large" }
                }
            }
            SourceFetchResult(ImageSource(buffer, options.fileSystem), connection.contentType, DataSource.NETWORK)
        } finally {
            connection.disconnect()
        }
    }

    class Factory : Fetcher.Factory<RemoteCover> {
        override fun create(data: RemoteCover, options: Options, imageLoader: ImageLoader): Fetcher = RemoteCoverFetcher(data, options)
    }

    class Key : Keyer<RemoteCover> {
        override fun key(data: RemoteCover, options: Options): String = "remote:${data.url}"
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_BYTES = 3L * 1024 * 1024
    }
}
