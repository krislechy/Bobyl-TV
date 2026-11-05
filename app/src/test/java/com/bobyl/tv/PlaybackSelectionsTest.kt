package com.bobyl.tv

import com.bobyl.tv.data.PlaybackSelection
import com.bobyl.tv.data.PlaybackSelections
import com.bobyl.tv.data.database.RoomTestBase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionsTest : RoomTestBase() {
    @Test fun selectionRetainsFilmOnlyScopeAndSurvivesRealReopen() = io {
        val selections = PlaybackSelections.withDatabase(database)
        selections.save("42", PlaybackSelection("voice", "2", "3"))
        selections.save("43", PlaybackSelection("other", "1", "8"))
        reopen()
        assertEquals(PlaybackSelection("voice", "2", "3"), PlaybackSelections.withDatabase(database).read("42"))
        assertEquals(PlaybackSelection("other", "1", "8"), PlaybackSelections.withDatabase(database).read("43"))
        assertEquals(PlaybackSelection(), PlaybackSelections.withDatabase(database).read("44"))
    }
    @Test fun selectionReadWaitsForAcceptedOldSessionWriteButNotAnotherFilm() = io {
        val barrier = PlaybackSelectionBarrier()
        val completion = CompletableDeferred<Result<Unit>>()
        barrier.track("42", FilmActionReceipt("old-session-write", true, completion))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CompletableDeferred<Unit>()
        val read = scope.async {
            started.complete(Unit)
            barrier.await("42")
            PlaybackSelections.withDatabase(database).read("42")
        }
        started.await()
        barrier.await("43") // A different global primary key is not blocked.
        assertFalse(read.isCompleted)
        PlaybackSelections.withDatabase(database).save("42", PlaybackSelection("old-session", "2", "4"))
        completion.complete(Result.success(Unit))
        assertEquals(PlaybackSelection("old-session", "2", "4"), read.await())
        scope.cancel()
    }
    @Test fun rejectedReceiptNeverClaimsAQueuedSelectionAndFailedWriteUnblocksReaders() = io {
        val barrier = PlaybackSelectionBarrier()
        barrier.track("42", FilmActionReceipt("rejected", false, CompletableDeferred()))
        barrier.await("42")
        val failed = CompletableDeferred<Result<Unit>>()
        barrier.track("42", FilmActionReceipt("accepted", true, failed))
        failed.complete(Result.failure(IllegalStateException("disk failed")))
        barrier.await("42")
        assertEquals(PlaybackSelection(), PlaybackSelections.withDatabase(database).read("42"))
    }
}
