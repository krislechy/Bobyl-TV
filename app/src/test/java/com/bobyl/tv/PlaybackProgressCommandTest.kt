package com.bobyl.tv

import com.bobyl.tv.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackProgressCommandTest {
    private val origin = "https://progress-test.example/"
    private fun snapshot(session: AccountSession, complete: Boolean = false, movie: Boolean = false) = PlaybackProgressSnapshot(
        "launch-1", FilmActionKey.of(session, "42"),
        WatchRecord("42", "${origin}series/42-example.html", "Example", "poster", "voice-2", "3", "4",
            if (complete && movie) 0L else 123_000L, updated = 1_000L, quality = "1080p", subtitle = "ru",
            playerPackage = "player", decoder = 4, watched = complete && movie),
        complete, movie, 123_000L,
    )

    @Test fun cancelledRemoteAttemptStillInvalidatesUsingTheCapturedSession() = runTest {
        val captured = AccountSession(origin, "captured-owner")
        val progress = snapshot(captured)
        val gate = CompletableDeferred<Unit>()
        val before = ServerHistoryChanges.revision.value
        var local = false
        val save = launch { persistPlaybackProgress(progress, local = { _, _ -> local = true }, server = { gate.await() }) }
        runCurrent()
        assertTrue(local)
        assertEquals(before + 1, ServerHistoryChanges.revision.value)
        save.cancel(); runCurrent(); save.join()
        assertEquals(before + 2, ServerHistoryChanges.revision.value)
        assertEquals(captured, ServerHistoryChanges.films.value.films.last().session)
    }

    @Test fun acceptedSnapshotIsSavedOnceLocallyBeforeServerDespiteDuplicateCallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val guard = AccountSessionGuard(dispatcher)
        val queue = FilmActionQueue(owner, guard, dispatcher)
        val snapshot = snapshot(guard.current(origin))
        val calls = mutableListOf<String>()
        var saved: WatchRecord? = null
        try {
            val first = queue.submit(snapshot.key, snapshot.id, localFirst = true, deduplicate = true) {
                persistPlaybackProgress(snapshot, local = { record, remove ->
                    assertFalse(remove); calls += "local"; saved = record
                }, server = { calls += "server" })
            }
            val duplicate = queue.submit(snapshot.key, snapshot.id, localFirst = true, deduplicate = true) {
                error("Duplicate callback must not run its new payload")
            }
            assertSame(first, duplicate)
            runCurrent()
            assertEquals(snapshot.record, saved)
            assertEquals(listOf("local", "server"), calls)
            assertEquals("", first.await().message)
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun staleSessionStillSavesConfirmedLocalProgressWithoutSendingToNewAccount() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val guard = AccountSessionGuard(dispatcher)
        val queue = FilmActionQueue(owner, guard, dispatcher)
        val snapshot = snapshot(guard.current(origin))
        guard.change(origin) { }
        var local = 0
        var server = 0
        try {
            val receipt = queue.submit(snapshot.key, snapshot.id, localFirst = true) { permit ->
                persistPlaybackProgress(snapshot, local = { _, _ -> local++ },
                    server = { guard.server(permit) { server++ } })
            }
            assertTrue(receipt.accepted)
            runCurrent()
            assertEquals(1, local)
            assertEquals(0, server)
            assertTrue(receipt.await().message.contains("Не удалось обновить историю аккаунта"))
            assertTrue(queue.latestError(snapshot.key).isNotBlank())
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun completionKeepsMovieWatchedRecordButRemovesCompletedSeriesAndReportsPartialFailure() = runTest {
        val session = AccountSession(origin, "session")
        val movie = snapshot(session, complete = true, movie = true)
        val series = snapshot(session, complete = true)
        var movieRemoved = true
        val result = persistPlaybackProgress(movie, local = { record, remove ->
            movieRemoved = remove
            assertTrue(record.watched)
            assertEquals(0L, record.positionMs)
            assertEquals(1_000L, record.updated)
        }, server = { throw IOException("offline") })
        assertFalse(movieRemoved)
        assertEquals("Не удалось обновить историю аккаунта: offline", result.message)
        var seriesRemoved = false
        persistPlaybackProgress(series, local = { _, remove -> seriesRemoved = remove }, server = { })
        assertTrue(seriesRemoved)
    }

    @Test fun relaunchHistoryReadWaitsSameFilmSaveButNotUnrelatedFilm() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val guard = AccountSessionGuard(dispatcher)
        val queue = FilmActionQueue(owner, guard, dispatcher)
        val snapshot = snapshot(guard.current(origin))
        val release = CompletableDeferred<Unit>()
        var localPosition = 10L
        try {
            queue.submit(snapshot.key, localFirst = true) {
                release.await()
                persistPlaybackProgress(snapshot, local = { record, _ -> localPosition = record.positionMs }, server = { })
            }
            val relaunch = async {
                queue.awaitSettled(FilmActionKey.of(snapshot.key.session, snapshot.record.url))
                localPosition
            }
            val otherFilm = async { queue.awaitSettled(FilmActionKey.of(snapshot.key.session, "99")); "ready" }
            runCurrent()
            assertFalse(relaunch.isCompleted)
            assertEquals("ready", otherFilm.await())
            release.complete(Unit)
            runCurrent()
            assertEquals(123_000L, relaunch.await())
        } finally { owner.cancel(); runCurrent() }
    }
}
