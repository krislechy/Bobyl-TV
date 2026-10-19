package com.bobyl.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.BookmarkCategory
import com.bobyl.tv.data.CatalogRepository
import com.bobyl.tv.data.LibraryRepository
import com.bobyl.tv.data.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class BookmarkRemovalState(val loading: Boolean = true, val saving: Boolean = false,
    val categories: List<BookmarkCategory> = emptyList(), val error: String = "", val removed: Boolean = false)

internal class BookmarkRemovalViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(BookmarkRemovalState())
    val state = mutableState.asStateFlow()
    private var checking: Job? = null

    fun check(url: String) {
        if (mutableState.value.saving) return
        checking?.cancel()
        mutableState.value = BookmarkRemovalState()
        checking = viewModelScope.launch {
            try { mutableState.value = BookmarkRemovalState(loading = false, categories = CatalogRepository().details(url).bookmarks) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = BookmarkRemovalState(loading = false, error = networkErrorMessage(failure)) }
        }
    }

    fun remove(provider: String, url: String) {
        val before = mutableState.value
        if (before.loading || before.saving || before.error.isNotBlank()) return
        mutableState.value = before.copy(saving = true)
        viewModelScope.launch {
            try {
                val remaining = LibraryRepository().removeFromAll(provider, url)
                mutableState.value = BookmarkRemovalState(loading = false, categories = remaining, removed = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = before.copy(error = networkErrorMessage(failure)) }
        }
    }
}
