package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.AccountSessionGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HistoryCleanupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val origin = "https://cleanup.example/"
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun accountSwitchWaitsForTheWholeAcceptedClear() = runTest(dispatcher) {
        val guard = AccountSessionGuard(dispatcher)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cookie = "old-account"
        val observed = mutableListOf<String>()
        val changes = mutableListOf<Pair<Boolean, Boolean>>()
        val model = HistoryCleanupViewModel(RuntimeEnvironment.getApplication(), sessions = guard,
            clearAccount = { _, progress ->
                observed += cookie; progress(1, 2); started.complete(Unit)
                release.await()
                observed += cookie; progress(2, 2); 2
            }, notifyChange = { _, device, completed -> changes += device to completed })
        try {
            model.clear(origin, HistoryCleanupTarget.Account); started.await()
            val switch = async { guard.change(origin) { cookie = "new-account" } }
            runCurrent()
            assertFalse(switch.isCompleted)
            release.complete(Unit); switch.await()
            assertEquals(listOf("old-account", "old-account"), observed)
            assertEquals(listOf(false to true), changes)
            assertFalse(model.state.value.busy)
        } finally { release.complete(Unit); model.viewModelScope.cancel() }
    }

    @Test fun partialFailureInvalidatesWithoutClaimingThatHistoryIsEmpty() = runTest(dispatcher) {
        val guard = AccountSessionGuard(dispatcher)
        val changes = mutableListOf<Pair<Boolean, Boolean>>()
        val model = HistoryCleanupViewModel(RuntimeEnvironment.getApplication(), sessions = guard,
            clearAccount = { _, progress -> progress(1, 2); throw IOException("offline") },
            notifyChange = { _, device, completed -> changes += device to completed })
        try {
            model.clear(origin, HistoryCleanupTarget.Account); runCurrent()
            assertEquals(listOf(false to false), changes)
            assertFalse(model.state.value.busy)
            assertEquals(1, model.state.value.removed)
            assertTrue(model.state.value.message.isNotBlank())
        } finally { model.viewModelScope.cancel() }
    }
}
