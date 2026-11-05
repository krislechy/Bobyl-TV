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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class FilmHistoryState(
    val inContinue: Boolean = false, val season: String = "", val episode: String = "", val error: String = "", val viewedAt: String = "", val watched: Boolean = false,
    val loading: Boolean = true, val saving: Boolean = false,
)

internal class FilmHistoryViewModel(application: Application, private val actions: FilmActionsViewModel,
    private val actionKey: FilmActionKey) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(FilmHistoryState())
    val state = mutableState.asStateFlow()
    private var loading: Job? = null
    private var serverRows: List<ContinueItem> = emptyList()
    private var loaded = false
    private var appliedSequence = 0L

    private fun formatDate(timestamp: Long): String = if (timestamp <= 0L) "" else
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru")).apply {
            timeZone = TimeZone.getTimeZone("Europe/Moscow")
        }.format(Date(timestamp))

    fun load(provider: String, url: String) {
        if (mutableState.value.saving) return
        loading?.cancel()
        mutableState.value = mutableState.value.copy(loading = true)
        loading = viewModelScope.launch {
            actions.awaitSettled(actionKey)
            val key = historyFilmKey(url)
            val local = withContext(Dispatchers.IO) {
                WatchHistory(getApplication(), provider).forFilm(key).firstOrNull()
            }
            val fallback = FilmHistoryState(inContinue = local != null, watched = local?.watched == true, loading = false,
                season = local?.season.orEmpty().takeIf { it.toIntOrNull()?.let { number -> number > 0 } == true }.orEmpty(),
                episode = local?.episode.orEmpty().takeIf { it.toIntOrNull()?.let { number -> number > 0 } == true }.orEmpty(),
                viewedAt = formatDate(local?.updated ?: 0L))
            try {
                val batch = ServerHistoryChanges.films.value
                val lastServerClear = batch.clears.lastOrNull { it.session == actionKey.session && !it.device }
                val patch = batch.films.lastOrNull { it.session == actionKey.session && it.key == key &&
                    it.sequence > (lastServerClear?.sequence ?: 0L) }
                val clear = batch.clears.lastOrNull { it.session == actionKey.session && !it.device && it.sequence > appliedSequence }
                if (clear != null && clear.completed && (patch?.sequence ?: 0L) <= clear.sequence) serverRows = emptyList()
                else if (patch?.server != null && (clear == null || patch.sequence > clear.sequence)) serverRows = patch.server
                else if (!loaded || clear != null) serverRows = AccountSessionGuard.shared.read(actionKey.session) {
                    ExtrasRepository().history(provider, force = clear?.completed == false).filter { historyFilmKey(it.film.url) == key }
                }
                patch?.watched?.let { watched -> serverRows = serverRows.map { it.copy(watched = watched) } }
                loaded = true
                appliedSequence = batch.sequence
                val server = serverRows.maxByOrNull { it.viewedTimestamp ?: 0L }
                val serverNewer = (server?.viewedTimestamp ?: 0L) > (local?.updated ?: 0L)
                mutableState.value = fallback.copy(inContinue = server != null || fallback.inContinue,
                    season = if (serverNewer) server?.season.orEmpty().ifBlank { fallback.season }
                        else fallback.season.ifBlank { server?.season.orEmpty() },
                    episode = if (serverNewer) server?.episode.orEmpty().ifBlank { fallback.episode }
                        else fallback.episode.ifBlank { server?.episode.orEmpty() },
                    viewedAt = if (serverNewer) server?.viewedAt.orEmpty().ifBlank { fallback.viewedAt }
                        else fallback.viewedAt.ifBlank { server?.viewedAt.orEmpty() },
                    watched = if (serverRows.isNotEmpty()) serverRows.any { it.watched } else fallback.watched,
                    error = actions.latestError(actionKey))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.value = fallback.copy(error = "Не удалось обновить историю просмотра: ${networkErrorMessage(failure)}")
            }
        }
    }

    fun setWatched(provider: String, film: CatalogFilm, watched: Boolean) {
        if (mutableState.value.saving || mutableState.value.loading) return
        loading?.cancel()
        val entries = serverRows.toList()
        val receipt = actions.watched(actionKey, film, watched, entries)
        mutableState.update { it.copy(saving = receipt.accepted, error = "") }
        viewModelScope.launch {
            try {
                val saved = receipt.await()
                serverRows = saved.rows
                mutableState.update { it.copy(inContinue = saved.local != null || saved.rows.isNotEmpty(),
                    watched = watched, error = saved.message) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(error = "Не удалось сохранить отметку: ${networkErrorMessage(failure)}") }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }
}
