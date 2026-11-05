package com.bobyl.tv.data

import com.bobyl.tv.releaseFilmResource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.lang.management.ManagementFactory
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionGuardTest {
    private val origin = "https://session-test.example/"
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queued = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun runNext(): Boolean = queued.pollFirst()?.let { it.run(); true } ?: false
    }

    @Test fun nativeTransitionWaitsAcceptedPrefixAndRejectsNewServerEffectsDuringChange() = runTest {
        val guard = AccountSessionGuard(StandardTestDispatcher(testScheduler))
        val before = guard.current(origin)
        val permit = guard.register(before)
        var committed = false
        val transition = async { guard.change(origin) { committed = true } }
        runCurrent()
        assertFalse(committed)
        assertFalse(guard.register(before).accepted)
        var sent = false
        guard.server(permit) { sent = true }
        guard.finish(permit)
        runCurrent()
        transition.await()
        assertTrue(sent)
        assertTrue(committed)
        assertNotEquals(before, guard.current(origin))
        assertFalse(guard.register(before).accepted)
    }

    @Test fun readonlyRestoreKeepsAcceptedSessionButFencesOldCookieResponses() = runTest {
        val guard = AccountSessionGuard(StandardTestDispatcher(testScheduler))
        val before = guard.current(origin)
        val permit = guard.register(before)
        val request = guard.requestCookies("${origin}films/42-name.html") { "old-cookie" }.first
        var restored = false
        guard.restore(origin, unchanged = { true }, commit = { restored = true })
        assertTrue(restored)
        assertEquals(before, guard.current(origin))
        assertFalse(guard.commitResponse(request) { fail("Old response must not restore cookies") })
        guard.server(permit) { /* The already accepted command still belongs to the same account. */ }
        guard.finish(permit)
        val fresh = guard.requestCookies(origin) { "current-cookie" }.first
        assertTrue(guard.commitResponse(fresh) { })
    }

    @Test fun websiteBlocksServerWritesAndOnlyCredentialChangesInvalidateAuthToken() = runTest {
        val guard = AccountSessionGuard(StandardTestDispatcher(testScheduler))
        var credentials = listOf("account-a", "credential-a")
        val before = guard.current(origin)
        val request = guard.requestCookies(origin) { "cookies-a" }.first
        val first = guard.openWebsite(origin) { credentials }
        assertFalse(guard.register(before).accepted)
        assertFalse(guard.commitResponse(request) { fail("Website owns the cookie jar") })
        first.close()
        first.close() // Idempotent release must not rotate or release another website.
        assertEquals(before, guard.current(origin))
        assertFalse(guard.commitResponse(request) { fail("Response from before website must remain stale") })
        val second = guard.openWebsite(origin) { credentials }
        credentials = listOf("account-b", "credential-b")
        second.close()
        assertNotEquals(before, guard.current(origin))
        assertFalse(guard.register(before).accepted)
    }

    @Test fun activityClearCannotLoseWebsiteReleaseEvenWhenCleanupStartsAfterCancellation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val guard = AccountSessionGuard(dispatcher)
        val lease = guard.openWebsite(origin) { listOf("account-a", "credential-a") }
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        owner.cancel()
        val cleanup = releaseFilmResource(owner, lease, dispatcher)
        runCurrent()
        assertTrue(cleanup.isCompleted)
        val transition = async { guard.change(origin) { "ready" } }
        runCurrent()
        assertEquals("ready", transition.await())
    }

    @Test fun failedWebsiteCredentialReadDoesNotLeaveAnUnreleasableLease() = runTest {
        val guard = AccountSessionGuard(StandardTestDispatcher(testScheduler))
        try {
            guard.openWebsite(origin) { error("Unavailable cookie store") }
            fail("Opening must fail")
        } catch (_: IllegalStateException) { }
        guard.change(origin) { }
        var failRead = false
        val lease = guard.openWebsite(origin) {
            if (failRead) error("Cookie read failed on release") else listOf("a", "credential")
        }
        failRead = true
        try { lease.close() } catch (_: IllegalStateException) { }
        guard.change(origin) { }
    }

    @Test fun blockedCookieFlushNeverBlocksCurrentTokenOrAcceptanceMonitor() {
        val guard = AccountSessionGuard()
        val request = guard.requestCookies(origin) { "cookies" }.first
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit {
                guard.commitResponse(request) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val accepted = pool.submit<AccountSessionGuard.Permit> { guard.register(guard.current(origin)) }
            val permit = accepted.get(1, TimeUnit.SECONDS)
            assertTrue(permit.accepted)
            guard.finish(permit)
            release.countDown()
            writer.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun cancellationBeforeWebsiteLeaseReturnsStillReleasesCookieOwnership() {
        val caller = QueueDispatcher()
        val io = QueueDispatcher()
        val guard = AccountSessionGuard(io)
        val owner = CoroutineScope(SupervisorJob() + caller)
        val opening = owner.launch {
            guard.openWebsite(origin) { listOf("account-a", "credential-a") }
            fail("Cancelled caller must not adopt the lease")
        }
        try {
            assertTrue(caller.runNext())
            assertTrue(io.runNext()) // Lease created, return continuation queued on caller.
            opening.cancel()
            repeat(8) { caller.runNext(); io.runNext() }
            assertTrue(opening.isCompleted)
            assertTrue(opening.isCancelled)
            val permit = guard.register(guard.current(origin))
            assertTrue(permit.accepted)
            guard.finish(permit)
        } finally { owner.cancel() }
    }

    @Test fun restoreReadsSavedSnapshotOnlyAfterEarlierLogoutDeletesIt() = runTest {
        val guard = AccountSessionGuard(StandardTestDispatcher(testScheduler))
        val accepted = guard.register(guard.current(origin))
        var saved: String? = "account-a"
        var cookieAccount: String? = "account-a"
        var preparations = 0
        val logout = async { guard.change(origin) { saved = null; cookieAccount = null } }
        runCurrent() // Logout owns the transition gate while waiting for the accepted prefix.
        val restore = async {
            guard.restore(origin, prepare = { preparations++; saved },
                unchanged = { it == null || it == cookieAccount },
                commit = { snapshot -> snapshot?.also { cookieAccount = it } })
        }
        runCurrent()
        assertEquals("account-a", saved)
        assertEquals(0, preparations)
        assertFalse(restore.isCompleted)
        guard.finish(accepted)
        runCurrent()
        logout.await()
        assertNull(restore.await())
        assertNull(cookieAccount)
        assertEquals(1, preparations)
    }

    @Test fun restoreThatStartedFirstCommitsBeforeFollowingAccountChange() = runTest {
        val guard = AccountSessionGuard(StandardTestDispatcher(testScheduler))
        var saved = "account-a"
        var cookieAccount = ""
        val prepared = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restore = async {
            guard.restore(origin, prepare = {
                val snapshot = saved
                prepared.complete(Unit)
                release.await()
                snapshot
            }, unchanged = { it == cookieAccount }, commit = { cookieAccount = it; it })
        }
        runCurrent()
        assertTrue(prepared.isCompleted)
        val change = async { guard.change(origin) { saved = "account-b"; cookieAccount = "account-b" } }
        runCurrent()
        assertFalse(change.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertEquals("account-a", restore.await())
        change.await()
        assertEquals("account-b", cookieAccount)
        assertEquals("account-b", saved)
    }

    @Test fun cancellationAfterRestorePreparationNeverCommitsSnapshotOrBlocksFollowingChange() = runTest {
        val guard = AccountSessionGuard(StandardTestDispatcher(testScheduler))
        var commits = 0
        val restore = async {
            guard.restore(origin, prepare = {
                currentCoroutineContext().cancel()
                "account-a"
            }, unchanged = { false }, commit = { commits++; it })
        }
        runCurrent()
        assertTrue(restore.isCancelled)
        assertEquals(0, commits)
        val change = async { guard.change(origin) { "account-b" } }
        runCurrent()
        assertEquals("account-b", change.await())
    }

    @Test fun cancellationWhileWaitingForSecondCookieMonitorNeverCommitsRestore() = runBlocking {
        val workerThread = AtomicReference<Thread>()
        val writerThread = AtomicReference<Thread>()
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "restore-commit-test").apply { isDaemon = true; workerThread.set(this) }
        }.asCoroutineDispatcher()
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val writerPool = Executors.newSingleThreadExecutor()
        val guard = AccountSessionGuard(dispatcher)
        val original = guard.current(origin)
        val request = guard.requestCookies(origin) { "cookies-a" }.first
        val permit = guard.register(original)
        val probeCompleted = CountDownLatch(1)
        val cookieMonitorHeld = CountDownLatch(1)
        val releaseCookieMonitor = CountDownLatch(1)
        val commits = AtomicInteger()
        try {
            val restore = owner.async {
                guard.restore(origin, prepare = { "account-a" }, unchanged = {
                    probeCompleted.countDown()
                    false
                }, commit = { commits.incrementAndGet(); it })
            }
            assertTrue(probeCompleted.await(5, TimeUnit.SECONDS))
            val writer = writerPool.submit {
                writerThread.set(Thread.currentThread())
                guard.commitResponse(request) {
                    cookieMonitorHeld.countDown()
                    check(releaseCookieMonitor.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(cookieMonitorHeld.await(5, TimeUnit.SECONDS))
            // Restore has already completed its first probe and is waiting for this accepted prefix.
            guard.finish(permit)
            val worker = checkNotNull(workerThread.get())
            val lockOwner = checkNotNull(writerThread.get()).id
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var blockedAtCommit = false
            while (System.nanoTime() < deadline) {
                val info = threads.getThreadInfo(worker.id)
                if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == lockOwner) {
                    blockedAtCommit = true
                    break
                }
                Thread.yield()
            }
            assertTrue("Restore must be blocked at its second cookie monitor, after the prefix", blockedAtCommit)
            restore.cancel()
            releaseCookieMonitor.countDown()
            writer.get(5, TimeUnit.SECONDS)
            withTimeout(5_000) { restore.join() }
            assertTrue(restore.isCancelled)
            assertEquals(0, commits.get())
            assertEquals(original, guard.current(origin))
            assertTrue("Cancelled restore must not advance the cookie epoch", guard.commitResponse(request) { })
            assertEquals("ready", withTimeout(5_000) { owner.async { guard.change(origin) { "ready" } }.await() })
        } finally {
            releaseCookieMonitor.countDown()
            guard.finish(permit)
            owner.cancel()
            writerPool.shutdownNow()
            dispatcher.close()
        }
    }

    @Test fun cancelledAccountReadDoesNotReadAnotherSessionOrRetainAnAcceptedPermit() = runTest {
        val guard = AccountSessionGuard(StandardTestDispatcher(testScheduler))
        val session = guard.current(origin)
        val permit = guard.register(session)
        val release = CompletableDeferred<Unit>()
        val writing = async { guard.server(permit) { release.await() } }
        runCurrent()
        var reads = 0
        val reading = async { guard.read(session) { reads++; "rating" } }
        runCurrent()
        assertFalse(reading.isCompleted)
        reading.cancel()
        release.complete(Unit)
        runCurrent()
        writing.await()
        guard.finish(permit)
        assertTrue(reading.isCancelled)
        assertEquals(0, reads)
        guard.change(origin) { }
        try { guard.read(session) { reads++ }; fail("Old card session must not read the new account") }
        catch (_: AccountSessionUnavailable) { }
        assertEquals(0, reads)
    }

    @Test fun restoredAccountCookiesSelectStableLocalOwnerAndLogoutSelectsGuestOnly() = runTest {
        val guard = AccountSessionGuard(StandardTestDispatcher(testScheduler))
        var header = ""
        guard.restore(origin, unchanged = { false }, commit = { header = "dle_user_id=42; dle_password=restored" })
        val session = guard.current(origin)
        val restored = guard.read(session) { PersonalRatingOwner.fromCookies(origin, header) }
        assertEquals(PersonalRatingOwner.fromCookies(origin, "dle_user_id=42; dle_password=login"), restored)
        guard.restore(origin, unchanged = { true }, commit = { header = "dle_user_id=42; dle_password=refreshed" })
        assertEquals(session, guard.current(origin))
        assertEquals(restored, guard.read(session) { PersonalRatingOwner.fromCookies(origin, header) })
        guard.change(origin) { header = "" }
        assertEquals(PersonalRatingOwner.fromCookies(origin, ""), guard.read(guard.current(origin)) {
            PersonalRatingOwner.fromCookies(origin, header)
        })
    }
}
