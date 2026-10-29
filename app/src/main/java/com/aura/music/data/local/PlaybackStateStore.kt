package com.aura.music.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Snapshot of the queue that allows playback to resume after the process dies. */
data class SavedPlayback(
    val trackIds: List<Long>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
)

/**
 * Split in two files: the (potentially large) queue is rewritten only when the queue changes,
 * while the frequently-updated position lives in a tiny file of its own.
 */
private val Context.queueStore: DataStore<Preferences> by preferencesDataStore(name = "playback_queue")
private val Context.positionStore: DataStore<Preferences> by preferencesDataStore(name = "playback_state")

@Singleton
class PlaybackStateStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun load(): SavedPlayback? {
        val ids = context.queueStore.data.first()[QUEUE]?.split(',')?.mapNotNull(String::toLongOrNull).orEmpty()
        if (ids.isEmpty()) return null
        val prefs = context.positionStore.data.first()
        return SavedPlayback(
            trackIds = ids,
            index = (prefs[INDEX] ?: 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs[POSITION] ?: 0L,
            shuffle = prefs[SHUFFLE] ?: false,
            repeatMode = prefs[REPEAT] ?: 0,
        )
    }

    suspend fun save(state: SavedPlayback) {
        saveQueue(state.trackIds)
        context.positionStore.edit {
            it[INDEX] = state.index
            it[POSITION] = state.positionMs
            it[SHUFFLE] = state.shuffle
            it[REPEAT] = state.repeatMode
        }
    }

    suspend fun saveQueue(trackIds: List<Long>) {
        context.queueStore.edit { it[QUEUE] = trackIds.joinToString(",") }
    }

    suspend fun savePosition(index: Int, positionMs: Long) {
        context.positionStore.edit {
            it[INDEX] = index
            it[POSITION] = positionMs
        }
    }

    suspend fun saveModes(shuffle: Boolean, repeatMode: Int) {
        context.positionStore.edit {
            it[SHUFFLE] = shuffle
            it[REPEAT] = repeatMode
        }
    }

    private companion object {
        val QUEUE = stringPreferencesKey("queue")
        val INDEX = intPreferencesKey("index")
        val POSITION = longPreferencesKey("position")
        val SHUFFLE = booleanPreferencesKey("shuffle")
        val REPEAT = intPreferencesKey("repeat")
    }
}
