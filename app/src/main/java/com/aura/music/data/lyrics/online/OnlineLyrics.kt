package com.aura.music.data.lyrics.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.aura.music.core.model.Track
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.local.db.LyricsDao
import com.aura.music.data.local.db.OnlineLyricsEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException

/**
 * Finds lyrics on the internet for songs that have none, and stores them with the song so they
 * keep working offline. Runs only with a working connection and only while the user allows it.
 */
@Singleton
class OnlineLyrics @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: LyricsDao,
    private val settings: SettingsRepository,
) {
    private val client = LrclibClient()
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    sealed interface SearchResult {
        data class Found(val records: List<LrclibRecord>) : SearchResult
        data object Offline : SearchResult
        data object Failed : SearchResult
    }

    /** True when there is a connection that actually reaches the internet. */
    fun isOnline(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Looks the song up when allowed and useful: enabled in settings, online, not found recently,
     * not removed by the user. Returns true when lyrics were found and saved.
     */
    suspend fun fetchIfNeeded(track: Track): Boolean {
        if (!settings.settings.first().onlineLyrics || !isOnline()) return false
        retryAfterSmarterSearch()
        val known = dao.getOnline(track.id)
        if (known != null && (known.content != null || known.userRemoved || System.currentTimeMillis() - known.checkedAt < RETRY_NOT_FOUND_MS)) return false
        if (!inFlight.add(track.id)) return false
        return try {
            val query = queryOf(track)
            val record = LyricsLookup.find(query.title, query.artist, query.album, query.durationMs, client)
            save(track.id, record)
            record != null
        } catch (e: IOException) {
            // Network trouble isn't "not found": try again another time.
            Log.i(TAG, "Lyrics lookup failed for ${track.id}: ${e.message}")
            false
        } catch (e: SerializationException) {
            Log.w(TAG, "Unexpected LRCLIB answer", e)
            false
        } finally {
            inFlight.remove(track.id)
        }
    }

    /** Results for the user to choose from; [text] overrides the song's own title and artist. */
    suspend fun search(track: Track, text: String? = null): SearchResult {
        if (!isOnline()) return SearchResult.Offline
        val query = queryOf(track)
        val guesses = SongNames.guesses(query.title, query.artist).map { LyricsQuery(it.title, it.artist, query.album, query.durationMs) }
        return try {
            val records = client.search(text?.takeIf(String::isNotBlank) ?: LyricsLookup.suggestedQuery(query.title, query.artist))
                .filter(LrclibRecord::hasLyrics)
                // Likely matches first, then synced ones, then by how close the duration is.
                .sortedWith(
                    compareByDescending<LrclibRecord> { r -> guesses.any { LyricsMatcher.isSameSong(it, r) } }
                        .thenBy { it.syncedLyrics.isNullOrBlank() }
                        .thenBy { kotlin.math.abs(it.durationMs - query.durationMs) },
                )
            SearchResult.Found(records)
        } catch (e: IOException) {
            SearchResult.Failed
        } catch (e: SerializationException) {
            SearchResult.Failed
        }
    }

    /** The user picked [record] for this song. */
    suspend fun choose(trackId: Long, record: LrclibRecord) = save(trackId, record)

    /** The user doesn't want these online lyrics: forget them and don't fetch again by itself. */
    suspend fun remove(trackId: Long) {
        dao.upsertOnline(OnlineLyricsEntity(trackId, content = null, synced = false, providerId = null, checkedAt = System.currentTimeMillis(), userRemoved = true))
    }

    private suspend fun save(trackId: Long, record: LrclibRecord?) {
        dao.upsertOnline(
            OnlineLyricsEntity(
                trackId = trackId,
                content = record?.bestText,
                synced = !record?.syncedLyrics.isNullOrBlank(),
                providerId = record?.id,
                checkedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Songs the previous, simpler search couldn't find get another chance right away with the
     * smarter one, instead of waiting a week. Runs once per search version.
     */
    private suspend fun retryAfterSmarterSearch() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SEARCH_VERSION, 1) >= SEARCH_VERSION) return
        dao.forgetNotFound()
        prefs.edit().putInt(KEY_SEARCH_VERSION, SEARCH_VERSION).apply()
    }

    private fun queryOf(track: Track) = LyricsQuery(
        title = track.title,
        artist = track.artist?.takeUnless { it.isBlank() || it == MEDIASTORE_UNKNOWN },
        album = track.album?.takeUnless { it.isBlank() || it == MEDIASTORE_UNKNOWN },
        durationMs = track.durationMs,
    )

    private companion object {
        const val TAG = "OnlineLyrics"
        const val MEDIASTORE_UNKNOWN = "<unknown>"
        const val PREFS = "online_lyrics"
        const val KEY_SEARCH_VERSION = "search_version"

        /** Bump when the search gets better, so earlier misses are tried again. */
        const val SEARCH_VERSION = 2

        /** Songs not found are asked again after a week: LRCLIB keeps growing. */
        const val RETRY_NOT_FOUND_MS = 7L * 24 * 60 * 60 * 1000
    }
}
