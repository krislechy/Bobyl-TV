package com.bobyl.tv

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.CatalogUrls
import com.bobyl.tv.data.LibraryRepository
import com.bobyl.tv.data.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PersonalRatingState(val value: Int? = null, val loading: Boolean = true, val saving: Boolean = false,
    val error: String = "", val aggregate: Pair<String, String>? = null)

internal class PersonalRatingViewModel(application: Application) : AndroidViewModel(application) {
    private val ratings = application.getSharedPreferences("personal_ratings", Context.MODE_PRIVATE)
    private fun numericFilmId(reference: String): String = reference.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: Regex("/(\\d+)-").find(java.net.URI(reference).path.orEmpty())?.groupValues?.get(1)
        ?: error("Не удалось определить фильм для оценки. Откройте его карточку и повторите попытку.")
    private fun ratingKey(provider: String, filmId: String) = "${CatalogUrls.provider(provider)}|${numericFilmId(filmId)}"
    private val mutableState = MutableStateFlow(PersonalRatingState())
    val state = mutableState.asStateFlow()
    fun load(provider: String, filmId: String) {
        if (mutableState.value.saving) return
        viewModelScope.launch {
            mutableState.value = PersonalRatingState(loading = true)
            try {
                val value = withContext(Dispatchers.IO) { ratings.getInt(ratingKey(provider, filmId), 0).coerceIn(0, 10) }
                mutableState.value = PersonalRatingState(value = value, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = PersonalRatingState(loading = false, error = networkErrorMessage(failure)) }
        }
    }

    fun rate(provider: String, filmId: String, value: Int) {
        val before = mutableState.value
        if (before.loading || before.saving || value == before.value) return
        mutableState.value = before.copy(saving = true, error = "")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val result = LibraryRepository().rate(provider, numericFilmId(filmId), value)
                    ratings.edit().putInt(ratingKey(provider, filmId), value).commit()
                    result
                }
                mutableState.value = before.copy(value = value, saving = false, aggregate = result.average to result.votes, error = "")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = before.copy(saving = false, error = networkErrorMessage(failure)) }
        }
    }
}
