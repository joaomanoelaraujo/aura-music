package com.aura.music.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aura.music.core.model.ArtworkSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the home-screen widgets show. Written by the playback service, read by every widget. */
data class WidgetSnapshot(
    val hasMedia: Boolean,
    val title: String,
    val artist: String?,
    val isPlaying: Boolean,
    val albumId: Long,
    val trackUri: String,
    val positionMs: Long,
    val durationMs: Long,
    /** Wall-clock time of [positionMs], so progress can be estimated when the widget renders. */
    val capturedAt: Long,
) {
    val artwork: ArtworkSource? get() = if (hasMedia && trackUri.isNotEmpty()) ArtworkSource(albumId, trackUri) else null

    fun progressAt(now: Long): Float {
        if (durationMs <= 0) return 0f
        val elapsed = if (isPlaying) (now - capturedAt).coerceAtLeast(0) else 0
        return ((positionMs + elapsed).toFloat() / durationMs).coerceIn(0f, 1f)
    }

    companion object {
        val Empty = WidgetSnapshot(false, "", null, false, -1, "", 0, 0, 0)
    }
}

private val Context.widgetStore: DataStore<Preferences> by preferencesDataStore(name = "widget_state")

@Singleton
class WidgetStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val snapshot: Flow<WidgetSnapshot> = context.widgetStore.data.map { prefs ->
        WidgetSnapshot(
            hasMedia = prefs[HAS_MEDIA] ?: false,
            title = prefs[TITLE].orEmpty(),
            artist = prefs[ARTIST],
            isPlaying = prefs[IS_PLAYING] ?: false,
            albumId = prefs[ALBUM_ID] ?: -1,
            trackUri = prefs[TRACK_URI].orEmpty(),
            positionMs = prefs[POSITION] ?: 0,
            durationMs = prefs[DURATION] ?: 0,
            capturedAt = prefs[CAPTURED_AT] ?: 0,
        )
    }.distinctUntilChanged()

    suspend fun write(snapshot: WidgetSnapshot) {
        context.widgetStore.edit {
            it[HAS_MEDIA] = snapshot.hasMedia
            it[TITLE] = snapshot.title
            if (snapshot.artist != null) it[ARTIST] = snapshot.artist else it.remove(ARTIST)
            it[IS_PLAYING] = snapshot.isPlaying
            it[ALBUM_ID] = snapshot.albumId
            it[TRACK_URI] = snapshot.trackUri
            it[POSITION] = snapshot.positionMs
            it[DURATION] = snapshot.durationMs
            it[CAPTURED_AT] = snapshot.capturedAt
        }
    }

    private companion object {
        val HAS_MEDIA = booleanPreferencesKey("has_media")
        val TITLE = stringPreferencesKey("title")
        val ARTIST = stringPreferencesKey("artist")
        val IS_PLAYING = booleanPreferencesKey("is_playing")
        val ALBUM_ID = longPreferencesKey("album_id")
        val TRACK_URI = stringPreferencesKey("track_uri")
        val POSITION = longPreferencesKey("position")
        val DURATION = longPreferencesKey("duration")
        val CAPTURED_AT = longPreferencesKey("captured_at")
    }
}
