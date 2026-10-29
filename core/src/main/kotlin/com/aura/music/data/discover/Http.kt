package com.aura.music.data.discover

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HeadInfo(val contentType: String?, val contentLength: Long?, val finalUrl: String, val fileName: String?)

/**
 * Minimal HTTPS client on [HttpURLConnection] (no extra dependency). Only HTTPS is accepted,
 * responses are size-capped and every call has connect and read timeouts.
 */
object Http {
    /** Identifies the app to the sites it talks to (set by each app at start-up). */
    @Volatile var userAgent = "AURA-Music/3.0"
    private const val TIMEOUT_MS = 12_000
    private const val MAX_BODY = 4 * 1024 * 1024

    suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val connection = open(url, "GET")
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw DiscoverException(DiscoverException.Reason.NotFound)
            if (code !in 200..299) throw DiscoverException(DiscoverException.Reason.Network)
            connection.inputStream.use { input ->
                val bytes = input.readNBytesCapped(MAX_BODY)
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            throw DiscoverException(DiscoverException.Reason.Network, e)
        } finally {
            connection.disconnect()
        }
    }

    /** Learns type and size of a remote file without downloading it (HEAD, following redirects). */
    suspend fun head(url: String): HeadInfo = withContext(Dispatchers.IO) {
        val connection = open(url, "HEAD")
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw DiscoverException(DiscoverException.Reason.NotFound)
            if (code !in 200..299) throw DiscoverException(DiscoverException.Reason.Network)
            val finalUrl = connection.url.toString()
            if (!finalUrl.startsWith("https://")) throw DiscoverException(DiscoverException.Reason.Insecure)
            HeadInfo(
                contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(),
                contentLength = connection.contentLengthLong.takeIf { it >= 0 },
                finalUrl = finalUrl,
                fileName = connection.getHeaderField("Content-Disposition")?.let(::fileNameFromDisposition),
            )
        } catch (e: IOException) {
            throw DiscoverException(DiscoverException.Reason.Network, e)
        } finally {
            connection.disconnect()
        }
    }

    fun open(url: String, method: String): HttpURLConnection {
        if (!url.startsWith("https://")) throw DiscoverException(DiscoverException.Reason.Insecure)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
        }
    }

    internal fun fileNameFromDisposition(header: String): String? =
        Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()

    private fun java.io.InputStream.readNBytesCapped(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw DiscoverException(DiscoverException.Reason.TooLarge)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
