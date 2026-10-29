package com.aura.music.data.repository

import com.aura.music.core.model.AudioKind
import com.aura.music.data.local.db.AudioOverrideEntity
import com.aura.music.data.local.db.ClassificationDao
import com.aura.music.data.local.db.FolderRuleEntity
import com.aura.music.data.media.ClassificationOverride
import com.aura.music.data.media.FolderRule
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persists the user's corrections to the automatic music/audio classification. Files are never touched. */
@Singleton
class ClassificationRepository @Inject constructor(
    private val dao: ClassificationDao,
) {
    val overrides: Flow<Map<Long, ClassificationOverride>> = dao.observeOverrides().map { list ->
        list.associate { it.trackId to ClassificationOverride(kindOf(it.kind), it.hidden) }
    }

    val folderRules: Flow<List<FolderRule>> = dao.observeFolderRules().map { list ->
        list.map { FolderRule(it.path, kindOf(it.kind), it.hidden) }
    }

    suspend fun setKind(trackId: Long, kind: AudioKind) =
        dao.upsertOverride(AudioOverrideEntity(trackId, kind.name, hidden = false, updatedAt = now()))

    suspend fun hide(trackId: Long) =
        dao.upsertOverride(AudioOverrideEntity(trackId, kind = null, hidden = true, updatedAt = now()))

    suspend fun unhide(trackId: Long) {
        dao.unhide(trackId)
        dao.pruneEmptyOverrides()
    }

    /** Goes back to the automatic classification for this file. */
    suspend fun reset(trackId: Long) = dao.deleteOverride(trackId)

    suspend fun setFolderRule(path: String, kind: AudioKind?, hidden: Boolean = false) =
        dao.upsertFolderRule(FolderRuleEntity(path, kind?.name, hidden))

    suspend fun removeFolderRule(path: String) = dao.deleteFolderRule(path)

    private fun kindOf(name: String?): AudioKind? = name?.let { n -> AudioKind.entries.firstOrNull { it.name == n } }

    private fun now() = System.currentTimeMillis()
}
