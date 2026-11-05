package com.bobyl.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class FavoritesState(
    val session: AccountSession? = null,
    val categories: List<BookmarkCategory> = emptyList(),
    val busy: Boolean = true,
    val error: String = "",
    val dirty: Boolean = false,
    val contentRevision: Int = 0,
)

/** One retained screen instance; membership events mark it dirty without fetching hidden lists. */
internal class FavoritesViewModel(
    private val read: suspend (String, Boolean) -> List<BookmarkCategory> = { provider, force -> LibraryRepository().categories(provider, force) },
    private val changes: StateFlow<Map<AccountSession, Int>> = LibraryRepository.changes,
    private val sessionFor: (String) -> AccountSession = AccountSessionGuard.shared::current,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FavoritesState())
    val state = mutableState.asStateFlow()
    private var owner: AccountSession? = null
    private var job: Job? = null
    private var serial = 0L
    private var loaded = false
    private var appliedMutation = -1
    private var appliedForeground = -1
    private var requestedForeground = 0
    private var failedRequest: Pair<Int, Int>? = null

    init { viewModelScope.launch { changes.collect { batch ->
        owner?.let { session -> mutableState.update { it.copy(dirty = !loaded ||
            appliedMutation != (batch[session] ?: 0) || appliedForeground != requestedForeground) } }
    } } }

    fun open(provider: String, foregroundRevision: Int = 0) {
        bind(provider)
        requestedForeground = foregroundRevision
        val session = owner ?: return
        val mutation = changes.value[session] ?: 0
        if (loaded && appliedMutation == mutation && appliedForeground == foregroundRevision && !mutableState.value.dirty) return
        if (failedRequest == (mutation to foregroundRevision)) return
        load(provider)
    }

    fun retry(provider: String) { bind(provider); failedRequest = null; load(provider, force = true) }

    private fun bind(provider: String) {
        val session = sessionFor(provider)
        if (owner == session) return
        serial++; job?.cancel(); job = null
        owner = session; loaded = false; appliedMutation = -1; appliedForeground = -1
        requestedForeground = 0; failedRequest = null
        mutableState.value = FavoritesState(session = session)
    }

    private fun current(session: AccountSession, request: Long) =
        owner == session && serial == request && sessionFor(session.provider) == session

    private fun load(provider: String, force: Boolean = false) {
        if (job?.isActive == true) return
        val session = owner ?: return
        val mutation = changes.value[session] ?: 0
        val foreground = requestedForeground
        val fresh = force || ((mutation != 0 || foreground != 0) && !loaded) || (loaded &&
            (appliedMutation != mutation || appliedForeground != foreground)) || mutableState.value.dirty
        val request = ++serial
        mutableState.update { it.copy(busy = true, error = "") }
        job = viewModelScope.launch {
            try {
                val categories = read(provider, fresh)
                if (!current(session, request)) return@launch
                loaded = true; appliedMutation = mutation; appliedForeground = foreground; failedRequest = null
                mutableState.update { it.copy(categories = categories, busy = false, contentRevision = it.contentRevision + 1,
                    dirty = (changes.value[session] ?: 0) != mutation || requestedForeground != foreground) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current(session, request)) {
                    failedRequest = mutation to foreground
                    mutableState.update { it.copy(busy = false, error = networkErrorMessage(failure), dirty = true) }
                }
            } finally {
                if (owner == session && serial == request) {
                    if (sessionFor(session.provider) != session) mutableState.value = FavoritesState(busy = false, dirty = true)
                    else mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }
}
