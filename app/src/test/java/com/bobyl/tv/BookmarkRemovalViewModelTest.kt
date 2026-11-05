package com.bobyl.tv

import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.BookmarkCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkRemovalViewModelTest {
    @Test fun noMembershipDoesNotShowConfirmationOrSubmitRemovalEvenWithOldError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var submissions = 0
        val model = BookmarkRemovalViewModel.forTests(
            read = { listOf(BookmarkCategory("1", "Список", checked = false)) },
            latestError = { "Старая ошибка" },
            submitRemove = { _, _ ->
                submissions++
                FilmActionReceipt("remove", true, CompletableDeferred(Result.success(emptyList<BookmarkCategory>())))
            })
        try {
            model.check("https://example.org/films/1-name.html"); runCurrent()
            assertTrue(model.queryReady.value)
            assertFalse(model.state.value.showConfirmation)
            model.remove("https://example.org/", "https://example.org/films/1-name.html"); runCurrent()
            assertEquals(0, submissions)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun pendingQueryDoesNotDisplayRemovalQuestionBeforeMembershipIsKnown() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val result = CompletableDeferred<List<BookmarkCategory>>()
        val model = BookmarkRemovalViewModel.forTests(read = { result.await() }, latestError = { "" },
            submitRemove = { _, _ -> error("not expected") })
        try {
            model.check("https://example.org/films/1-name.html"); runCurrent()
            assertTrue(model.state.value.loading)
            assertFalse(model.state.value.showConfirmation)
            result.complete(listOf(BookmarkCategory("1", "Список", checked = true))); runCurrent()
            assertTrue(model.queryReady.value)
            assertTrue(model.state.value.showConfirmation)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
