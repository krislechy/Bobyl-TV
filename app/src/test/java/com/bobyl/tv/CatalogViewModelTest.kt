package com.bobyl.tv

import androidx.lifecycle.SavedStateHandle
import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.CatalogSection
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun film(id: String) = CatalogFilm(id, "https://example.org/$id", id, "", "", "", "", "")

    private fun savedCatalog(page: Int) = SavedStateHandle(mapOf(
        "loadedPage" to page,
        "loadedRequest" to listOf("example.org", CatalogSection.Popular.name, "", "0"),
    ))

    @Test fun restoredCatalogLoadsThroughSavedPageBeforePublishing() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        val second = CompletableDeferred<List<CatalogFilm>>()
        val model = CatalogViewModel({ _, _, page, _, _ ->
            calls += page
            if (page == 2) second.await() else listOf(film(page.toString()))
        }, dispatcher, savedCatalog(3))
        assertTrue(model.state.value.restoring)
        model.load("example.org", CatalogSection.Popular, "", kind = "0")
        runCurrent()
        assertTrue(model.state.value.restoring)
        assertTrue(model.state.value.films.isEmpty())
        second.complete(listOf(film("2")))
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), calls)
        assertEquals(3, model.state.value.page)
        assertEquals(listOf("1", "2", "3"), model.state.value.films.map { it.id })
        assertFalse(model.state.value.restoring)
    }

    @Test fun changedFilterDoesNotRestorePagesFromOldCatalog() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        val model = CatalogViewModel({ _, _, page, _, _ -> calls += page; listOf(film("one")) }, dispatcher, savedCatalog(5))
        model.load("example.org", CatalogSection.Popular, "", kind = "2")
        advanceUntilIdle()
        assertEquals(listOf(1), calls)
        assertFalse(model.state.value.restoring)
    }

    @Test fun restorationFailureRetriesFailedPageWithoutReloadingSuccessfulPages() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        var fail = true
        val model = CatalogViewModel({ _, _, page, _, _ ->
            calls += page
            if (page == 2 && fail) throw IOException("offline")
            listOf(film(page.toString()))
        }, dispatcher, savedCatalog(3))
        model.load("example.org", CatalogSection.Popular, "", kind = "0")
        advanceUntilIdle()
        assertEquals(1, model.state.value.page)
        assertNotNull(model.state.value.error)
        fail = false
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 2, 3), calls)
        assertEquals(3, model.state.value.page)
        assertNull(model.state.value.error)
    }

    @Test fun selectionRestoresFromSavedValuesWithoutStartingNetwork() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        var calls = 0
        val model = CatalogViewModel({ _, _, _, _, _ -> calls++; emptyList() }, dispatcher, saved)
        model.selectNewsSection(CatalogSection.Popular)
        model.selectKind("2")
        model.editSearch("  matrix  ")
        model.submitSearch()
        model.editSearch("unfinished draft")
        val restored = CatalogViewModel(
            { _, _, _, _, _ -> calls++; emptyList() }, dispatcher,
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
        )
        assertEquals(model.selection.value, restored.selection.value)
        assertEquals("matrix", restored.selection.value.query)
        assertEquals("unfinished draft", restored.selection.value.searchDraft)
        advanceUntilIdle()
        assertEquals(0, calls)
    }

    @Test fun editingDraftDoesNotReplaceSubmittedQuery() = runTest(dispatcher) {
        val model = CatalogViewModel(computationDispatcher = dispatcher)
        model.editSearch("first")
        model.submitSearch()
        model.editSearch("second")
        assertEquals("first", model.selection.value.query)
        model.editSearch("  ")
        model.submitSearch()
        assertEquals("", model.selection.value.query)
    }

    @Test fun sessionRefreshReloadsOnceWithoutResettingSelection() = runTest(dispatcher) {
        var calls = 0
        val model = CatalogViewModel({ _, _, _, _, _ -> calls++; listOf(film("one")) }, dispatcher)
        model.selectKind("2")
        model.editSearch("draft")
        model.load("example.org", CatalogSection.Popular, "", kind = "2")
        advanceUntilIdle()
        repeat(2) { model.load("example.org", CatalogSection.Popular, "", kind = "2", sessionRevision = 1) }
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals("2", model.selection.value.kind)
        assertEquals("draft", model.selection.value.searchDraft)
    }

    @Test fun repeatedSelectionAndConcurrentPaginationDoNotDuplicateRequests() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        val second = CompletableDeferred<List<CatalogFilm>>()
        val model = CatalogViewModel({ _, _, page, _, _ ->
            calls += page
            if (page == 1) listOf(film("one")) else second.await()
        }, dispatcher)
        repeat(3) { model.load("example.org", CatalogSection.Popular, "") }
        advanceUntilIdle()
        model.load("example.org", CatalogSection.Popular, "")
        repeat(3) { model.loadMore() }
        runCurrent()
        assertEquals(listOf(1, 2), calls)
        second.complete(listOf(film("two")))
        advanceUntilIdle()
        assertEquals(listOf("one", "two"), model.state.value.films.map { it.id })
        assertEquals(2, model.state.value.page)
    }

    @Test fun failedPageRequiresExplicitRetryAndKeepsExistingCards() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        var fail = true
        val model = CatalogViewModel({ _, _, page, _, _ ->
            calls += page
            if (page == 2 && fail) throw IOException("offline")
            listOf(film(page.toString()))
        }, dispatcher)
        model.load("example.org", CatalogSection.Popular, "")
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        assertNotNull(model.state.value.error)
        assertEquals(listOf("1"), model.state.value.films.map { it.id })
        model.loadMore()
        model.load("example.org", CatalogSection.Popular, "")
        advanceUntilIdle()
        assertEquals(listOf(1, 2), calls)
        fail = false
        repeat(2) { model.retry() }
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 2), calls)
        assertNull(model.state.value.error)
        assertEquals(listOf("1", "2"), model.state.value.films.map { it.id })
    }

    @Test fun lateFailureFromCancelledQueryCannotOverwriteNewResults() = runTest(dispatcher) {
        val oldResponse = CompletableDeferred<Unit>()
        val model = CatalogViewModel({ _, _, _, query, _ ->
            if (query == "old") withContext(NonCancellable) {
                oldResponse.await()
                throw IOException("late failure")
            }
            listOf(film(query))
        }, dispatcher)
        model.load("example.org", CatalogSection.Latest, "old")
        runCurrent()
        model.load("example.org", CatalogSection.Latest, "new")
        advanceUntilIdle()
        oldResponse.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", model.state.value.request?.query)
        assertEquals("new", model.state.value.films.single().id)
        assertNull(model.state.value.error)
    }

    @Test fun clearPreventsLateResponseAndFurtherPagination() = runTest(dispatcher) {
        val response = CompletableDeferred<List<CatalogFilm>>()
        var calls = 0
        val model = CatalogViewModel({ _, _, _, _, _ ->
            calls++
            withContext(NonCancellable) { response.await() }
        }, dispatcher)
        model.load("example.org", CatalogSection.Latest, "query")
        runCurrent()
        model.clear()
        response.complete(listOf(film("old")))
        advanceUntilIdle()
        model.loadMore()
        assertEquals(1, calls)
        assertNull(model.state.value.request)
        assertTrue(model.state.value.films.isEmpty())
        assertFalse(model.state.value.hasMore)
        assertFalse(model.state.value.loading)
    }

    @Test fun duplicateOnlyPageStopsAutomaticPagination() = runTest(dispatcher) {
        var calls = 0
        val model = CatalogViewModel({ _, _, _, _, _ -> calls++; listOf(film("same")) }, dispatcher)
        model.load("example.org", CatalogSection.Popular, "")
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(1, model.state.value.films.size)
        assertFalse(model.state.value.hasMore)
    }
}
