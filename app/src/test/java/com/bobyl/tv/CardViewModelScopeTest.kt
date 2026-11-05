package com.bobyl.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.AccountSession
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CardViewModelScopeTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    private class QueryModel : ViewModel() {
        var clears = 0
        val query = viewModelScope.launch { awaitCancellation() }
        override fun onCleared() { clears++ }
    }
    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = QueryModel() as T
    }

    @Test fun buttonPanelAndChildWindowShareStoreButNestedFilmDoesNot() = runTest(dispatcher) {
        val parent = CardViewModelStoreOwner()
        val nested = CardViewModelStoreOwner()
        try {
            val button = ViewModelProvider(parent, factory).get("rating:42", QueryModel::class.java)
            val panel = ViewModelProvider(parent, factory).get("rating:42", QueryModel::class.java)
            val childWindow = ViewModelProvider(parent, factory).get("rating:42", QueryModel::class.java)
            val childFilm = ViewModelProvider(nested, factory).get("rating:42", QueryModel::class.java)
            runCurrent()
            assertSame(button, panel)
            assertSame(panel, childWindow)
            assertNotSame(button, childFilm)
            nested.close()
            runCurrent()
            assertTrue(childFilm.query.isCancelled)
            assertTrue(button.query.isActive)
            parent.close()
            runCurrent()
            assertTrue(button.query.isCancelled)
            assertEquals(1, button.clears)
            parent.close()
            assertEquals(1, button.clears)
        } finally { parent.close(); nested.close() }
    }

    @Test fun reopeningDoesNotReuseClosedModelsOrTheirQueries() = runTest(dispatcher) {
        val first = CardViewModelStoreOwner()
        val old = ViewModelProvider(first, factory).get("history:42", QueryModel::class.java)
        runCurrent()
        first.close()
        runCurrent()
        val second = CardViewModelStoreOwner()
        try {
            val fresh = ViewModelProvider(second, factory).get("history:42", QueryModel::class.java)
            assertNotSame(old, fresh)
            assertTrue(old.query.isCancelled)
        } finally { second.close() }
    }

    @Test fun numericIdsUrlsAndMirrorAliasesUseOneFilmKey() {
        val session = AccountSession("https://example.org/", "session")
        val expected = FilmActionKey.of(session, "42")
        assertEquals(expected, FilmActionKey.of(session, "https://example.org/films/42-first-name.html"))
        assertEquals(expected, FilmActionKey.of(session, "https://mirror.example/series/42-alias.html"))
        assertNotEquals(expected, FilmActionKey.of(session.copy(token = "other-account"), "42"))
    }
}
