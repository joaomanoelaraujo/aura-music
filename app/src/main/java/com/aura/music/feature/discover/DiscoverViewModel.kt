package com.aura.music.feature.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.music.data.discover.AudioOption
import com.aura.music.data.discover.DiscoverException
import com.aura.music.data.discover.DiscoverItem
import com.aura.music.data.discover.DiscoverRepository
import com.aura.music.data.discover.DownloadRepository
import com.aura.music.data.discover.DownloadUi
import com.aura.music.data.discover.DownloadableTrack
import com.aura.music.data.discover.ItemDetail
import com.aura.music.data.discover.LinkAnalyzer
import com.aura.music.data.discover.LinkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val input: String = "",
    val loading: Boolean = false,
    val error: DiscoverException.Reason? = null,
    val results: List<DiscoverItem> = emptyList(),
    /** Query of the last finished search, to tell "no results" from "not searched yet". */
    val searchedFor: String? = null,
    val streaming: DiscoverItem? = null,
    val detail: ItemDetail? = null,
    val detailLoading: Boolean = false,
    val online: Boolean = true,
    val metered: Boolean = false,
    val downloads: List<DownloadUi> = emptyList(),
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val discover: DiscoverRepository,
    private val downloads: DownloadRepository,
) : ViewModel() {

    private val local = MutableStateFlow(DiscoverUiState(input = savedState[KEY_INPUT] ?: ""))
    private var job: Job? = null

    val state: StateFlow<DiscoverUiState> = combine(local, downloads.downloads) { s, list -> s.copy(downloads = list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        refreshConnectivity()
        viewModelScope.launch { downloads.reconcileActive() }
    }

    fun setInput(value: String) {
        savedState[KEY_INPUT] = value
        local.update { it.copy(input = value, error = null) }
    }

    fun refreshConnectivity() = local.update { it.copy(online = discover.isOnline(), metered = discover.isMetered()) }

    /** Searches the open catalog, or analyses the text when it is a link. */
    fun submit() {
        val input = local.value.input.trim()
        if (input.isEmpty()) return
        refreshConnectivity()
        job?.cancel()
        local.update { it.copy(loading = true, error = null, streaming = null) }
        job = viewModelScope.launch {
            try {
                if (LinkAnalyzer.looksLikeLink(input)) {
                    when (val result = discover.analyze(input)) {
                        is LinkResult.Streaming -> local.update { it.copy(loading = false, streaming = result.item, results = emptyList(), searchedFor = null) }
                        is LinkResult.Downloadable -> local.update { it.copy(loading = false, detail = result.detail, searchedFor = null) }
                    }
                } else {
                    val results = discover.search(input)
                    local.update { it.copy(loading = false, results = results, searchedFor = input) }
                }
            } catch (e: DiscoverException) {
                local.update { it.copy(loading = false, error = e.reason) }
            }
        }
    }

    fun open(item: DiscoverItem) {
        if (!item.downloadable) {
            local.update { it.copy(streaming = item) }
            return
        }
        local.update { it.copy(detailLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val detail = discover.detail(item)
                local.update { it.copy(detail = detail, detailLoading = false) }
            } catch (e: DiscoverException) {
                local.update { it.copy(detailLoading = false, error = e.reason) }
            }
        }
    }

    fun closeDetail() = local.update { it.copy(detail = null) }

    fun closeStreaming() = local.update { it.copy(streaming = null) }

    fun download(track: DownloadableTrack, option: AudioOption, onResult: (DownloadRepository.EnqueueResult) -> Unit) {
        val item = local.value.detail?.item ?: return
        viewModelScope.launch { onResult(downloads.enqueue(item, track, option)) }
    }

    fun cancel(id: Long) = viewModelScope.launch { downloads.cancel(id) }

    fun retry(id: Long, onResult: (DownloadRepository.EnqueueResult) -> Unit) = viewModelScope.launch { onResult(downloads.retry(id)) }

    fun remove(id: Long) = viewModelScope.launch { downloads.remove(id) }

    fun clearFinished() = viewModelScope.launch { downloads.clearFinished() }

    private companion object {
        const val KEY_INPUT = "discover_input"
    }
}
