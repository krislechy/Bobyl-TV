package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DeletedHistoryItem(val key: String, val records: List<WatchRecord> = emptyList(), val server: List<ContinueItem> = emptyList())

internal data class HistoryEntry(val key: String, val film: CatalogFilm, val records: List<WatchRecord>, val server: List<ContinueItem>) {
    val watched: Boolean get() = if (server.isNotEmpty()) server.any { it.watched } else records.any { it.watched }
    val position: WatchRecord? get() = records.filter { it.positionMs > 0 }.maxByOrNull { it.updated }
        ?.takeIf { it.updated >= (server.maxOfOrNull { row -> row.viewedTimestamp ?: 0L } ?: 0L) }
    val info: String get() = position?.let { historyPositionLabel(it) } ?: server.firstOrNull()?.info.orEmpty()
    val updated: Long get() = maxOf(records.maxOfOrNull { it.updated } ?: 0L,
        server.maxOfOrNull { it.viewedTimestamp ?: 0L } ?: 0L)
}

internal fun historyEntries(records: List<WatchRecord>, server: List<ContinueItem>): List<HistoryEntry> {
    val locals = records.groupBy { historyFilmKey(it.url) }
    val account = server.groupBy { historyFilmKey(it.film.url) }
    return (account.keys + locals.keys).distinct().map { key ->
        val localRows = locals[key].orEmpty().sortedByDescending { it.updated }
        val serverRows = account[key].orEmpty().sortedByDescending { it.viewedTimestamp ?: 0L }
        val film = serverRows.firstOrNull()?.film ?: localRows.first().let {
            CatalogFilm(it.filmId, it.url, it.title, it.poster, "", "", "", "")
        }
        HistoryEntry(key, film.copy(poster = film.poster.ifBlank { localRows.firstOrNull()?.poster.orEmpty() }), localRows, serverRows)
    }.sortedByDescending { it.updated }
}

/** Replace only the affected film; never discard the list's other rows or fetch it again. */
internal fun HistoryState.applyFilmChange(change: HistoryFilmChange): HistoryState {
    val local = change.records?.let { rows -> records.filterNot { historyFilmKey(it.url) == change.key } + rows } ?: records
    val account = change.server?.let { rows -> server.filterNot { historyFilmKey(it.film.url) == change.key } + rows } ?: server
    return copy(records = if (change.watched == null) local else local.map {
        if (historyFilmKey(it.url) == change.key) it.copy(watched = change.watched) else it
    }, server = if (change.watched == null) account else account.map {
        if (historyFilmKey(it.film.url) == change.key) it.copy(watched = change.watched) else it
    })
}

internal fun historyPositionLabel(record: WatchRecord): String {
    val seconds = record.positionMs.coerceAtLeast(0) / 1000
    val episode = if (record.season.isNotBlank() && record.season != "0" && record.episode.isNotBlank() && record.episode != "0")
        "Сезон ${record.season} · серия ${record.episode} · " else ""
    return episode + "${seconds / 3600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

internal data class HistoryState(val records: List<WatchRecord> = emptyList(), val server: List<ContinueItem> = emptyList(),
    val busy: Boolean = true, val error: String = "", val changing: Set<String> = emptySet(), val removing: Set<String> = emptySet(),
    val deleted: List<DeletedHistoryItem> = emptyList())

internal class HistoryViewModel private constructor(application: Application,
    private val readRecords: suspend (String) -> List<WatchRecord>,
    private val readServer: suspend (String, Boolean) -> List<ContinueItem>,
    private val changes: StateFlow<HistoryFilmChanges>,
    private val currentSession: (String) -> AccountSession,
    private val acknowledgeChanges: (AccountSession, Long) -> Unit,
    private val sessions: AccountSessionGuard = AccountSessionGuard.shared,
    private val repository: ExtrasRepository = ExtrasRepository(),
    private val historyFor: (String) -> WatchHistory = { WatchHistory(application, it) },
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        readRecords = { provider -> withContext(Dispatchers.IO) { WatchHistory(application, provider).all() } },
        readServer = { provider, force -> ExtrasRepository().history(provider, force) },
        changes = ServerHistoryChanges.films, currentSession = AccountSessionGuard.shared::current,
        acknowledgeChanges = ServerHistoryChanges::acknowledge)

    companion object {
        internal fun forTests(application: Application, readRecords: suspend (String) -> List<WatchRecord>,
            readServer: suspend (String, Boolean) -> List<ContinueItem>, changes: StateFlow<HistoryFilmChanges>,
            currentSession: (String) -> AccountSession,
            acknowledgeChanges: (AccountSession, Long) -> Unit = { _, _ -> },
            sessions: AccountSessionGuard = AccountSessionGuard.shared,
            repository: ExtrasRepository = ExtrasRepository(),
            historyFor: (String) -> WatchHistory = { WatchHistory(application, it) }) =
            HistoryViewModel(application, readRecords, readServer, changes, currentSession, acknowledgeChanges,
                sessions, repository, historyFor)
    }
    private val mutableState = MutableStateFlow(HistoryState())
    val state = mutableState.asStateFlow()
    private var action: Job? = null
    private val localHistoryLock = Mutex()
    private val deletingFilms = mutableSetOf<String>()
    private var loadedSession: AccountSession? = null
    private var loadedRevision: Int? = null
    private var appliedSequence = 0L
    private var reading = false

    init {
        viewModelScope.launch { changes.collect { if (!reading) applyChanges(it) } }
    }

    private fun applyChanges(batch: HistoryFilmChanges) {
        val session = loadedSession ?: return
        if (currentSession(session.provider) != session) return
        val clears = batch.clears.filter { it.sequence > appliedSequence && it.session == session }
        if (clears.any { !it.completed }) loadedRevision = null
        val localClear = clears.filter { it.device && it.completed }.maxOfOrNull { it.sequence } ?: 0L
        val serverClear = clears.filter { !it.device && it.completed }.maxOfOrNull { it.sequence } ?: 0L
        val patches = batch.films.filter { it.sequence > appliedSequence && it.session == session }.sortedBy { it.sequence }
        mutableState.update { before ->
            val deleted = before.deleted.map { it.copy(records = if (localClear > 0) emptyList() else it.records,
                server = if (serverClear > 0) emptyList() else it.server) }
                .filter { it.records.isNotEmpty() || it.server.isNotEmpty() }
            patches.fold(before.copy(records = if (localClear > 0) emptyList() else before.records,
                server = if (serverClear > 0) emptyList() else before.server, deleted = deleted)) { state, patch ->
                state.applyFilmChange(patch.copy(records = patch.records.takeUnless { patch.sequence <= localClear },
                    server = patch.server.takeUnless { patch.sequence <= serverClear }))
            }
        }
        appliedSequence = batch.sequence
    }

    fun load(provider: String, refreshRevision: Int = 0) {
        if (!reading) applyChanges(changes.value)
        if (loadedSession == currentSession(provider) && loadedRevision == refreshRevision) return
        refresh(provider, refreshRevision, force = loadedSession != null)
    }

    private fun runAction(work: suspend () -> Unit) {
        if (action?.isActive == true || mutableState.value.changing.isNotEmpty() || mutableState.value.removing.isNotEmpty()) return
        mutableState.value = mutableState.value.copy(busy = true, error = "")
        action = viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = mutableState.value.copy(error = networkErrorMessage(failure)) }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    fun refresh(provider: String) = refresh(provider, loadedRevision ?: 0, force = true)

    private fun refresh(provider: String, refreshRevision: Int, force: Boolean) = runAction {
        val session = currentSession(provider)
        val startedAt = changes.value.sequence
        val effectiveForce = force || changes.value.clears.any { it.session == session && !it.completed }
        val previouslyLoaded = loadedSession == session && loadedRevision != null
        loadedSession = session
        reading = true
        try {
            val records = readRecords(provider)
            if (currentSession(provider) != session) return@runAction
            mutableState.update { it.copy(records = records) }
            val server = readServer(provider, effectiveForce)
            if (currentSession(provider) != session) return@runAction
            mutableState.update { it.copy(server = server) }
            loadedSession = session
            loadedRevision = refreshRevision
            if (effectiveForce) acknowledgeChanges(session, startedAt)
        } finally {
            reading = false
            appliedSequence = if (previouslyLoaded) startedAt else 0L
            applyChanges(changes.value)
        }
    }

    fun setWatched(provider: String, key: String, watched: Boolean) = changeWatched(provider, key, watched)

    private fun acceptMutation(provider: String): AccountSessionGuard.Permit? {
        val session = loadedSession?.takeIf { it == currentSession(provider) } ?: return null
        val permit = sessions.register(session)
        if (permit.accepted) return permit
        mutableState.update { it.copy(error = permit.rejection.orEmpty()) }
        sessions.finish(permit)
        return null
    }

    private fun changeWatched(provider: String, key: String, watched: Boolean) {
        if (mutableState.value.busy || key in mutableState.value.changing || key in deletingFilms) return
        val permit = acceptMutation(provider) ?: return
        mutableState.update { it.copy(changing = it.changing + key, error = "") }
        val job = viewModelScope.launch {
            try {
                sessions.server(permit) {
                    val entries = mutableState.value.server.filter {
                        historyFilmKey(it.film.url) == key && it.watched != watched
                    }
                    for (entry in entries) {
                        val updated = repository.setHistoryWatched(provider, entry, watched)
                        mutableState.update { current ->
                            current.copy(server = current.server.map { if (it.id == updated.id) updated else it })
                        }
                    }
                    val records = updateLocalWatched(provider, key, watched)
                    mutableState.update { current -> current.copy(
                        records = current.records.filterNot { historyFilmKey(it.url) == key } + records, error = "") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = networkErrorMessage(failure)) } }
            finally {
                mutableState.update { it.copy(changing = it.changing - key) }
                publishFilm(provider, key)
                sessions.finish(permit)
            }
        }
        job.invokeOnCompletion { sessions.finish(permit) }
    }

    private suspend fun updateLocalWatched(provider: String, key: String, watched: Boolean) = localHistoryLock.withLock { withContext(Dispatchers.IO) {
        val history = historyFor(provider)
        val current = mutableState.value
        val film = current.server.asSequence().filter { historyFilmKey(it.film.url) == key }
            .maxByOrNull { it.viewedTimestamp ?: 0L }?.film
            ?: current.records.asSequence().filter { historyFilmKey(it.url) == key }.maxByOrNull { it.updated }?.let {
                CatalogFilm(it.filmId, it.url, it.title, it.poster, "", "", "", "")
            }
        if (film != null) history.setFilmWatched(film, watched) else emptyList()
    } }

    fun removeFilm(provider: String, key: String) = removeRow(provider, key, key) {
        val records = withContext(Dispatchers.IO) { historyFor(provider).forFilm(key) }
        val entries = mutableState.value.server.filter { historyFilmKey(it.film.url) == key }.map { entry ->
            repository.prepareHistoryRestore(provider, entry, records.maxByOrNull { it.updated })
        }
        for (entry in entries) {
            withContext(Dispatchers.IO) { repository.removeHistory(provider, entry.id, notifyChanges = false) }
            mutableState.update { state ->
                val old = state.deleted.firstOrNull { it.key == key } ?: DeletedHistoryItem(key)
                state.copy(server = state.server.filterNot { it.id == entry.id },
                    deleted = listOf(old.copy(server = listOf(entry) + old.server.filterNot { it.id == entry.id })) + state.deleted.filterNot { it.key == key })
            }
            publishFilm(provider, key)
        }
        localHistoryLock.withLock { withContext(Dispatchers.IO) {
            val history = historyFor(provider)
            records.forEach { history.remove(it.filmId) }
        } }
        mutableState.update { state ->
            val old = state.deleted.firstOrNull { it.key == key } ?: DeletedHistoryItem(key)
            state.copy(records = state.records.filterNot { historyFilmKey(it.url) == key },
                deleted = listOf(old.copy(records = (records + old.records).distinctBy { it.filmId })) + state.deleted.filterNot { it.key == key })
        }
        publishFilm(provider, key)
    }

    fun restoreFilm(provider: String, key: String) = removeRow(provider, key, key) {
        val removed = mutableState.value.deleted.firstOrNull { it.key == key } ?: return@removeRow
        for (entry in removed.server) {
            val restored = repository.restoreHistory(provider, entry)
            mutableState.update { state -> state.copy(server = listOf(restored) + state.server.filterNot { it.id == restored.id }) }
            publishFilm(provider, key)
        }
        localHistoryLock.withLock { withContext(Dispatchers.IO) {
            val history = historyFor(provider)
            removed.records.forEach { history.put(it) }
        } }
        mutableState.update { state -> state.copy(records = removed.records + state.records.filterNot { historyFilmKey(it.url) == key },
            deleted = state.deleted.filterNot { it.key == key }) }
        publishFilm(provider, key)
    }

    private fun publishFilm(provider: String, key: String) {
        val current = mutableState.value
        val session = loadedSession ?: return
        if (session != currentSession(provider)) return
        val url = current.records.firstOrNull { historyFilmKey(it.url) == key }?.url
            ?: current.server.firstOrNull { historyFilmKey(it.film.url) == key }?.film?.url
            ?: key
        ServerHistoryChanges.filmChanged(session, url,
            current.records.filter { historyFilmKey(it.url) == key },
            current.server.filter { historyFilmKey(it.film.url) == key })
    }

    private fun removeRow(provider: String, row: String, key: String, work: suspend () -> Unit) {
        if (mutableState.value.busy || key in mutableState.value.changing || key in deletingFilms) return
        val permit = acceptMutation(provider) ?: return
        deletingFilms += key
        mutableState.update { it.copy(removing = it.removing + row, error = "") }
        val job = viewModelScope.launch {
            try { sessions.server(permit) { work() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = networkErrorMessage(failure)) } }
            finally {
                deletingFilms -= key
                mutableState.update { it.copy(removing = it.removing - row) }
                sessions.finish(permit)
            }
        }
        job.invokeOnCompletion { sessions.finish(permit) }
    }
}
