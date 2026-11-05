package com.bobyl.tv

import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.ContinueItem
import com.bobyl.tv.data.WatchRecord
import org.junit.Assert.*
import org.junit.Test

class ContinueHistoryRowTest {
    private val film = CatalogFilm("42", "https://history.example/films/42.html", "Title", "poster", "99% audience rating", "", "", "")
    private fun record(updated: Long = 200, position: Long = 125_000) =
        WatchRecord("42", film.url, film.title, film.poster, "17", "2", "3", position, updated, quality = "1080p")
    private fun server(info: String, updated: Long = 100, viewedAt: String = "") =
        ContinueItem("1", film, info, season = "2", episode = "3", viewedAt = viewedAt, viewedTimestamp = updated)
    private fun row(local: List<WatchRecord> = emptyList(), remote: List<ContinueItem> = emptyList()) =
        continueHistoryPresentation(HistoryEntry("42", film, local, remote))

    @Test fun localPositionHasClockAndMetadataWithoutInventingPercentage() {
        val result = row(local = listOf(record()))
        assertEquals("Сезон 2 · Серия 3 · 1080p", result.metadata)
        assertEquals("02:05", result.progressLabel)
        assertNull(result.fraction)
        assertTrue(result.showProgress)
        assertFalse(result.metadata.contains("17")) // Voice is an ID, not a display label.
    }

    @Test fun newerLocalPositionDoesNotBorrowAnOlderServerPercentageOrEpisode() {
        val result = row(listOf(record()), listOf(server("Сезон 1 · Серия 1 · 59%")))
        assertEquals("Сезон 2 · Серия 3 · 1080p", result.metadata)
        assertEquals("02:05", result.progressLabel)
        assertNull(result.fraction)
        assertTrue(result.showProgress)
    }

    @Test fun equalTimestampKeepsExistingLocalWinner() {
        val result = row(listOf(record(updated = 100)), listOf(server("59%")))
        assertEquals("02:05", result.progressLabel)
        assertNull(result.fraction)
    }

    @Test fun mergedNewerAccountDoesNotInventFractionFromPercentageText() {
        val result = row(listOf(record(updated = 50)), listOf(server("Сегодня в 14:15 • Сезон 2 · Серия 3 · 59%", viewedAt = "05.11.2026")))
        assertEquals("Сегодня в 14:15 · Сезон 2 · Серия 3 · 59%", result.metadata)
        assertEquals("", result.progressLabel)
        assertNull(result.fraction)
        assertTrue(result.showProgress)
    }

    @Test fun viewingDateClockDoesNotBecomePlaybackPosition() {
        val result = row(remote = listOf(server("Сегодня в 14:15 • Сезон 2 · Серия 3", viewedAt = "05.11.2026")))
        assertTrue(result.metadata.contains("14:15"))
        assertEquals("", result.progressLabel)
        assertNull(result.fraction)
        assertFalse(result.showProgress)
    }

    @Test fun progressClockIsReadOnlyAfterDatePrefix() {
        val result = row(remote = listOf(server("Сегодня в 14:15 • 03:45", viewedAt = "05.11.2026")))
        assertEquals("Сегодня в 14:15", result.metadata)
        assertEquals("03:45", result.progressLabel)
        assertNull(result.fraction)
        assertFalse(result.showProgress)
    }

    @Test fun dateOnlyClockIsNotParsedAsPlayback() {
        val result = row(remote = listOf(server("14:15", viewedAt = "05.11.2026")))
        assertEquals("14:15", result.metadata)
        assertEquals("", result.progressLabel)
    }

    @Test fun accountOnlyPercentageTextNeverCreatesProgress() {
        val result = row(remote = listOf(server("59% • Сезон 2")))
        assertEquals("59% · Сезон 2", result.metadata)
        assertEquals("", result.progressLabel)
        assertNull(result.fraction)
        assertFalse(result.showProgress)
    }

    @Test fun undatedPositionBeforeBulletIsNotTreatedAsDate() {
        val result = row(remote = listOf(server("03:45 • Сезон 2")))
        assertEquals("Сезон 2", result.metadata)
        assertEquals("03:45", result.progressLabel)
        assertNull(result.fraction)
        assertFalse(result.showProgress)
    }

    @Test fun accountPercentageMarkersDoNotCreateLocalProgress() {
        listOf("0%", "100%", "Прогресс 59%", "Просмотрено: 59%").forEach { info ->
            val result = row(remote = listOf(server(info)))
            assertEquals(info, result.metadata)
            assertEquals("", result.progressLabel)
            assertNull(result.fraction)
            assertFalse(result.showProgress)
        }
    }

    @Test fun mergedNewerAccountClockDoesNotBorrowOlderLocalPosition() {
        val result = row(listOf(record(updated = 50)), listOf(server("Сегодня в 14:15 • 03:45", viewedAt = "05.11.2026")))
        assertEquals("Сегодня в 14:15", result.metadata)
        assertEquals("03:45", result.progressLabel)
        assertNull(result.fraction)
        assertTrue(result.showProgress)
    }

    @Test fun absentHistoryHasNoProgressTrack() {
        val result = row()
        assertEquals("", result.progressLabel)
        assertNull(result.fraction)
        assertFalse(result.showProgress)
    }

    @Test fun invalidPercentagesAndRatingMetadataDoNotCreateProgress() {
        listOf("-1%", "101%", "1000%", "Рейтинг 99%", "05.11.2026").forEach {
            val result = row(remote = listOf(server(it)))
            assertNull(it, result.fraction)
            assertEquals(it, "", result.progressLabel)
            assertEquals(it, result.metadata)
            assertFalse(result.showProgress)
        }
    }

    @Test fun hoursSubtitleAndUnknownPositionRemainTruthful() {
        val long = row(local = listOf(record(position = 3_725_000).copy(subtitle = "Русские")))
        assertEquals("1:02:05", long.progressLabel)
        assertTrue(long.metadata.endsWith("Русские"))
        assertTrue(long.showProgress)
        val unknown = row(local = listOf(record(position = 0)))
        assertEquals("", unknown.progressLabel)
        assertNull(unknown.fraction)
        assertTrue(unknown.showProgress)
    }
}
