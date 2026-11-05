package com.bobyl.tv

import com.bobyl.tv.data.AccountSessionGuard
import com.bobyl.tv.data.BookmarkCategory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class FilmActionsViewModelTest {
    private val origin = "https://actions-test.example/"

    @Test fun acceptedFifoSurvivesCancelledWindowWaitAndExecutesEachCommandOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val guard = AccountSessionGuard(dispatcher)
        val queue = FilmActionQueue(owner, guard, dispatcher)
        val key = FilmActionKey.of(guard.current(origin), "42")
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        try {
            val first = queue.submit(key) { calls += 1; release.await(); "first" }
            val second = queue.submit(key) { calls += 2; "second" }
            val windowWait = launch { first.await() }
            runCurrent()
            assertEquals(listOf(1), calls)
            windowWait.cancel()
            release.complete(Unit)
            runCurrent()
            assertEquals("first", first.await())
            assertEquals("second", second.await())
            assertEquals(listOf(1, 2), calls)
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun capacityRejectsClearlyAndOneFailureDoesNotKillFollowingCommands() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val guard = AccountSessionGuard(dispatcher)
        val queue = FilmActionQueue(owner, guard, dispatcher, capacity = 2)
        val key = FilmActionKey.of(guard.current(origin), "42")
        val release = CompletableDeferred<Unit>()
        try {
            val failed = queue.submit<Int>(key) { release.await(); throw IOException("offline") }
            val next = queue.submit(key) { 7 }
            val rejected = queue.submit(key) { error("Rejected work must never run") }
            assertFalse(rejected.accepted)
            assertTrue(rejected.completion.isCompleted)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertTrue(failed.completion.await().exceptionOrNull() is IOException)
            assertEquals(7, next.await())
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun barrierWaitsOnlyItsFilmAndTerminalJournalIsBounded() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val guard = AccountSessionGuard(dispatcher)
        val queue = FilmActionQueue(owner, guard, dispatcher)
        val a = FilmActionKey.of(guard.current(origin), "42")
        val b = FilmActionKey.of(a.session, "43")
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        try {
            queue.submit(a) { releaseA.await() }
            queue.submit(b) { releaseB.await() }
            val waitA = async { queue.awaitSettled(FilmActionKey.of(a.session, "${origin}films/42-name.html")) }
            val unrelated = async { queue.awaitSettled(FilmActionKey.of(a.session, "99")) }
            runCurrent()
            assertFalse(waitA.isCompleted)
            assertTrue(unrelated.isCompleted)
            releaseA.complete(Unit)
            runCurrent()
            assertTrue(waitA.isCompleted)
            assertTrue(queue.status.value.any { it.key == b && it.pending })
            releaseB.complete(Unit)
            runCurrent()
            repeat(40) { index -> queue.submit(FilmActionKey.of(a.session, index.toString())) { index }; runCurrent() }
            assertTrue(queue.status.value.size <= 16)
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun shutdownDrainsAllPermitsEvenWhenWorkerNeverStarted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (startWorker in listOf(false, true)) {
            val owner = CoroutineScope(SupervisorJob() + dispatcher)
            val guard = AccountSessionGuard(dispatcher)
            val queue = FilmActionQueue(owner, guard, dispatcher)
            val key = FilmActionKey.of(guard.current(origin), "42")
            val first = queue.submit(key) { awaitCancellation() }
            val second = queue.submit(key) { error("Queued work must not start at shutdown") }
            if (startWorker) runCurrent()
            owner.cancel()
            runCurrent()
            assertTrue(first.completion.isCompleted)
            assertTrue(second.completion.isCompleted)
            val transition = async { guard.change(origin) { "changed" } }
            runCurrent()
            assertTrue(transition.isCompleted)
            assertEquals("changed", transition.await())
        }
    }

    @Test fun submittingDoesNotRunOrWaitForBlockedIoOnCaller() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val guard = AccountSessionGuard()
        val queue = FilmActionQueue(owner, guard)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        try {
            val receipt = queue.submit(FilmActionKey.of(guard.current(origin), "42")) {
                assertNotSame(caller, Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                7
            }
            assertTrue(receipt.accepted)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(receipt.completion.isCompleted)
            release.countDown()
            assertEquals(7, receipt.await())
        } finally { release.countDown(); owner.cancel() }
    }

    @Test fun shutdownAfterDeliveryToParkedReceiverStillFinishesReceiptAndSessionPermit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val guard = AccountSessionGuard(dispatcher)
        val queue = FilmActionQueue(owner, guard, dispatcher)
        val key = FilmActionKey.of(guard.current(origin), "42")
        runCurrent() // Worker is suspended in Channel.receive, not merely queued for initial launch.
        var executed = false
        val delivered = queue.submit(key) { executed = true }
        owner.cancel() // Element is in the receiver continuation; it is no longer in the channel buffer.
        runCurrent()
        assertFalse(executed)
        assertTrue(delivered.completion.isCompleted)
        assertTrue(delivered.completion.await().isFailure)
        val transition = async { guard.change(origin) { "ready" } }
        runCurrent()
        assertTrue(transition.isCompleted)
        assertEquals("ready", transition.await())
    }

    @Test fun successfulFreshBookmarkCheckCanDeleteWhilePriorCommandErrorRemainsVisible() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val category = BookmarkCategory("1", "List", checked = true)
        var submissions = 0
        val model = BookmarkRemovalViewModel.forTests(
            read = { listOf(category) },
            latestError = { "Предыдущая команда не завершена: offline" },
            submitRemove = { _, _ ->
                submissions++
                FilmActionReceipt("remove", true,
                    CompletableDeferred(Result.success(listOf(category.copy(checked = false)))))
            },
        )
        try {
            model.check("${origin}films/42-name.html")
            runCurrent()
            assertTrue(model.queryReady.value)
            assertTrue(model.state.value.error.contains("Предыдущая команда"))
            model.remove(origin, "${origin}films/42-name.html")
            runCurrent()
            assertEquals(1, submissions)
            assertTrue(model.state.value.removed)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun failedInitialBookmarkCheckBlocksDeleteUntilExplicitSuccessfulRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var reads = 0
        var submissions = 0
        val model = BookmarkRemovalViewModel.forTests(
            read = {
                if (++reads == 1) throw IOException("offline")
                listOf(BookmarkCategory("1", "List", checked = true))
            },
            latestError = { "" },
            submitRemove = { _, _ ->
                submissions++
                FilmActionReceipt("remove", true, CompletableDeferred(Result.success(emptyList<BookmarkCategory>())))
            },
        )
        try {
            model.check("${origin}films/42-name.html")
            runCurrent()
            assertFalse(model.queryReady.value)
            model.remove(origin, "${origin}films/42-name.html")
            runCurrent()
            assertEquals(0, submissions)
            model.check("${origin}films/42-name.html")
            runCurrent()
            assertTrue(model.queryReady.value)
            model.remove(origin, "${origin}films/42-name.html")
            runCurrent()
            assertEquals(1, submissions)
            assertTrue(model.state.value.removed)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
