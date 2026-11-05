package com.bobyl.tv

import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owner = AccountSession("https://favorites-test.example/", "owner")
    private val categories = listOf(BookmarkCategory("0", "Всё", "${owner.provider}favorites/", 12))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun unchangedReentryReusesLoadedCategoriesEvenWithNonzeroForegroundToken() = runTest(dispatcher) {
        val forces = mutableListOf<Boolean>()
        val model = FavoritesViewModel({ _, force -> forces += force; categories }, MutableStateFlow(emptyMap()), { owner })
        try {
            model.open(owner.provider, 4); runCurrent()
            repeat(3) { model.open(owner.provider, 4) }; runCurrent()
            assertEquals(listOf(true), forces)
            assertEquals(categories, model.state.value.categories)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun hiddenMutationMarksDirtyWithoutFetchingAndNextOpenConsumesOnce() = runTest(dispatcher) {
        val changes = MutableStateFlow<Map<AccountSession, Int>>(emptyMap())
        val forces = mutableListOf<Boolean>()
        val model = FavoritesViewModel({ _, force -> forces += force; categories }, changes, { owner })
        try {
            model.open(owner.provider); runCurrent()
            changes.value = mapOf(owner to 2); runCurrent()
            assertTrue(model.state.value.dirty)
            assertEquals(listOf(false), forces)
            model.open(owner.provider); runCurrent()
            model.open(owner.provider); runCurrent()
            assertEquals(listOf(false, true), forces)
            assertFalse(model.state.value.dirty)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun synchronousEventReadAndInflightEventCannotBeAcknowledgedByOldResponse() = runTest(dispatcher) {
        val changes = MutableStateFlow<Map<AccountSession, Int>>(emptyMap())
        val response = CompletableDeferred<List<BookmarkCategory>>()
        var reads = 0
        val model = FavoritesViewModel({ _, _ -> if (++reads == 1) response.await() else categories }, changes, { owner })
        try {
            model.open(owner.provider); runCurrent()
            changes.value = mapOf(owner to 1)
            model.open(owner.provider) // Before the collector receives the event.
            response.complete(categories); runCurrent()
            assertTrue(model.state.value.dirty)
            model.open(owner.provider); runCurrent()
            assertEquals(2, reads)
            assertFalse(model.state.value.dirty)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun failedRefreshKeepsCardsAndRequiresExplicitRetryForSameRequest() = runTest(dispatcher) {
        val changes = MutableStateFlow<Map<AccountSession, Int>>(emptyMap())
        var reads = 0
        val model = FavoritesViewModel({ _, _ -> if (++reads == 2) throw IOException("offline") else categories }, changes, { owner })
        try {
            model.open(owner.provider); runCurrent()
            changes.value = mapOf(owner to 1); model.open(owner.provider); runCurrent()
            assertEquals(categories, model.state.value.categories)
            assertEquals("offline", model.state.value.error)
            model.open(owner.provider); runCurrent()
            assertEquals(2, reads)
            model.retry(owner.provider); runCurrent()
            assertEquals(3, reads)
            assertEquals("", model.state.value.error)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun changedAccountCannotReceiveLateOldResponseOrAnotherOwnersDirtyFlag() = runTest(dispatcher) {
        val changes = MutableStateFlow<Map<AccountSession, Int>>(emptyMap())
        var active = owner
        val old = CompletableDeferred<List<BookmarkCategory>>()
        var reads = 0
        val model = FavoritesViewModel({ _, _ -> if (++reads == 1) withContext(NonCancellable) { old.await() }
            else listOf(categories.single().copy(count = 2)) }, changes, { active })
        try {
            model.open(owner.provider); runCurrent()
            active = owner.copy(token = "new-owner")
            model.open(owner.provider); runCurrent()
            old.complete(categories); runCurrent()
            changes.value = mapOf(owner to 99); runCurrent()
            assertEquals(2, model.state.value.categories.single().count)
            assertEquals(active, model.state.value.session)
            assertFalse(model.state.value.dirty)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun initialFailureDoesNotCreateAutomaticRequestLoop() = runTest(dispatcher) {
        var reads = 0
        val model = FavoritesViewModel({ _, _ -> reads++; throw IOException("offline") }, MutableStateFlow(emptyMap()), { owner })
        try {
            model.open(owner.provider); runCurrent()
            repeat(4) { model.open(owner.provider) }; runCurrent()
            assertEquals(1, reads)
            model.retry(owner.provider); runCurrent()
            assertEquals(2, reads)
        } finally { model.viewModelScope.cancel() }
    }
}
