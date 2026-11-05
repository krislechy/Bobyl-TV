package com.bobyl.tv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.CatalogFilm
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PagedFilmsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private fun film(id: String) = CatalogFilm(id, "https://example.org/films/$id.html", id, "", "", "", "", "")
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun loadedPagesSurviveReentryWithoutAnyLoaderCallsOrDuplicatedRows() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        val load: suspend (Int, Boolean) -> List<CatalogFilm> = { page, _ -> calls += page; listOf(film(page.toString())) }
        val model = PagedFilmsViewModel(SavedStateHandle(), dispatcher)
        try {
            model.open("owner|category|sort", load = load); runCurrent()
            model.more(); runCurrent(); model.more(); runCurrent()
            model.open("owner|category|sort", load = load); runCurrent()
            assertEquals(listOf(1, 2, 3), calls)
            assertEquals(3, model.state.value.page)
            assertEquals(listOf("1", "2", "3"), model.state.value.films.map { it.id })
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun explicitRevisionRefreshesAllLoadedPagesWithForceAndKeepsPositionDepth() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val load: suspend (Int, Boolean) -> List<CatalogFilm> = { page, force -> calls += page to force; listOf(film(page.toString())) }
        val model = PagedFilmsViewModel(SavedStateHandle(), dispatcher)
        try {
            model.open("owner|category", load = load); runCurrent(); model.more(); runCurrent()
            model.open("owner|category", 1, load = load); runCurrent()
            assertEquals(listOf(1 to false, 2 to false, 1 to true, 2 to true), calls)
            assertEquals(2, model.state.value.page)
            assertEquals(2, model.state.value.films.size)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun changedFilterCannotAppendToOldRowsOrAcceptItsLateResponse() = runTest(dispatcher) {
        val old = CompletableDeferred<List<CatalogFilm>>()
        val model = PagedFilmsViewModel(SavedStateHandle(), dispatcher)
        try {
            model.open("owner|old-filter") { _, _ -> withContext(NonCancellable) { old.await() } }; runCurrent()
            model.open("owner|new-filter") { _, _ -> listOf(film("new")) }; runCurrent()
            old.complete(listOf(film("old"))); runCurrent()
            assertEquals("owner|new-filter", model.state.value.token)
            assertEquals(listOf("new"), model.state.value.films.map { it.id })
            assertEquals(1, model.state.value.page)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun failedMoreKeepsRowsAndOnlyExplicitRetryLoadsSamePageAgain() = runTest(dispatcher) {
        var fail = true
        val pages = mutableListOf<Int>()
        val load: suspend (Int, Boolean) -> List<CatalogFilm> = { page, _ ->
            pages += page; if (page == 2 && fail) throw IOException("offline"); listOf(film(page.toString())) }
        val model = PagedFilmsViewModel(SavedStateHandle(), dispatcher)
        try {
            model.open("owner|category", load = load); runCurrent(); model.more(); runCurrent()
            assertEquals(listOf("1"), model.state.value.films.map { it.id })
            model.open("owner|category", load = load); model.more(); runCurrent()
            assertEquals(listOf(1, 2), pages)
            fail = false; model.retry(); runCurrent()
            assertEquals(listOf(1, 2, 2), pages)
            assertEquals(listOf("1", "2"), model.state.value.films.map { it.id })
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun failedFullRefreshKeepsEveryPreviouslyVisiblePage() = runTest(dispatcher) {
        val model = PagedFilmsViewModel(SavedStateHandle(), dispatcher)
        val load: suspend (Int, Boolean) -> List<CatalogFilm> = { page, force ->
            if (force && page == 2) throw IOException("offline"); listOf(film(if (force) "fresh$page" else "$page")) }
        try {
            model.open("owner|category", load = load); runCurrent(); model.more(); runCurrent()
            model.open("owner|category", 1, load = load); runCurrent()
            assertEquals(listOf("1", "2"), model.state.value.films.map { it.id })
            assertEquals("offline", model.state.value.error)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun savedPageDepthLoadsSequentiallyBeforePublishingRestoredContent() = runTest(dispatcher) {
        val response = CompletableDeferred<List<CatalogFilm>>()
        val saved = SavedStateHandle(mapOf("token" to "owner|category", "page" to 3))
        val model = PagedFilmsViewModel(saved, dispatcher)
        try {
            model.open("owner|category") { page, _ -> if (page == 2) response.await() else listOf(film(page.toString())) }
            runCurrent()
            assertTrue(model.state.value.restoring)
            assertTrue(model.state.value.films.isEmpty())
            response.complete(listOf(film("2"))); runCurrent()
            assertEquals(listOf("1", "2", "3"), model.state.value.films.map { it.id })
            assertFalse(model.state.value.restoring)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun ownerFenceRejectsResponseEvenBeforeNewScreenRequestStarts() = runTest(dispatcher) {
        var valid = true
        val response = CompletableDeferred<List<CatalogFilm>>()
        val model = PagedFilmsViewModel(SavedStateHandle(), dispatcher)
        try {
            model.open("owner|category", current = { valid }) { _, _ -> response.await() }; runCurrent()
            valid = false
            response.complete(listOf(film("old-account"))); runCurrent()
            assertTrue(model.state.value.films.isEmpty())
            assertFalse(model.state.value.busy)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun revisionChangeDuringReadStaysDirtyAndNextOpenRefreshesInsteadOfAppending() = runTest(dispatcher) {
        val response = CompletableDeferred<List<CatalogFilm>>()
        val calls = mutableListOf<Pair<Int, Boolean>>()
        val load: suspend (Int, Boolean) -> List<CatalogFilm> = { page, force ->
            calls += page to force; if (calls.size == 1) response.await() else listOf(film("fresh")) }
        val model = PagedFilmsViewModel(SavedStateHandle(), dispatcher)
        try {
            model.open("owner|category", load = load); runCurrent()
            model.open("owner|category", 1, load = load)
            response.complete(listOf(film("old"))); runCurrent()
            assertTrue(model.state.value.dirty)
            model.more(); runCurrent()
            assertEquals(listOf(1 to false, 1 to true), calls)
            assertEquals(listOf("fresh"), model.state.value.films.map { it.id })
        } finally { model.viewModelScope.cancel() }
    }
}
