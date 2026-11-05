package com.bobyl.tv

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.bobyl.tv.data.AccountSessionGuard
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

class PlaybackResourceTest {
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runNext() { check(queue.isNotEmpty()); queue.removeFirst().run() }
    }

    private class Resource(private val closeFailure: Throwable? = null) : AutoCloseable {
        var closes = 0
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    @Test fun cancellationAfterCreationBeforeCallerResumeClosesUnadoptedResource() {
        for (interruptible in listOf(false, true)) {
            val caller = QueueDispatcher()
            val worker = QueueDispatcher()
            val scope = CoroutineScope(Job() + caller)
            val resource = Resource()
            var adopted = false
            val job = scope.launch {
                acquirePlaybackResource(worker, interruptible, create = { resource }, adopt = { adopted = true })
            }
            try {
                caller.runNext() // Creation is queued on the worker.
                worker.runNext() // Resource exists; return to caller is queued.
                assertEquals(0, resource.closes)
                job.cancel()
                caller.runNext()
                assertTrue(job.isCompleted)
                assertTrue(job.isCancelled)
                assertFalse(adopted)
                assertEquals(1, resource.closes)
            } finally { scope.cancel() }
        }
    }

    @Test fun successfulAdoptionTransfersOwnershipWithoutClosing() {
        val caller = QueueDispatcher()
        val worker = QueueDispatcher()
        val scope = CoroutineScope(Job() + caller)
        val resource = Resource()
        var serviceOwned: Resource? = null
        var returned: Resource? = null
        val job = scope.launch { returned = acquirePlaybackResource(worker, create = { resource }, adopt = { serviceOwned = it }) }
        try {
            caller.runNext(); worker.runNext(); caller.runNext()
            assertTrue(job.isCompleted)
            assertFalse(job.isCancelled)
            assertSame(resource, serviceOwned)
            assertSame(resource, returned)
            assertEquals(0, resource.closes)
        } finally { scope.cancel(); serviceOwned?.close() }
    }

    @Test fun creationFailureNeverCallsAdopt() {
        val caller = QueueDispatcher()
        val worker = QueueDispatcher()
        val scope = CoroutineScope(Job() + caller)
        val failure = IllegalStateException("create failed")
        var actual: Throwable? = null
        var adopted = false
        scope.launch {
            try { acquirePlaybackResource<Resource>(worker, create = { throw failure }, adopt = { adopted = true }) }
            catch (caught: Throwable) { actual = caught }
        }
        try {
            caller.runNext(); worker.runNext(); caller.runNext()
            assertEquals(failure.javaClass, actual?.javaClass)
            assertEquals(failure.message, actual?.message)
            // Coroutine stacktrace recovery may copy the exception across withContext.
            assertTrue(generateSequence(actual) { it.cause }.any { it === failure })
            assertFalse(adopted)
        } finally { scope.cancel() }
    }

    @Test fun adoptionFailureClosesResourceAndPreservesOriginalFailure() {
        val caller = QueueDispatcher()
        val worker = QueueDispatcher()
        val scope = CoroutineScope(Job() + caller)
        val failure = IllegalStateException("adopt failed")
        val closeFailure = IllegalArgumentException("close failed")
        val resource = Resource(closeFailure)
        var actual: Throwable? = null
        scope.launch {
            try { acquirePlaybackResource(worker, create = { resource }, adopt = { throw failure }) }
            catch (caught: Throwable) { actual = caught }
        }
        try {
            caller.runNext(); worker.runNext(); caller.runNext()
            assertSame(failure, actual)
            assertEquals(listOf(closeFailure), actual!!.suppressed.toList())
            assertEquals(1, resource.closes)
        } finally { scope.cancel() }
    }

    @Test fun failedPlayerLaunchCleansUpPreparedResourceBeforeOwnershipTransfer() {
        val ownership = PlaybackLaunchOwnership()
        val resource = Resource()
        try {
            ownership.launch { throw IllegalStateException("player unavailable") }
            fail("Launch must fail")
        } catch (failure: IllegalStateException) { assertEquals("player unavailable", failure.message) }
        finally { ownership.cleanupBeforeTransfer { resource.close() } }
        assertEquals(1, resource.closes)
    }

    @Test fun successfulPlayerLaunchKeepsResourcesWhenSelectionQueueIsFull() {
        val worker = QueueDispatcher()
        val scope = CoroutineScope(Job() + worker)
        val sessions = AccountSessionGuard()
        val queue = FilmActionQueue(scope, sessions, worker, capacity = 1)
        val key = FilmActionKey.of(sessions.current("https://ownership.example/"), "42")
        val resource = Resource()
        val ownership = PlaybackLaunchOwnership()
        var launches = 0
        try {
            assertTrue(queue.submit(key) { Unit }.accepted)
            ownership.launch { launches++ }
            val selection = queue.submit(key, localFirst = true) { Unit }
            assertFalse(selection.accepted)
            assertTrue(selection.completion.isCompleted)
        } finally {
            ownership.cleanupBeforeTransfer { resource.close() }
            scope.cancel(); worker.runNext()
        }
        assertEquals(1, launches)
        assertEquals(0, resource.closes)
    }

    @Test fun successfulPlayerLaunchKeepsResourcesWhenAcceptedSelectionFailsImmediately() = runBlocking {
        val worker = QueueDispatcher()
        val scope = CoroutineScope(Job() + worker)
        val sessions = AccountSessionGuard()
        val queue = FilmActionQueue(scope, sessions, worker)
        val key = FilmActionKey.of(sessions.current("https://ownership.example/"), "42")
        val resource = Resource()
        val ownership = PlaybackLaunchOwnership()
        try {
            ownership.launch { Unit }
            val selection = queue.submit<Unit>(key, localFirst = true) { error("isolated selection write failure") }
            worker.runNext()
            assertTrue(selection.accepted)
            assertTrue(selection.completion.isCompleted)
            try { selection.await(); fail("Write must fail") }
            catch (failure: IllegalStateException) { assertEquals("isolated selection write failure", failure.message) }
        } finally {
            ownership.cleanupBeforeTransfer { resource.close() }
            scope.cancel(); worker.runNext()
        }
        assertEquals(0, resource.closes)
    }
}
