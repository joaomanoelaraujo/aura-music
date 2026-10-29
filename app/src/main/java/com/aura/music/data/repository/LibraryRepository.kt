package com.aura.music.data.repository

import android.content.Context
import android.util.Log
import com.aura.music.core.common.ApplicationScope
import com.aura.music.data.local.SettingsRepository
import com.aura.music.data.media.AudioPermission
import com.aura.music.data.media.LibraryAssembler
import com.aura.music.data.media.LibraryIndex
import com.aura.music.data.local.db.MetadataDao
import com.aura.music.data.media.LibraryRules
import com.aura.music.data.media.MetadataOverride
import com.aura.music.data.media.MediaStoreSource
import com.aura.music.data.media.ScannedAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LibraryState {
    data object Loading : LibraryState
    data object PermissionRequired : LibraryState
    data class Ready(val index: LibraryIndex) : LibraryState
    data object Error : LibraryState
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class LibraryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val source: MediaStoreSource,
    settingsRepository: SettingsRepository,
    classification: ClassificationRepository,
    metadataDao: MetadataDao,
    @ApplicationScope scope: CoroutineScope,
) {
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val triggers: Flow<Unit> = merge(
        refreshRequests,
        source.changes().debounce(MEDIASTORE_DEBOUNCE_MS),
    ).onStart { emit(Unit) }

    /** The raw MediaStore scan; only repeated when files change or the minimum duration does. */
    private val scan: Flow<ScanResult> = combine(
        settingsRepository.settings.map { it.minTrackDurationSec }.distinctUntilChanged(),
        triggers,
    ) { minDuration, _ -> minDuration }
        .mapLatest(::load)
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    private val rules: Flow<LibraryRules> = combine(
        classification.overrides,
        classification.folderRules,
        settingsRepository.settings
            .map { Triple(it.excludedFolders, it.hideWhatsApp, it.showUntagged) }
            .distinctUntilChanged(),
        metadataDao.observeOverrides().map { list -> list.associate { it.trackId to MetadataOverride(it.title, it.artist) } },
    ) { overrides, folderRules, (excluded, hideWhatsApp, showUntagged), metadata ->
        LibraryRules(overrides, folderRules, excluded, hideWhatsApp, showUntagged, metadata)
    }.distinctUntilChanged()

    /** Classification is re-applied on every rule change without touching MediaStore again. */
    val state: StateFlow<LibraryState> = combine(scan, rules) { scan, rules ->
        when (scan) {
            ScanResult.PermissionRequired -> LibraryState.PermissionRequired
            ScanResult.Error -> LibraryState.Error
            is ScanResult.Done -> LibraryState.Ready(LibraryAssembler.assemble(scan.files, rules))
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, LibraryState.Loading)

    /** Every folder holding audio, including excluded ones, so they can be re-included in settings. */
    val scannedFolders: Flow<List<String>> = scan
        .map { result -> (result as? ScanResult.Done)?.files?.map { it.track.folder }?.distinct()?.sorted().orEmpty() }
        .distinctUntilChanged()

    /** The latest index, or an empty one while loading or without permission. */
    val index: Flow<LibraryIndex> = state
        .map { (it as? LibraryState.Ready)?.index ?: LibraryIndex.Empty }
        .distinctUntilChanged()

    suspend fun awaitIndex(timeoutMs: Long = AWAIT_TIMEOUT_MS): LibraryIndex? = withTimeoutOrNull(timeoutMs) {
        state.filterIsInstance<LibraryState.Ready>().first().index
    }

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    private suspend fun load(minDurationSec: Int): ScanResult {
        if (!AudioPermission.isGranted(context)) return ScanResult.PermissionRequired
        return try {
            ScanResult.Done(source.queryAudio(minDurationSec * 1000L))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission revoked during scan", e)
            ScanResult.PermissionRequired
        } catch (e: Exception) {
            Log.e(TAG, "Library scan failed", e)
            ScanResult.Error
        }
    }

    private sealed interface ScanResult {
        data object PermissionRequired : ScanResult
        data object Error : ScanResult
        class Done(val files: List<ScannedAudio>) : ScanResult
    }

    private companion object {
        const val TAG = "LibraryRepository"
        const val MEDIASTORE_DEBOUNCE_MS = 1_200L
        const val AWAIT_TIMEOUT_MS = 8_000L
    }
}
