package com.bobyl.tv.data

import com.bobyl.tv.data.database.RoomTestBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test

class WatchHistoryConcurrencyTest : RoomTestBase() {
    private val provider = "https://history-test.example/"
    private fun record(id: Int, updated: Long = id.toLong()) = WatchRecord(id.toString(),
        "${provider}films/$id-title.html", "Title $id", "poster-$id", "voice-$id", "2", "3", 12345L,
        updated, "1080p", "Русский", "test.player", 4, false)
    @Test fun concurrentInstancesPreserveAllDistinctPuts() = io {
        coroutineScope {
            (1..40).map { id -> async(Dispatchers.IO) { WatchHistory.withDatabase(database, provider).put(record(id)) } }.awaitAll()
        }
        assertEquals((1..40).map(Int::toString).toSet(), WatchHistory.withDatabase(database, provider).all().map { it.filmId }.toSet())
    }
    @Test fun setWatchedAndRemovePreserveOtherRecordsAndAllPlaybackFields() = io {
        val history = WatchHistory.withDatabase(database, provider)
        history.put(record(1)); history.put(record(2)); history.put(record(3))
        val film = CatalogFilm("1", record(1).url, "Title 1", "poster-1", "", "", "", "")
        history.setWatched(film, true)
        history.remove("2")
        assertEquals(listOf(record(3), record(1).copy(watched = true)), history.all())
    }
    @Test fun providerClearCannotDeleteAnotherProviderAndValuesSurviveReopen() = io {
        val other = "https://other-history.example/"
        WatchHistory.withDatabase(database, provider).put(record(1))
        WatchHistory.withDatabase(database, other).put(record(2))
        WatchHistory.withDatabase(database, provider).clear()
        reopen()
        assertTrue(WatchHistory.withDatabase(database, provider).all().isEmpty())
        assertEquals(record(2), WatchHistory.withDatabase(database, other).all().single())
    }
    @Test fun manualStatusKeepsUnknownDateAndNewestPutIsRetainedAtCap() = io {
        val history = WatchHistory.withDatabase(database, provider)
        for (id in 1..501) history.put(record(id))
        assertEquals(500, history.all().size)
        assertNull(history.find("1"))
        val film = CatalogFilm("900", "${provider}films/900-manual.html", "Manual", "poster", "", "", "", "")
        history.setWatched(film, true)
        val manual = history.find("900")!!
        assertEquals(0L, manual.updated)
        assertEquals(0L, manual.positionMs)
        assertTrue(manual.watched)
        assertEquals(500, history.all().size)
        assertEquals("900", history.all().last().filmId)
    }
    @Test fun equalTimestampsKeepPrependOrderAndFilmAliasStatusTouchesAllMatchingRows() = io {
        val history = WatchHistory.withDatabase(database, provider)
        history.put(record(1, 7)); history.put(record(2, 7))
        history.put(record(1, 7).copy(filmId = "episode:1:3"))
        val film = CatalogFilm("1", record(1).url, "One", "", "", "", "", "")
        history.setWatched(film, true)
        assertEquals(listOf("episode:1:3", "2", "1"), history.all().map { it.filmId })
        assertTrue(history.all().filter { historyFilmKey(it.url) == "1" }.all { it.watched })
        assertFalse(history.find("2")!!.watched)
    }

    @Test fun targetedReadsAndWatchedMutationKeepEpisodeOrderAndProviderIsolation() = io {
        val history = WatchHistory.withDatabase(database, provider)
        val other = WatchHistory.withDatabase(database, "https://other-history.example/")
        history.put(record(1, 7)); history.put(record(2, 8))
        history.put(record(1, 9).copy(filmId = "episode:1:3"))
        other.put(record(1, 100))
        assertEquals(record(1, 7), history.find("1"))
        assertEquals(listOf("episode:1:3", "1"), history.forFilm(record(1).url).map { it.filmId })
        assertTrue(history.forFilm("999").isEmpty())
        val film = CatalogFilm("1", record(1).url, "One", "", "", "", "", "")
        val changed = history.setFilmWatched(film, true)
        assertEquals(listOf("episode:1:3", "1"), changed.map { it.filmId })
        assertTrue(changed.all { it.watched })
        assertFalse(history.find("2")!!.watched)
        assertFalse(other.find("1")!!.watched)
        assertEquals(3, history.clearCount())
        assertEquals(0, history.clearCount())
        assertEquals(record(1, 100), other.find("1"))
    }
}
