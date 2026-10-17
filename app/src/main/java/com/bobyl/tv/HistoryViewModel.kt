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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DeletedHistoryItem(val key: String, val records: List<WatchRecord> = emptyList(), val server: List<ContinueItem> = emptyList())

internal data class HistoryEntry(val key: String, val film: CatalogFilm, val records: List<WatchRecord>, val server: List<ContinueItem>) {
    val watched: Boolean get() = if (server.isNotEmpty()) server.any { it.watched } else records.any { it.watched }
    val position: WatchRecord? get() = records.filter { it.positionMs > 0 }.maxByOrNull { it.updated }
    val info: String get() = position?.let { historyPositionLabel(it) } ?: server.firstOrNull()?.info.orEmpty()
}

internal fun historyEntries(records: List<WatchRecord>, server: List<ContinueItem>): List<HistoryEntry> {
    val locals = records.groupBy { historyFilmKey(it.url) }
    val account = server.groupBy { historyFilmKey(it.film.url) }
    return (account.keys + locals.keys).distinct().map { key ->
        val localRows = locals[key].orEmpty()
        val serverRows = account[key].orEmpty()
        val film = serverRows.firstOrNull()?.film ?: localRows.first().let {
            CatalogFilm(it.filmId, it.url, it.title, it.poster, "", "", "", "")
        }
        HistoryEntry(key, film.copy(poster = film.poster.ifBlank { localRows.firstOrNull()?.poster.orEmpty() }), localRows, serverRows)
    }
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

internal class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(HistoryState())
    val state = mutableState.asStateFlow()
    private var action: Job? = null
    private val repository = ExtrasRepository()
    private val localHistoryLock = Mutex()
    private val deletingFilms = mutableSetOf<String>()

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

    fun refresh(provider: String) = runAction {
        val records = withContext(Dispatchers.IO) { WatchHistory(getApplication(), provider).all() }
        mutableState.value = mutableState.value.copy(records = records)
        val server = repository.history(provider)
        mutableState.value = mutableState.value.copy(server = server)
    }

    fun setWatched(provider: String, key: String, watched: Boolean) = changeWatched(provider, key, watched)

    private fun changeWatched(provider: String, key: String, watched: Boolean) {
        if (mutableState.value.busy || key in mutableState.value.changing || key in deletingFilms) return
        mutableState.update { it.copy(changing = it.changing + key, error = "") }
        viewModelScope.launch {
            try {
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
                mutableState.update { it.copy(records = records, error = if (it.server.none { row -> historyFilmKey(row.film.url) == key })
                    "Отметка сохранена только на устройстве: записи в истории аккаунта нет. Серверная отметка доступна после начала просмотра в аккаунте."
                    else "") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = networkErrorMessage(failure)) } }
            finally {
                mutableState.update { it.copy(changing = it.changing - key) }
                ServerHistoryChanges.changed()
            }
        }
    }

    private suspend fun updateLocalWatched(provider: String, key: String, watched: Boolean) = localHistoryLock.withLock { withContext(Dispatchers.IO) {
        val history = WatchHistory(getApplication(), provider)
        val film = historyEntries(mutableState.value.records, mutableState.value.server).firstOrNull { it.key == key }?.film
        if (film != null) history.setWatched(film, watched) else history.all()
    } }

    fun removeFilm(provider: String, key: String) = removeRow(key, key) {
        val records = withContext(Dispatchers.IO) { WatchHistory(getApplication(), provider).all().filter { historyFilmKey(it.url) == key } }
        val entries = repository.history(provider).filter { historyFilmKey(it.film.url) == key }.map { entry ->
            repository.prepareHistoryRestore(provider, entry, records.maxByOrNull { it.updated })
        }
        for (entry in entries) {
            withContext(Dispatchers.IO) { repository.removeHistory(provider, entry.id, notifyChanges = false) }
            mutableState.update { state ->
                val old = state.deleted.firstOrNull { it.key == key } ?: DeletedHistoryItem(key)
                state.copy(server = state.server.filterNot { it.id == entry.id },
                    deleted = listOf(old.copy(server = listOf(entry) + old.server.filterNot { it.id == entry.id })) + state.deleted.filterNot { it.key == key })
            }
            ServerHistoryChanges.changed()
        }
        localHistoryLock.withLock { withContext(Dispatchers.IO) {
            val history = WatchHistory(getApplication(), provider)
            records.forEach { history.remove(it.filmId) }
        } }
        mutableState.update { state ->
            val old = state.deleted.firstOrNull { it.key == key } ?: DeletedHistoryItem(key)
            state.copy(records = state.records.filterNot { historyFilmKey(it.url) == key },
                deleted = listOf(old.copy(records = (records + old.records).distinctBy { it.filmId })) + state.deleted.filterNot { it.key == key })
        }
    }

    fun restoreFilm(provider: String, key: String) = removeRow(key, key) {
        val removed = mutableState.value.deleted.firstOrNull { it.key == key } ?: return@removeRow
        for (entry in removed.server) {
            val restored = repository.restoreHistory(provider, entry)
            mutableState.update { state -> state.copy(server = listOf(restored) + state.server.filterNot { it.id == restored.id }) }
            ServerHistoryChanges.changed()
        }
        localHistoryLock.withLock { withContext(Dispatchers.IO) {
            val history = WatchHistory(getApplication(), provider)
            removed.records.forEach { history.put(it) }
        } }
        mutableState.update { state -> state.copy(records = removed.records + state.records.filterNot { historyFilmKey(it.url) == key },
            deleted = state.deleted.filterNot { it.key == key }) }
    }

    private fun removeRow(row: String, key: String, work: suspend () -> Unit) {
        if (mutableState.value.busy || key in mutableState.value.changing || key in deletingFilms) return
        deletingFilms += key
        mutableState.update { it.copy(removing = it.removing + row, error = "") }
        viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = networkErrorMessage(failure)) } }
            finally {
                deletingFilms -= key
                mutableState.update { it.copy(removing = it.removing - row) }
            }
        }
    }
}
