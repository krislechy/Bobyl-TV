package com.bobyl.tv.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class HistoryDatesTest {
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun relativeDatesUseMoscowCalendarAcrossYearBoundary() {
        val now = millis("2026-12-31T22:15:00Z") // 1 January in Moscow.
        assertEquals(millis("2026-12-31T21:00:00Z"), HistoryDates.timestamp("сегодня", now))
        assertEquals(millis("2026-12-30T21:00:00Z"), HistoryDates.timestamp("вчера", now))
        assertEquals(millis("2026-12-29T21:00:00Z"), HistoryDates.timestamp("позавчера", now))
        assertEquals(millis("2026-12-31T20:45:00Z"), HistoryDates.timestamp("вчера, 23:45", now))
    }

    @Test fun absoluteDatesKeepRealTimeAndRejectInvalidDates() {
        assertEquals(millis("2026-11-03T18:42:00Z"), HistoryDates.timestamp("03.11.2026 21:42"))
        assertEquals(millis("2026-11-02T21:00:00Z"), HistoryDates.timestamp("2026-11-03"))
        assertNull(HistoryDates.timestamp("31.02.2026"))
        assertNull(HistoryDates.timestamp("неизвестно"))
    }

    @Test fun relativeDateStoredInContinueItemDoesNotMoveWhenDayChanges() {
        val now = millis("2026-11-03T21:30:00Z")
        val film = CatalogFilm("1", "https://example.org/films/1-name.html", "Film", "", "", "", "", "")
        val item = ContinueItem("1", film, "", viewedAt = "сегодня",
            viewedTimestamp = HistoryDates.timestamp("сегодня", now))
        assertEquals(millis("2026-11-03T21:00:00Z"), item.copy(watched = true).viewedTimestamp)
    }

    @Test fun namedDateWithoutYearChoosesPastYearAtJanuaryBoundary() {
        assertEquals(millis("2026-12-30T21:00:00Z"),
            HistoryDates.timestamp("31 декабря", millis("2027-01-01T08:00:00Z")))
    }

    @Test fun nativeWatchedHyphenDatesKeepChronologyAndOptionalTime() {
        assertEquals("02.11.2026", HistoryDates.format("02-11-2026"))
        assertEquals("31.10.2026", HistoryDates.format("31-10-2026"))
        assertEquals(millis("2026-11-01T21:00:00Z"), HistoryDates.timestamp("02-11-2026"))
        assertEquals(millis("2026-10-30T21:00:00Z"), HistoryDates.timestamp("31-10-2026"))
        assertEquals(millis("2026-11-02T18:42:00Z"), HistoryDates.timestamp("02-11-2026 21:42"))
        assertTrue(HistoryDates.timestamp("02-11-2026")!! > HistoryDates.timestamp("31-10-2026")!!)
        assertNull(HistoryDates.timestamp("31-02-2026"))
    }
}
