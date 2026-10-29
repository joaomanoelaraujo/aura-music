package com.aura.music.data.discover

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.core.net.toUri
import com.aura.music.core.common.ApplicationScope
import com.aura.music.data.local.db.DownloadDao
import com.aura.music.data.local.db.DownloadEntity
import com.aura.music.data.repository.LibraryRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadStatus { Queued, Running, WaitingNetwork, Done, Failed, Cancelled }

data class DownloadUi(
    val id: Long,
    val title: String,
    val artist: String?,
    val formatLabel: String,
    val license: String?,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val error: String?,
) {
    val progress: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * Downloads chosen in AURA Discover. Transfers run in Android's DownloadManager (resumes after
 * network drops, survives the app closing, shows its own notification). Files go to
 * Music/AURA/ so MediaStore — and therefore the library — picks them up automatically.
 */
@Singleton
class DownloadRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    private val library: LibraryRepository,
) {
    private val manager: DownloadManager get() = context.getSystemService(DownloadManager::class.java)

    /** History plus live progress, polled from DownloadManager only while something is active. */
    val downloads: Flow<List<DownloadUi>> = combine(dao.observeAll(), progressTicks()) { entities, progress ->
        entities.map { e ->
            val live = progress[e.systemId]
            val status = live?.status ?: DownloadStatus.valueOf(e.status)
            DownloadUi(
                id = e.id,
                title = e.title,
                artist = e.artist,
                formatLabel = e.formatLabel,
                license = e.license,
                status = status,
                downloadedBytes = live?.downloaded ?: if (status == DownloadStatus.Done) e.sizeBytes ?: 0 else 0,
                totalBytes = live?.total ?: e.sizeBytes,
                error = e.error,
            )
        }
    }

    sealed interface EnqueueResult {
        data object Started : EnqueueResult
        data object NoSpace : EnqueueResult
        data object TooLarge : EnqueueResult
        data object Insecure : EnqueueResult
    }

    suspend fun enqueue(item: DiscoverItem, track: DownloadableTrack, option: AudioOption): EnqueueResult {
        if (!option.url.startsWith("https://")) return EnqueueResult.Insecure
        val size = option.sizeBytes ?: 0
        if (size > DiscoverRepository.MAX_FILE_BYTES) return EnqueueResult.TooLarge
        if (!hasSpaceFor(size)) return EnqueueResult.NoSpace
        val title = track.title.ifBlank { item.title }
        val artist = item.artist
        val systemId = withContext(Dispatchers.IO) { manager.enqueue(request(option, title, artist)) }
        val now = System.currentTimeMillis()
        dao.insert(
            DownloadEntity(
                systemId = systemId,
                title = title,
                artist = artist,
                source = item.source.name,
                license = item.license,
                url = option.url,
                fileName = destinationName(option, artist),
                formatLabel = option.formatLabel,
                mimeType = option.mimeType,
                sizeBytes = option.sizeBytes,
                status = DownloadStatus.Queued.name,
                localUri = null,
                error = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return EnqueueResult.Started
    }

    suspend fun cancel(id: Long) {
        val e = dao.get(id) ?: return
        withContext(Dispatchers.IO) { manager.remove(e.systemId) }
        dao.updateStatus(id, DownloadStatus.Cancelled.name, null, null, System.currentTimeMillis())
    }

    suspend fun retry(id: Long): EnqueueResult {
        val e = dao.get(id) ?: return EnqueueResult.Started
        if (!hasSpaceFor(e.sizeBytes ?: 0)) return EnqueueResult.NoSpace
        val option = AudioOption(e.formatLabel, e.fileName.substringAfterLast('.'), null, false, true, e.sizeBytes, e.url, e.fileName.substringAfterLast('/'), e.mimeType)
        val systemId = withContext(Dispatchers.IO) { manager.enqueue(request(option, e.title, e.artist)) }
        dao.requeue(id, systemId, System.currentTimeMillis())
        return EnqueueResult.Started
    }

    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun clearFinished() = dao.clearFinished()

    /** Called when DownloadManager reports completion, and on start for transfers finished while closed. */
    suspend fun reconcile(systemId: Long) {
        val entity = dao.bySystemId(systemId) ?: return
        val snapshot = withContext(Dispatchers.IO) { query(systemId) }
        val now = System.currentTimeMillis()
        when {
            snapshot == null -> dao.updateStatus(entity.id, DownloadStatus.Cancelled.name, null, null, now)
            snapshot.status == DownloadStatus.Done -> {
                // The server may have answered with something that isn't audio despite the link.
                val type = snapshot.mediaType
                if (type != null && !type.startsWith("audio/") && type != "application/octet-stream" && !type.startsWith("binary/")) {
                    withContext(Dispatchers.IO) { manager.remove(systemId) }
                    dao.updateStatus(entity.id, DownloadStatus.Failed.name, null, "not_audio:$type", now)
                    return
                }
                dao.updateStatus(entity.id, DownloadStatus.Done.name, snapshot.localUri, null, now)
                snapshot.localUri?.toUri()?.path?.let { path ->
                    MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(entity.mimeType)) { _, _ -> library.refresh() }
                }
            }
            snapshot.status == DownloadStatus.Failed -> dao.updateStatus(entity.id, DownloadStatus.Failed.name, null, "reason:${snapshot.reason}", now)
            else -> Unit
        }
    }

    suspend fun reconcileActive() {
        dao.active().forEach { reconcile(it.systemId) }
    }

    private fun request(option: AudioOption, title: String, artist: String?) = DownloadManager.Request(option.url.toUri())
        .setTitle(title)
        .setDescription(listOfNotNull(artist, option.formatLabel).joinToString(" · "))
        .setMimeType(option.mimeType)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
        .addRequestHeader("User-Agent", "AURA-Music/3.0 (Android)")
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, destinationName(option, artist))

    /** Music/AURA/<Artist>/<file>, with characters that file systems reject replaced. */
    private fun destinationName(option: AudioOption, artist: String?): String {
        val folder = safe(artist ?: "AURA Discover")
        val name = safe(option.fileName).ifBlank { "audio.${option.extension}" }
        return "AURA${File.separator}$folder${File.separator}$name"
    }

    private fun safe(value: String) = value.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().take(120)

    private fun hasSpaceFor(bytes: Long): Boolean {
        val dir = Environment.getExternalStorageDirectory()
        val available = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        return available > bytes + SPACE_MARGIN
    }

    private data class Snapshot(val status: DownloadStatus, val downloaded: Long, val total: Long?, val localUri: String?, val mediaType: String?, val reason: Int)

    private fun query(systemId: Long): Snapshot? =
        manager.query(DownloadManager.Query().setFilterById(systemId))?.use { c -> if (c.moveToFirst()) c.snapshot() else null }

    private fun Cursor.snapshot(): Snapshot {
        val status = when (getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
            DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.Done
            DownloadManager.STATUS_FAILED -> DownloadStatus.Failed
            DownloadManager.STATUS_RUNNING -> DownloadStatus.Running
            DownloadManager.STATUS_PAUSED -> DownloadStatus.WaitingNetwork
            else -> DownloadStatus.Queued
        }
        return Snapshot(
            status = status,
            downloaded = getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
            total = getLong(getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 },
            localUri = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
            mediaType = getString(getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE))?.lowercase(),
            reason = getInt(getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
        )
    }

    private data class Live(val status: DownloadStatus, val downloaded: Long, val total: Long?)

    /** Emits live progress for active transfers; idles cheaply when nothing is downloading. */
    private fun progressTicks(): Flow<Map<Long, Live>> = flow {
        while (true) {
            val active = dao.active()
            val map = if (active.isEmpty()) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) {
                    active.mapNotNull { e ->
                        val s = runCatching { query(e.systemId) }.getOrNull() ?: return@mapNotNull null
                        if (s.status == DownloadStatus.Done || s.status == DownloadStatus.Failed) reconcile(e.systemId)
                        e.systemId to Live(s.status, s.downloaded, s.total)
                    }.toMap()
                }
            }
            emit(map)
            delay(if (active.isEmpty()) IDLE_MS else ACTIVE_MS)
        }
    }

    private companion object {
        const val SPACE_MARGIN = 64L * 1024 * 1024
        const val ACTIVE_MS = 700L
        const val IDLE_MS = 3_000L
    }
}

/** Receives DownloadManager's completion broadcast, even when AURA isn't open. */
@AndroidEntryPoint
class DownloadCompleteReceiver : BroadcastReceiver() {
    @Inject lateinit var downloads: DownloadRepository
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        scope.launch {
            try {
                downloads.reconcile(id)
            } catch (e: Exception) {
                Log.w("DownloadComplete", "Could not finish download $id", e)
            } finally {
                pending.finish()
            }
        }
    }
}
