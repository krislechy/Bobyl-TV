package com.bobyl.tv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PagedFilmsState(val token: String = "", val films: List<CatalogFilm> = emptyList(),
    val page: Int = 0, val busy: Boolean = true, val more: Boolean = true, val error: String? = null,
    val restoring: Boolean = false, val dirty: Boolean = false)

/** Retains only the current request for this screen; filter changes do not create ViewModel stores. */
internal class PagedFilmsViewModel(private val saved: SavedStateHandle,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    companion object { val Factory = viewModelFactory { initializer { PagedFilmsViewModel(createSavedStateHandle()) } } }
    private val mutableState = MutableStateFlow(PagedFilmsState())
    val state = mutableState.asStateFlow()
    private var loader: (suspend (Int, Boolean) -> List<CatalogFilm>)? = null
    private var serial = 0L
    private var job: Job? = null
    private var requestedRevision = 0
    private var appliedRevision: Int? = null
    private var refreshPending = false
    private var restoreThrough = 1
    private var failedRevision: Int? = null
    private var isCurrent: () -> Boolean = { true }

    fun open(token: String, revision: Int = 0, current: () -> Boolean = { true }, load: suspend (Int, Boolean) -> List<CatalogFilm>) {
        loader = load; requestedRevision = revision; isCurrent = current
        val before = mutableState.value
        if (before.token == token) {
            if (job?.isActive == true || appliedRevision == revision && before.error == null ||
                before.error != null && failedRevision == revision) return
            restoreThrough = before.page.coerceAtLeast(1)
            request(refresh = true, force = true)
            return
        }
        serial++; job?.cancel(); job = null; appliedRevision = null; refreshPending = false; failedRevision = null
        restoreThrough = if (saved.get<String>("token") == token) (saved.get<Int>("page") ?: 1).coerceAtLeast(1) else 1
        saved["token"] = token
        if (restoreThrough == 1) saved["page"] = 0
        mutableState.value = PagedFilmsState(token = token, restoring = restoreThrough > 1)
        request()
    }

    fun more() {
        val current = mutableState.value
        if (current.busy || current.error != null || !current.more || current.token.isBlank()) return
        if (current.dirty) { restoreThrough = current.page.coerceAtLeast(1); request(refresh = true, force = true); return }
        request(more = true)
    }

    fun retry() {
        val current = mutableState.value
        if (current.busy || current.error == null) return
        request(more = current.page > 0 && !refreshPending, refresh = refreshPending, force = true)
    }

    private fun request(more: Boolean = false, refresh: Boolean = false, force: Boolean = false) {
        val load = loader ?: return
        val valid = isCurrent
        val request = ++serial
        val revision = requestedRevision
        val visible = mutableState.value
        val token = visible.token
        var accumulated = if (more) visible else PagedFilmsState(token = token)
        refreshPending = refresh
        mutableState.value = (if (refresh) visible else accumulated).copy(busy = true, error = null,
            restoring = !refresh && restoreThrough > accumulated.page + 1)
        job = viewModelScope.launch {
            try {
                do {
                    val page = accumulated.page + 1
                    val films = load(page, force)
                    if (serial != request || mutableState.value.token != token || !valid()) return@launch
                    val combined = withContext(computationDispatcher) { (accumulated.films + films).distinctBy { it.url } }
                    if (serial != request || mutableState.value.token != token || !valid()) return@launch
                    accumulated = accumulated.copy(films = combined, page = page, busy = false, error = null,
                        more = films.isNotEmpty() && combined.size > accumulated.films.size, restoring = false)
                } while (accumulated.page < restoreThrough && accumulated.more)
                restoreThrough = 1; appliedRevision = revision; refreshPending = false; failedRevision = null
                saved["page"] = accumulated.page
                mutableState.value = accumulated.copy(dirty = requestedRevision != revision)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (serial == request && mutableState.value.token == token && valid()) {
                    failedRevision = revision
                    mutableState.value = (if (refresh) visible else accumulated).copy(busy = false, restoring = false,
                        error = networkErrorMessage(failure), dirty = requestedRevision != revision)
                }
            } finally {
                if (serial == request && !valid()) mutableState.value = PagedFilmsState(token = token, busy = false)
            }
        }
    }
}
