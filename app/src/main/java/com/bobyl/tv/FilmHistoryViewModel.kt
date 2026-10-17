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

internal class FilmHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(FilmHistoryState())
    val state = mutableState.asStateFlow()
    private var loading: Job? = null
    private var serverRows: List<ContinueItem> = emptyList()

    private fun formatDate(timestamp: Long): String = if (timestamp <= 0L) "" else
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru")).apply {
            timeZone = TimeZone.getTimeZone("Europe/Moscow")
        }.format(Date(timestamp))

    fun load(provider: String, url: String) {
        if (mutableState.value.saving) return
        loading?.cancel()
        serverRows = emptyList()
        mutableState.value = mutableState.value.copy(loading = true)
        loading = viewModelScope.launch {
            val key = historyFilmKey(url)
            val local = withContext(Dispatchers.IO) {
                WatchHistory(getApplication(), provider).all().firstOrNull { historyFilmKey(it.url) == key }
            }
            val fallback = FilmHistoryState(inContinue = local != null, watched = local?.watched == true, loading = false,
                season = local?.season.orEmpty().takeIf { it.toIntOrNull()?.let { number -> number > 0 } == true }.orEmpty(),
                episode = local?.episode.orEmpty().takeIf { it.toIntOrNull()?.let { number -> number > 0 } == true }.orEmpty(),
                viewedAt = formatDate(local?.updated ?: 0L))
            try {
                serverRows = ExtrasRepository().history(provider).filter { historyFilmKey(it.film.url) == key }
                val server = serverRows.firstOrNull()
                mutableState.value = fallback.copy(inContinue = server != null || fallback.inContinue,
                    season = fallback.season.ifBlank { server?.season.orEmpty() },
                    episode = fallback.episode.ifBlank { server?.episode.orEmpty() },
                    viewedAt = server?.viewedAt.orEmpty().ifBlank { fallback.viewedAt },
                    watched = if (serverRows.isNotEmpty()) serverRows.any { it.watched } else fallback.watched)
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
        mutableState.update { it.copy(saving = true, error = "") }
        viewModelScope.launch {
            var savedLocally = false
            try {
                val local = withContext(Dispatchers.IO) {
                    WatchHistory(getApplication(), provider).setWatched(film, watched)
                        .firstOrNull { historyFilmKey(it.url) == historyFilmKey(film.url) }
                }
                savedLocally = true
                mutableState.update { it.copy(inContinue = local != null || entries.isNotEmpty(), watched = watched) }
                val repository = ExtrasRepository()
                serverRows = entries.map { repository.setHistoryWatched(provider, it, watched) }
                if (entries.isEmpty()) mutableState.update { it.copy(error =
                    "Отметка сохранена на устройстве. В истории аккаунта этого фильма пока нет.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(error = if (savedLocally)
                    "Отметка сохранена на устройстве. Не удалось обновить аккаунт: ${networkErrorMessage(failure)}"
                    else "Не удалось сохранить отметку: ${networkErrorMessage(failure)}") }
            } finally {
                mutableState.update { it.copy(saving = false) }
                if (savedLocally) ServerHistoryChanges.changed()
            }
        }
    }
}
