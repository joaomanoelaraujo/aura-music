package com.aura.music.playback.stream

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.aura.music.playback.queue.MediaItemMapper
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Reads a song that is still arriving from another phone in an AURA Together jam, waiting for
 * bytes that haven't come yet, so playback starts after the first chunk instead of the whole file.
 */
@UnstableApi
class GrowingFileDataSource : BaseDataSource(/* isNetwork = */ false) {
    private var file: File? = null
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val target = File(dataSpec.uri.path ?: throw IOException("No path"))
        val total = GrowingFiles.awaitTotal(target, TIMEOUT_MS)
        if (dataSpec.position > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        file = target
        uri = dataSpec.uri
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, total - position) else total - position
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val target = file ?: throw IOException("Not open")
        val available = GrowingFiles.awaitAvailable(target, position, TIMEOUT_MS)
        if (available == 0L) return C.RESULT_END_OF_INPUT
        // Opened once: the part file keeps working through its rename to the final name.
        val input = raf ?: GrowingFiles.open(target).also { raf = it }
        input.seek(position)
        val n = input.read(buffer, offset, minOf(length.toLong(), available, bytesRemaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        file = null
        runCatching { raf?.close() }
        raf = null
        transferEnded()
    }

    private companion object {
        /** Longest wait without any progress before playback reports an error. */
        const val TIMEOUT_MS = 30_000L
    }
}

/** The player's data source: jam songs being received go through [GrowingFileDataSource]. */
@UnstableApi
class StreamAwareDataSource(private val context: Context) : DataSource {
    private val default = DefaultDataSource(context, /* allowCrossProtocolRedirects = */ false)
    private val growing = GrowingFileDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        growing.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path
        val streamDir = File(context.cacheDir, MediaItemMapper.STREAM_DIR).path + File.separator
        val source = if (dataSpec.uri.scheme == "file" && path != null && path.startsWith(streamDir)) growing else default
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = active?.read(buffer, offset, length) ?: throw IOException("Not open")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = StreamAwareDataSource(context)
    }
}
