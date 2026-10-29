package com.aura.music.data.metadata

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import com.aura.music.core.image.ArtworkFetcher
import com.aura.music.core.image.ArtworkUpdates
import com.aura.music.core.model.Track
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.local.db.CoverLookupEntity
import com.aura.music.data.local.db.MetadataDao
import com.aura.music.data.local.db.MetadataOverrideEntity
import com.aura.music.data.lyrics.online.OnlineLyrics
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** Where covers found online are kept, one per album; read by the artwork loader. */
object OnlineCovers {
    fun file(context: Context, albumId: Long) = File(File(context.filesDir, "covers"), "album_$albumId.jpg")
}

/**
 * Covers and song details from MusicBrainz: covers for albums that have none (automatic, when
 * allowed and online), and corrected titles and artists the user picks for badly tagged files
 * (stored in the app; files are never modified).
 */
@Singleton
class MetadataRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: MetadataDao,
    private val settings: SettingsRepository,
    private val network: OnlineLyrics,
    private val artwork: ArtworkFetcher.Factory,
) {
    private val client = MusicBrainzClient()

    sealed interface SearchResult {
        data class Found(val recordings: List<MbRecording>) : SearchResult
        data object Offline : SearchResult
        data object Failed : SearchResult
    }

    /** Finds and stores a cover for the track's album when it has none. True when one was added. */
    suspend fun fetchCoverIfMissing(track: Track): Boolean = withContext(Dispatchers.IO) {
        val album = track.album?.takeUnless { it.isBlank() || it == UNKNOWN } ?: return@withContext false
        if (track.albumId <= 0 || !settings.settings.first().onlineCovers || !network.isOnline()) return@withContext false
        val target = OnlineCovers.file(context, track.albumId)
        if (target.exists()) return@withContext false
        val lookup = dao.coverLookup(track.albumId)
        if (lookup != null && System.currentTimeMillis() - lookup.checkedAt < RETRY_MS) return@withContext false
        if (hasLocalCover(track)) return@withContext false
        try {
            val artist = track.albumArtist?.takeUnless { it == UNKNOWN } ?: track.artist?.takeUnless { it == UNKNOWN }
            val release = MetadataMatcher.album(album, artist, client.searchReleases(album, artist))
            val bytes = release?.let { client.frontCover(it) }
            if (bytes != null) save(track.albumId, bytes)
            dao.upsertCoverLookup(CoverLookupEntity(track.albumId, found = bytes != null, checkedAt = System.currentTimeMillis()))
            bytes != null
        } catch (e: IOException) {
            Log.i(TAG, "Cover lookup failed: ${e.message}")
            false
        } catch (e: SerializationException) {
            Log.w(TAG, "Unexpected MusicBrainz answer", e)
            false
        }
    }

    /** Songs on MusicBrainz for the user to pick the right details from. */
    suspend fun search(track: Track, text: String?): SearchResult {
        if (!network.isOnline()) return SearchResult.Offline
        val query = text?.takeIf(String::isNotBlank) ?: defaultQuery(track)
        return try {
            SearchResult.Found(client.searchRecordings(query).sortedByDescending { it.score })
        } catch (e: IOException) {
            SearchResult.Failed
        } catch (e: SerializationException) {
            SearchResult.Failed
        }
    }

    fun defaultQuery(track: Track): String = listOfNotNull(track.artist?.takeUnless { it == UNKNOWN }, track.title).joinToString(" ")

    /** Uses [recording]'s title and artist for the song, and its release's cover if the album has none. */
    suspend fun apply(track: Track, recording: MbRecording) = withContext(Dispatchers.IO) {
        dao.upsertOverride(MetadataOverrideEntity(track.id, recording.title, recording.artist.ifBlank { null }, System.currentTimeMillis()))
        val release = recording.releases.firstOrNull() ?: return@withContext
        if (track.albumId > 0 && !OnlineCovers.file(context, track.albumId).exists() && !hasLocalCover(track)) {
            runCatching { client.frontCover(release) }.getOrNull()?.let { save(track.albumId, it) }
        }
    }

    /** Back to the file's own tags. */
    suspend fun reset(trackId: Long) = dao.deleteOverride(trackId)

    private fun save(albumId: Long, bytes: ByteArray) {
        val target = OnlineCovers.file(context, albumId)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
        // The album was remembered as having no artwork: let it be loaded again, on screen too.
        artwork.clearMissing()
        ArtworkUpdates.bump()
    }

    private fun hasLocalCover(track: Track): Boolean {
        val thumbnail = runCatching {
            context.contentResolver.loadThumbnail(ArtworkFetcher.albumUri(track.albumId), Size(64, 64), null)
        }.getOrNull()
        if (thumbnail != null) return true
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, track.uri.toUri())
            retriever.embeddedPicture != null
        } catch (e: RuntimeException) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val TAG = "Metadata"
        const val UNKNOWN = "<unknown>"

        /** Albums without a cover online are asked again after a month. */
        const val RETRY_MS = 30L * 24 * 60 * 60 * 1000
    }
}
