package com.bobyl.tv

import com.bobyl.tv.data.*
import org.junit.Assert.*
import org.junit.Test

class HistoryEntriesTest {
    private val origin = "https://history.example/"
    private val session = AccountSession(origin, "owner")
    private fun film(id: String) = CatalogFilm(id, "${origin}films/$id-title.html", id, "poster", "", "", "", "")
    private fun record(id: String, updated: Long, watched: Boolean = false) = WatchRecord(id, film(id).url, id,
        "poster", "1", "1", "2", 500L, updated = updated, watched = watched)
    private fun server(id: String, updated: Long, saveId: String = id, watched: Boolean = false) =
        ContinueItem(saveId, film(id), "account", watched = watched, viewedTimestamp = updated)

    @Test fun groupsMirrorUrlsAndVoicesIntoOneFilmWithoutLosingSources() {
        val rows = historyEntries(listOf(record("1", 100)), listOf(server("1", 200),
            server("1", 300, "other-voice").copy(film = film("1").copy(url = "https://mirror.example/films/1-title.html"))))
        assertEquals(1, rows.size)
        assertEquals(1, rows.single().records.size)
        assertEquals(2, rows.single().server.size)
        assertEquals("other-voice", rows.single().server.first().id)
    }

    @Test fun newestDateAcrossBothSourcesWinsOverInsertionAndAccountFirstOrder() {
        val rows = historyEntries(listOf(record("1", 300), record("2", 200), record("3", 100)),
            listOf(server("3", 400), server("1", 50)))
        assertEquals(listOf("3", "1", "2"), rows.map { it.key })
    }

    @Test fun unknownManualWatchedDateDoesNotPretendToBeRecentlyViewed() {
        val rows = historyEntries(listOf(record("900", 0, true), record("1", 100)),
            listOf(server("2", 200, watched = true)))
        assertEquals(listOf("2", "1", "900"), rows.map { it.key })
        assertEquals(listOf("2", "900"), rows.filter { it.watched }.map { it.key })
    }

    @Test fun targetedWatchedPatchMovesOnlyThatFilmAndPreservesOtherMetadata() {
        val before = HistoryState(records = listOf(record("1", 100), record("2", 200)),
            server = listOf(server("1", 150), server("2", 220)), busy = false)
        val after = before.applyFilmChange(HistoryFilmChange(session, "1", watched = true))
        assertTrue(after.records.first { it.filmId == "1" }.watched)
        assertTrue(after.server.first { it.id == "1" }.watched)
        assertEquals(before.records.last(), after.records.last())
        assertEquals(before.server.last(), after.server.last())
        assertEquals(1, historyEntries(after.records, after.server).count { it.watched })
    }

    @Test fun emptySourceSnapshotDeletesFilmFromOnlyThatSource() {
        val before = HistoryState(records = listOf(record("1", 100), record("2", 200)),
            server = listOf(server("1", 150), server("2", 220)), busy = false)
        val after = before.applyFilmChange(HistoryFilmChange(session, "1", server = emptyList()))
        assertEquals(before.records, after.records)
        assertEquals(listOf("2"), after.server.map { it.id })
        val removed = after.applyFilmChange(HistoryFilmChange(session, "1", records = emptyList()))
        assertEquals(listOf("2"), historyEntries(removed.records, removed.server).map { it.key })
    }

    @Test fun mixedWatchedSourcesRemainGroupedAndOlderLocalPositionDoesNotReplaceNewAccountProgress() {
        val rows = historyEntries(listOf(record("1", 100), record("2", 400, true)),
            listOf(server("1", 300, watched = true), server("2", 200)))
        assertEquals(2, rows.size)
        val first = rows.first { it.key == "1" }
        assertTrue(first.watched)
        assertEquals("account", first.info)
        assertNull(first.position)
        val second = rows.first { it.key == "2" }
        assertFalse(second.watched) // Account status retains the existing precedence.
        assertNotNull(second.position)
        assertTrue(rows.all { it.records.size == 1 && it.server.size == 1 })
    }
}
