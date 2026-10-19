package com.bobyl.tv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.CatalogRepository
import com.bobyl.tv.data.CatalogSection
import com.bobyl.tv.data.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
data class CatalogRequest(
    val provider: String,
    val section: CatalogSection,
    val query: String,
    val kind: String,
    val sessionRevision: Int = 0,
)

@Immutable
data class CatalogSelection(
    val newsSection: CatalogSection = CatalogSection.Slider,
    val kind: String = "0",
    val searchDraft: String = "",
    val query: String = "",
)

@Immutable
data class CatalogState(
    val films: List<CatalogFilm> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val hasMore: Boolean = true,
    val request: CatalogRequest? = null,
    val restoring: Boolean = false,
)

class CatalogViewModel(
    private val loadPage: suspend (String, CatalogSection, Int, String, String) -> List<CatalogFilm> = CatalogRepository()::load,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    companion object {
        val Factory = viewModelFactory {
            initializer { CatalogViewModel(savedStateHandle = createSavedStateHandle()) }
        }
    }

    private val mutableSelection = MutableStateFlow(CatalogSelection(
        newsSection = CatalogSection.entries.firstOrNull { it.name == savedStateHandle.get<String>("newsSection") } ?: CatalogSection.Slider,
        kind = savedStateHandle["kind"] ?: "0",
        searchDraft = savedStateHandle["searchDraft"] ?: "",
        query = savedStateHandle["query"] ?: "",
    ))
    val selection = mutableSelection.asStateFlow()

    fun selectNewsSection(section: CatalogSection) = updateSelection(mutableSelection.value.copy(newsSection = section))
    fun selectKind(kind: String) = updateSelection(mutableSelection.value.copy(kind = kind))
    fun editSearch(value: String) = updateSelection(mutableSelection.value.copy(searchDraft = value))
    fun submitSearch() = updateSelection(mutableSelection.value.copy(query = mutableSelection.value.searchDraft.trim()))

    private fun updateSelection(selection: CatalogSelection) {
        savedStateHandle["newsSection"] = selection.newsSection.name
        savedStateHandle["kind"] = selection.kind
        savedStateHandle["searchDraft"] = selection.searchDraft
        savedStateHandle["query"] = selection.query
        mutableSelection.value = selection
    }

    private val mutableState = MutableStateFlow(CatalogState(restoring = (savedStateHandle.get<Int>("loadedPage") ?: 0) > 1))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var restoreThroughPage = 1
    private var loadedAt = 0L
    private var refreshPending = false
    private fun requestIdentity(request: CatalogRequest) = listOf(request.provider, request.section.name, request.query, request.kind)
    fun clear() {
        generation++
        job?.cancel()
        restoreThroughPage = 1
        savedStateHandle.remove<Int>("loadedPage")
        savedStateHandle.remove<List<String>>("loadedRequest")
        mutableState.value = CatalogState(hasMore = false)
    }

    fun loadMore() {
        val current = mutableState.value
        val request = current.request ?: return
        if (current.loading || current.error != null || !current.hasMore) return
        launchRequest(request, more = true)
    }

    fun retry() {
        val current = mutableState.value
        val request = current.request ?: return
        if (current.loading || current.error == null) return
        launchRequest(request, more = current.page > 0 && !refreshPending, refresh = refreshPending)
    }

    fun refreshIfStale(force: Boolean = false) {
        val current = mutableState.value
        val request = current.request ?: return
        if (current.loading || current.error != null || loadedAt == 0L ||
            (!force && android.os.SystemClock.elapsedRealtime() - loadedAt < com.bobyl.tv.data.PageCache.durationMillis())) return
        restoreThroughPage = current.page.coerceAtLeast(1)
        launchRequest(request, more = false, refresh = true)
    }

    fun load(provider: String, section: CatalogSection, query: String, kind: String = section.genre, sessionRevision: Int = 0) {
        val request = CatalogRequest(provider, section, query, kind, sessionRevision)
        // Recomposition and returning to the screen must not retry failures implicitly.
        if (request == mutableState.value.request) { refreshIfStale(); return }
        refreshPending = false
        restoreThroughPage = if (mutableState.value.request == null &&
            savedStateHandle.get<List<String>>("loadedRequest") == requestIdentity(request)) {
            (savedStateHandle.get<Int>("loadedPage") ?: 1).coerceAtLeast(1)
        } else 1
        savedStateHandle["loadedRequest"] = requestIdentity(request)
        if (restoreThroughPage == 1) savedStateHandle["loadedPage"] = 0
        launchRequest(request, more = false)
    }

    private fun launchRequest(request: CatalogRequest, more: Boolean, refresh: Boolean = false) {
        val requestGeneration = ++generation
        job?.cancel()
        val visible = mutableState.value
        refreshPending = refresh
        var previous = if (more) mutableState.value else CatalogState(request = request)
        mutableState.value = if (refresh) visible.copy(loading = true, error = null) else
            previous.copy(loading = true, error = null, restoring = restoreThroughPage > previous.page + 1)
        job = viewModelScope.launch {
            try {
                do {
                    val page = previous.page + 1
                    val films = loadPage(request.provider, request.section, page, request.query, request.kind)
                    val combined = withContext(computationDispatcher) { (previous.films + films).distinctBy { it.url } }
                    if (generation != requestGeneration) return@launch
                    previous = previous.copy(
                        films = combined, loading = false, error = null, page = page,
                        hasMore = (request.section != CatalogSection.Slider || request.query.isNotBlank()) &&
                            films.isNotEmpty() && combined.size > previous.films.size,
                        restoring = false,
                    )
                } while (previous.page < restoreThroughPage && previous.hasMore)
                restoreThroughPage = 1
                savedStateHandle["loadedPage"] = previous.page
                if (!more) loadedAt = android.os.SystemClock.elapsedRealtime()
                refreshPending = false
                mutableState.value = previous
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == requestGeneration) mutableState.value = (if (refresh) visible else previous)
                    .copy(loading = false, restoring = false, error = networkErrorMessage(error))
            }
        }
    }
}
