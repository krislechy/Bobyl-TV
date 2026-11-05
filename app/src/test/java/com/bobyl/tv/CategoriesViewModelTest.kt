package com.bobyl.tv

import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owner = AccountSession("https://categories-test.example/", "owner")
    private val filters = BrowseFilters(listOf(BrowseCategory("Фильмы", owner.provider + "films/", emptyList())), listOf("2026"))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun reentryWithSameNonzeroForegroundTokenDoesNotRefetchFilters() = runTest(dispatcher) {
        val calls = mutableListOf<Boolean>()
        val model = CategoriesViewModel({ _, force -> calls += force; filters }, { owner })
        try {
            model.open(owner.provider, 2); runCurrent()
            repeat(3) { model.open(owner.provider, 2) }; runCurrent()
            assertEquals(listOf(true), calls)
            model.open(owner.provider, 3); runCurrent()
            assertEquals(listOf(true, true), calls)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun failedRefreshPreservesFiltersAndDoesNotRetryImplicitly() = runTest(dispatcher) {
        var calls = 0
        val model = CategoriesViewModel({ _, _ -> if (++calls == 2) throw IOException("offline") else filters }, { owner })
        try {
            model.open(owner.provider); runCurrent()
            model.open(owner.provider, 1); runCurrent()
            assertEquals(filters, model.state.value.filters)
            model.open(owner.provider, 1); runCurrent()
            assertEquals(2, calls)
            model.retry(owner.provider); runCurrent()
            assertEquals(3, calls)
            assertNull(model.state.value.error)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun foregroundChangeDuringReadIsConsumedByNextOpen() = runTest(dispatcher) {
        val response = CompletableDeferred<BrowseFilters>()
        var calls = 0
        val model = CategoriesViewModel({ _, _ -> if (++calls == 1) response.await() else filters }, { owner })
        try {
            model.open(owner.provider); runCurrent()
            model.open(owner.provider, 1)
            response.complete(filters); runCurrent()
            model.open(owner.provider, 1); runCurrent()
            assertEquals(2, calls)
            model.open(owner.provider, 1); runCurrent()
            assertEquals(2, calls)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun cancelledOldProviderResponseCannotReplaceNewContextFilters() = runTest(dispatcher) {
        var ownerNow = owner
        val response = CompletableDeferred<BrowseFilters>()
        var calls = 0
        val next = filters.copy(years = listOf("2025"))
        val model = CategoriesViewModel({ _, _ -> if (++calls == 1) withContext(NonCancellable) { response.await() } else next }, { ownerNow })
        try {
            model.open(owner.provider); runCurrent()
            ownerNow = owner.copy(provider = "https://other.example/", token = "new")
            model.open(ownerNow.provider); runCurrent()
            response.complete(filters); runCurrent()
            assertEquals(next, model.state.value.filters)
            assertEquals(ownerNow, model.state.value.session)
        } finally { model.viewModelScope.cancel() }
    }
}
