package com.bobyl.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.BookmarkCategory
import com.bobyl.tv.data.CatalogRepository
import com.bobyl.tv.data.networkErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class BookmarkRemovalState(val loading: Boolean = true, val saving: Boolean = false,
    val categories: List<BookmarkCategory> = emptyList(), val error: String = "", val removed: Boolean = false)

internal val BookmarkRemovalState.hasMembership: Boolean get() = categories.any { it.checked }
internal val BookmarkRemovalState.showConfirmation: Boolean get() = !loading && hasMembership

internal class BookmarkRemovalViewModel private constructor(
    private val read: suspend (String) -> List<BookmarkCategory>,
    private val latestError: () -> String,
    private val submitRemove: (String, String) -> FilmActionReceipt<List<BookmarkCategory>>,
) : ViewModel() {
    constructor(actions: FilmActionsViewModel, actionKey: FilmActionKey) : this(
        read = { url -> actions.awaitSettled(actionKey); CatalogRepository().details(url).bookmarks },
        latestError = { actions.latestError(actionKey) },
        submitRemove = { _, url -> actions.removeBookmarks(actionKey, url) },
    )
    companion object {
        internal fun forTests(read: suspend (String) -> List<BookmarkCategory>, latestError: () -> String,
            submitRemove: (String, String) -> FilmActionReceipt<List<BookmarkCategory>>) =
            BookmarkRemovalViewModel(read, latestError, submitRemove)
    }
    private val mutableState = MutableStateFlow(BookmarkRemovalState())
    val state = mutableState.asStateFlow()
    private val mutableReadiness = MutableStateFlow(false)
    val queryReady = mutableReadiness.asStateFlow()
    private var checking: Job? = null

    fun check(url: String) {
        if (mutableState.value.saving) return
        checking?.cancel()
        mutableReadiness.value = false
        mutableState.value = BookmarkRemovalState()
        checking = viewModelScope.launch {
            try {
                val categories = read(url)
                mutableReadiness.value = true
                mutableState.value = BookmarkRemovalState(loading = false, categories = categories,
                    error = latestError())
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = BookmarkRemovalState(loading = false, error = networkErrorMessage(failure)) }
        }
    }

    fun remove(provider: String, url: String) {
        val before = mutableState.value
        if (before.loading || before.saving || !mutableReadiness.value || !before.hasMembership) return
        val receipt = submitRemove(provider, url)
        mutableState.value = before.copy(saving = receipt.accepted)
        viewModelScope.launch {
            try {
                val remaining = receipt.await()
                mutableState.value = BookmarkRemovalState(loading = false, categories = remaining, removed = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.value = before.copy(error = networkErrorMessage(failure)) }
        }
    }
}
