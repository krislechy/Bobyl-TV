package com.bobyl.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class FilmBookmarksState(val categories: List<BookmarkCategory> = emptyList(),
    val saving: Boolean = false, val error: String = "", val revision: Int = 0)

internal class FilmBookmarksViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(FilmBookmarksState())
    val state = mutableState.asStateFlow()

    fun initialize(categories: List<BookmarkCategory>) {
        if (!mutableState.value.saving && mutableState.value.categories != categories) {
            mutableState.value = mutableState.value.copy(categories = categories)
        }
    }

    fun toggle(provider: String, filmId: String, url: String, category: BookmarkCategory) {
        val before = mutableState.value
        if (before.saving) return
        mutableState.value = before.copy(saving = true, error = "")
        viewModelScope.launch {
            try {
                val categories = withContext(Dispatchers.IO) {
                    LibraryRepository().toggle(provider, filmId, category.id)
                    CatalogRepository().details(url).bookmarks.also { updated ->
                        check(updated.firstOrNull { it.id == category.id }?.checked == !category.checked) {
                            "Сервер не подтвердил изменение избранного. Попробуйте ещё раз."
                        }
                    }
                }
                mutableState.value = before.copy(categories = categories, revision = before.revision + 1)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = before.copy(error = networkErrorMessage(failure)) }
        }
    }
}
