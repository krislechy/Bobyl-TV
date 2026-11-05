package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class UpdatesState(val rows: List<SeriesUpdate> = emptyList(), val busy: Boolean = true, val error: String = "", val removing: Set<String> = emptySet(),
    val posterFinished: Set<String> = emptySet(), val posterErrors: Map<String, String> = emptyMap(), val dirty: Boolean = false)

internal class UpdatesViewModel @JvmOverloads constructor(
    application: Application,
    private val readUpdates: suspend (String, Boolean) -> List<SeriesUpdate> = { provider, force -> ExtrasRepository().trackedUpdates(provider, force) },
    private val changes: StateFlow<Int> = ServerHistoryChanges.revision,
    private val sessionFor: (String) -> AccountSession = { AccountSessionGuard.shared.current(it) },
    private val removeUpdate: suspend (String, String) -> Unit = { provider, url -> ExtrasRepository().removeTrackedFilm(provider, url, notifyChanges = false) },
    private val notifyRemoval: (AccountSession, String) -> Unit = { session, url -> ServerHistoryChanges.filmChanged(session, url, server = emptyList()) },
    private val sessions: AccountSessionGuard = AccountSessionGuard.shared,
    private val userClearRevision: () -> Int = { PersistentCache.userClearRevision.value },
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(UpdatesState())
    val state = mutableState.asStateFlow()
    private var loading = false
    private var loaded = false
    private var appliedRevision = -1
    private var requestedRefreshRevision = 0
    private var appliedRefreshRevision = -1
    private var appliedUserClearRevision = userClearRevision()
    private var attemptedUserClearRevision = appliedUserClearRevision
    private var userClearRefreshPending = false
    private var owner: AccountSession? = null
    private var requestSerial = 0L
    private var request: Job? = null
    private val repository = ExtrasRepository()
    private val posterRequests = mutableSetOf<String>()
    private val posters = mutableMapOf<String, String>()
    private val posterSlots = Semaphore(3)
    init {
        viewModelScope.launch {
            changes.collect { revision ->
                if (owner != null) mutableState.update { it.copy(dirty = !loaded || appliedRevision != revision ||
                    appliedRefreshRevision != requestedRefreshRevision || appliedUserClearRevision != userClearRevision()) }
            }
        }
    }

    /** Ordinary events never fetch a hidden preview; explicit cache clearing is consumed on Home entry. */
    fun preview(provider: String, refreshRevision: Int = 0) {
        bind(provider)
        val clearEpoch = userClearRevision()
        if (appliedUserClearRevision != clearEpoch) {
            requestedRefreshRevision = refreshRevision
            if (mutableState.value.error.isNotBlank() && attemptedUserClearRevision == clearEpoch) return
            load(provider, force = true)
        } else if (!loaded && mutableState.value.error.isBlank()) {
            requestedRefreshRevision = refreshRevision
            load(provider)
        }
    }

    /** Entering the section consumes invalidation; unchanged re-entry reuses the visible list. */
    fun open(provider: String, refreshRevision: Int = 0) {
        bind(provider)
        requestedRefreshRevision = refreshRevision
        if (loaded && appliedRevision == changes.value && appliedRefreshRevision == refreshRevision &&
            appliedUserClearRevision == userClearRevision() && !mutableState.value.dirty) return
        load(provider)
    }

    fun retry(provider: String) {
        bind(provider)
        load(provider, force = true)
    }

    private fun bind(provider: String) {
        val next = sessionFor(provider)
        if (owner == next) return
        requestSerial++
        request?.cancel()
        request = null
        owner = next
        loading = false
        loaded = false
        appliedRevision = -1
        appliedRefreshRevision = -1
        requestedRefreshRevision = 0
        appliedUserClearRevision = userClearRevision()
        attemptedUserClearRevision = appliedUserClearRevision
        userClearRefreshPending = false
        posters.clear()
        posterRequests.clear()
        mutableState.value = UpdatesState(dirty = changes.value != 0)
    }

    private fun isCurrent(session: AccountSession, serial: Long = requestSerial): Boolean =
        owner == session && requestSerial == serial && sessionFor(session.provider) == session

    private fun load(provider: String, force: Boolean = false) {
        if (loading) {
            if (appliedUserClearRevision != userClearRevision()) userClearRefreshPending = true
            return
        }
        if (mutableState.value.removing.isNotEmpty()) {
            if (appliedUserClearRevision != userClearRevision()) userClearRefreshPending = true
            return
        }
        val session = owner ?: return
        val revision = changes.value
        val refreshRevision = requestedRefreshRevision
        val clearEpoch = userClearRevision()
        attemptedUserClearRevision = clearEpoch
        val bypassCache = force || mutableState.value.dirty ||
            (loaded && (appliedRevision != revision || appliedRefreshRevision != refreshRevision || appliedUserClearRevision != clearEpoch)) || (!loaded && revision != 0)
        val serial = ++requestSerial
        loading = true
        mutableState.value = mutableState.value.copy(busy = true, error = "")
        request = viewModelScope.launch {
            try {
                val response = readUpdates(provider, bypassCache)
                if (!isCurrent(session, serial)) return@launch
                val rows = response.map { row ->
                    posters[row.film.url]?.let { row.copy(film = row.film.copy(poster = it)) } ?: row
                }
                loaded = true
                appliedRevision = revision
                appliedRefreshRevision = refreshRevision
                appliedUserClearRevision = clearEpoch
                mutableState.update { it.copy(rows = rows, busy = false, posterFinished = emptySet(), posterErrors = emptyMap(),
                    dirty = changes.value != revision || requestedRefreshRevision != refreshRevision || userClearRevision() != clearEpoch) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (isCurrent(session, serial)) mutableState.update { it.copy(busy = false, error = networkErrorMessage(failure), dirty = true) }
            }
            finally {
                if (owner == session && requestSerial == serial) {
                    loading = false
                    if (sessionFor(session.provider) != session) mutableState.value = UpdatesState(busy = false, dirty = true)
                    else {
                        mutableState.update { it.copy(busy = false) }
                        consumePendingUserClear(provider)
                    }
                }
            }
        }
    }

    private fun consumePendingUserClear(provider: String) {
        if (loading || mutableState.value.removing.isNotEmpty() || !userClearRefreshPending) return
        userClearRefreshPending = false
        if (attemptedUserClearRevision != userClearRevision()) load(provider, force = true)
    }
    fun loadPoster(url: String) {
        val session = owner ?: return
        if (url in mutableState.value.posterFinished || !posterRequests.add(url)) return
        viewModelScope.launch {
            try {
                val poster = posterSlots.withPermit { repository.updatePoster(url) }
                if (!isCurrent(session)) return@launch
                if (poster.isNotBlank()) posters[url] = poster
                mutableState.update { current -> current.copy(
                    rows = current.rows.map { if (it.film.url == url) it.copy(film = it.film.copy(poster = poster)) else it },
                    posterFinished = current.posterFinished + url,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (isCurrent(session)) mutableState.update { it.copy(posterFinished = it.posterFinished + url,
                    posterErrors = it.posterErrors + (url to networkErrorMessage(failure))) }
            } finally { if (owner == session) posterRequests.remove(url) }
        }
    }
    fun remove(provider: String, url: String) {
        val key = historyFilmKey(url)
        val session = owner?.takeIf { it == sessionFor(provider) } ?: return
        if (mutableState.value.busy || key in mutableState.value.removing) return
        val permit = sessions.register(session)
        if (!permit.accepted) {
            mutableState.update { it.copy(error = permit.rejection.orEmpty()) }
            sessions.finish(permit)
            return
        }
        mutableState.update { it.copy(removing = it.removing + key, error = "") }
        val job = viewModelScope.launch {
            try {
                sessions.server(permit) { removeUpdate(provider, url) }
                notifyRemoval(session, url)
                if (!isCurrent(session)) return@launch
                mutableState.update { current -> current.copy(rows = current.rows.map {
                    if (historyFilmKey(it.film.url) == key) it.copy(tracked = false) else it
                }) }
                mutableState.update { it.copy(dirty = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (isCurrent(session)) mutableState.update { it.copy(error = networkErrorMessage(failure)) } }
            finally {
                if (owner == session) mutableState.update { it.copy(removing = it.removing - key) }
                sessions.finish(permit)
                if (isCurrent(session)) consumePendingUserClear(provider)
            }
        }
        job.invokeOnCompletion { sessions.finish(permit) }
    }
}
