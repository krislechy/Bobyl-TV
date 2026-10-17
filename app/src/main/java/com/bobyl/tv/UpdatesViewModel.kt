package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class UpdatesState(val rows: List<SeriesUpdate> = emptyList(), val busy: Boolean = true, val error: String = "", val removing: Set<String> = emptySet(),
    val posterFinished: Set<String> = emptySet(), val posterErrors: Map<String, String> = emptyMap())

internal class UpdatesViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(UpdatesState())
    val state = mutableState.asStateFlow()
    private var loading = false
    private var loaded = false
    private var appliedRevision = -1
    private val repository = ExtrasRepository()
    private val posterRequests = mutableSetOf<String>()
    private val posters = mutableMapOf<String, String>()
    private val posterSlots = Semaphore(3)
    fun refresh(provider: String, revision: Int, force: Boolean = false) {
        if (loading || mutableState.value.removing.isNotEmpty()) return
        if (!force && loaded && appliedRevision == revision) return
        loading = true
        mutableState.value = mutableState.value.copy(busy = true, error = "")
        viewModelScope.launch {
            try {
                val rows = repository.trackedUpdates(provider, force).map { row ->
                    posters[row.film.url]?.let { row.copy(film = row.film.copy(poster = it)) } ?: row
                }
                loaded = true
                appliedRevision = revision
                mutableState.update { it.copy(rows = rows, busy = false, posterFinished = emptySet(), posterErrors = emptyMap()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = mutableState.value.copy(busy = false, error = networkErrorMessage(failure)) }
            finally { loading = false }
        }
    }
    fun loadPoster(url: String) {
        if (url in mutableState.value.posterFinished || !posterRequests.add(url)) return
        viewModelScope.launch {
            try {
                val poster = posterSlots.withPermit { repository.updatePoster(url) }
                if (poster.isNotBlank()) posters[url] = poster
                mutableState.update { current -> current.copy(
                    rows = current.rows.map { if (it.film.url == url) it.copy(film = it.film.copy(poster = poster)) else it },
                    posterFinished = current.posterFinished + url,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(posterFinished = it.posterFinished + url,
                    posterErrors = it.posterErrors + (url to networkErrorMessage(failure))) }
            } finally { posterRequests.remove(url) }
        }
    }
    fun remove(provider: String, url: String) {
        val key = historyFilmKey(url)
        if (mutableState.value.busy || key in mutableState.value.removing) return
        mutableState.update { it.copy(removing = it.removing + key, error = "") }
        viewModelScope.launch {
            try {
                ExtrasRepository().removeTrackedFilm(provider, url, notifyChanges = false)
                mutableState.update { current -> current.copy(rows = current.rows.map {
                    if (historyFilmKey(it.film.url) == key) it.copy(tracked = false) else it
                }) }
                ServerHistoryChanges.changed()
                appliedRevision = ServerHistoryChanges.revision.value
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = networkErrorMessage(failure)) } }
            finally { mutableState.update { it.copy(removing = it.removing - key) } }
        }
    }
}
