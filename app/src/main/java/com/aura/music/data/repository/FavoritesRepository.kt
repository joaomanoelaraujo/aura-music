package com.aura.music.data.repository

import com.aura.music.core.common.ApplicationScope
import com.aura.music.core.model.Track
import com.aura.music.data.local.db.FavoriteDao
import com.aura.music.data.local.db.FavoriteEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class FavoritesRepository @Inject constructor(
    private val dao: FavoriteDao,
    library: LibraryRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    val favoriteIds: StateFlow<Set<Long>> = dao.observeFavorites()
        .map { list -> list.mapTo(LinkedHashSet()) { it.trackId } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Favorites still present on the device, most recently liked first. */
    val favoriteTracks: Flow<List<Track>> = combine(dao.observeFavorites(), library.index) { favorites, index ->
        index.tracks(favorites.map { it.trackId })
    }

    suspend fun toggle(trackId: Long): Boolean {
        val isFavorite = dao.isFavorite(trackId)
        if (isFavorite) dao.delete(trackId) else dao.insert(FavoriteEntity(trackId, System.currentTimeMillis()))
        return !isFavorite
    }
}
