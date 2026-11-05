package com.bobyl.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CategoriesState(val session: AccountSession? = null, val filters: BrowseFilters? = null,
    val busy: Boolean = true, val error: String? = null, val contentRevision: Int = 0)

internal class CategoriesViewModel(
    private val read: suspend (String, Boolean) -> BrowseFilters = { provider, force -> BrowseRepository().filters(provider, force) },
    private val sessionFor: (String) -> AccountSession = AccountSessionGuard.shared::current,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CategoriesState())
    val state = mutableState.asStateFlow()
    private var owner: AccountSession? = null
    private var appliedRevision: Int? = null
    private var requestedRevision = 0
    private var failedRevision: Int? = null
    private var serial = 0L
    private var job: Job? = null

    fun open(provider: String, refreshRevision: Int = 0) {
        bind(provider); requestedRevision = refreshRevision
        if (appliedRevision == refreshRevision || failedRevision == refreshRevision) return
        load(provider)
    }

    fun retry(provider: String) { bind(provider); failedRevision = null; load(provider, force = true) }

    private fun bind(provider: String) {
        val session = sessionFor(provider)
        if (owner == session) return
        serial++; job?.cancel(); job = null; owner = session
        appliedRevision = null; failedRevision = null; requestedRevision = 0
        mutableState.value = CategoriesState(session = session)
    }

    private fun current(session: AccountSession, request: Long) = owner == session && serial == request && sessionFor(session.provider) == session

    private fun load(provider: String, force: Boolean = false) {
        if (job?.isActive == true) return
        val session = owner ?: return
        val revision = requestedRevision
        val request = ++serial
        mutableState.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                val filters = read(provider, force || revision != 0 || appliedRevision != null && appliedRevision != revision)
                if (!current(session, request)) return@launch
                appliedRevision = revision; failedRevision = null
                mutableState.update { it.copy(filters = filters, busy = false, contentRevision = it.contentRevision + 1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current(session, request)) { failedRevision = revision
                    mutableState.update { it.copy(busy = false, error = networkErrorMessage(failure)) } }
            } finally { if (owner == session && serial == request) {
                if (sessionFor(session.provider) != session) mutableState.value = CategoriesState(busy = false)
                else mutableState.update { it.copy(busy = false) }
            } }
        }
    }
}
