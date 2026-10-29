package com.aura.music.data.repository

import com.aura.music.data.local.db.SearchHistoryDao
import com.aura.music.data.local.db.SearchHistoryEntity
import com.aura.music.data.search.TextNormalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Recent searches, stored only on the device. */
@Singleton
class SearchHistoryRepository @Inject constructor(
    private val dao: SearchHistoryDao,
) {
    val recent: Flow<List<String>> = dao.observeRecent(MAX_SHOWN).map { list -> list.map { it.text } }

    suspend fun record(query: String) {
        val text = query.trim()
        val key = TextNormalizer.normalize(text)
        if (key.length < MIN_LENGTH) return
        val existing = dao.find(key)
        dao.upsert(SearchHistoryEntity(key, text, System.currentTimeMillis(), (existing?.uses ?: 0) + 1))
        dao.trimTo(MAX_KEPT)
    }

    suspend fun remove(query: String) = dao.delete(TextNormalizer.normalize(query))

    suspend fun clear() = dao.clear()

    private companion object {
        const val MAX_SHOWN = 12
        const val MAX_KEPT = 50
        const val MIN_LENGTH = 2
    }
}
