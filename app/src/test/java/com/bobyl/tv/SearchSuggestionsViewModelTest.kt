package com.bobyl.tv

import com.bobyl.tv.data.CatalogFilm
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class SearchSuggestionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun film(id: String) = CatalogFilm(id, "https://example.org/$id", id, "", "", "", "", "")

    @Test fun rapidTypingWaits400msAndLoadsOnlyLatestValue() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val model = SearchSuggestionsViewModel { _, value -> calls += value; listOf(film(value)) }
        model.update("example.org", "m")
        advanceUntilIdle()
        model.update("example.org", "ma")
        advanceTimeBy(250)
        model.update("example.org", "matrix")
        advanceTimeBy(399)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertFalse(model.state.value.loading)
        advanceTimeBy(1)
        runCurrent()
        model.update("example.org", "matrix")
        advanceUntilIdle()
        assertEquals(listOf("matrix"), calls)
        assertEquals("matrix", model.state.value.films.single().id)
    }

    @Test fun newProviderRejectsLateFailureOfPreviousRequest() = runTest(dispatcher) {
        val old = CompletableDeferred<Unit>()
        val current = CompletableDeferred<List<CatalogFilm>>()
        val model = SearchSuggestionsViewModel { provider, _ ->
            if (provider == "old.org") withContext(NonCancellable) {
                old.await()
                throw IOException("late failure")
            }
            current.await()
        }
        model.update("old.org", "matrix")
        advanceTimeBy(400); runCurrent()
        model.update("new.org", "matrix")
        advanceTimeBy(400); runCurrent()
        old.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.unavailable)
        current.complete(listOf(film("new")))
        advanceUntilIdle()
        assertEquals("new", model.state.value.films.single().id)
    }

    @Test fun shortInputAndLeavingScreenDiscardLateSuccess() = runTest(dispatcher) {
        val response = CompletableDeferred<List<CatalogFilm>>()
        val model = SearchSuggestionsViewModel { _, _ -> withContext(NonCancellable) { response.await() } }
        model.update("example.org", "matrix")
        advanceTimeBy(400); runCurrent()
        model.update("example.org", " ")
        response.complete(listOf(film("old")))
        advanceUntilIdle()
        assertEquals(SearchSuggestionsState(), model.state.value)
        model.update("example.org", "again")
        model.stop()
        advanceUntilIdle()
        assertEquals(SearchSuggestionsState(), model.state.value)
    }

    @Test fun failureIsVisibleAndNextInputRecovers() = runTest(dispatcher) {
        val model = SearchSuggestionsViewModel { _, value ->
            if (value == "fail") throw IOException("offline")
            listOf(film(value))
        }
        model.update("example.org", "fail")
        advanceUntilIdle()
        assertTrue(model.state.value.unavailable)
        assertFalse(model.state.value.loading)
        model.update("example.org", "next")
        advanceUntilIdle()
        assertFalse(model.state.value.unavailable)
        assertEquals("next", model.state.value.films.single().id)
    }
}
