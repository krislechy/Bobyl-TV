package com.bobyl.tv.data

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRestoreTest {
    private class Fake : PageTransport {
        override suspend fun get(url: String, details: Boolean): Document = Jsoup.parse(
            """<script>sof.tv.initCDNSeriesEvents(42, 1, 3, 4);</script>""", url)
        override suspend fun post(url: String, form: Map<String, String>): Document =
            error("Preparing a restore must not change server history")
    }

    private val entry = ContinueItem("10",
        CatalogFilm("42", "https://hdrzk.org/series/42-example.html", "Example", "", "", "", "", ""),
        "", season = "3", episode = "4")
    private val local = WatchRecord("42", entry.film.url, "Example", "", "2", "3", "4", 125_000L)

    private suspend fun snapshot(record: WatchRecord?) =
        checkNotNull(ExtrasRepository(Fake()).prepareHistoryRestore("hdrzk.org", entry, record).save)

    @Test fun matchingLocalRecordPreservesNonDefaultVoiceAndPosition() = runBlocking {
        assertEquals(HistorySaveSnapshot("42", "2", "3", "4", 125_000L), snapshot(local))
    }

    @Test fun anotherFilmIdCannotSupplyVoiceOrPosition() = runBlocking {
        assertEquals(HistorySaveSnapshot("42", "1", "3", "4", 0L), snapshot(local.copy(filmId = "99")))
    }

    @Test fun anotherFilmUrlCannotSupplyVoiceOrPosition() = runBlocking {
        assertEquals(HistorySaveSnapshot("42", "1", "3", "4", 0L),
            snapshot(local.copy(url = "https://hdrzk.org/series/99-other.html")))
    }

    @Test fun anotherEpisodeCannotSupplyVoiceOrPosition() = runBlocking {
        assertEquals(HistorySaveSnapshot("42", "1", "3", "4", 0L), snapshot(local.copy(episode = "5")))
    }

    @Test fun anotherSeasonCannotSupplyVoiceOrPosition() = runBlocking {
        assertEquals(HistorySaveSnapshot("42", "1", "3", "4", 0L), snapshot(local.copy(season = "2")))
    }

    @Test fun missingLocalRecordUsesPageDefaultAndZeroPosition() = runBlocking {
        assertEquals(HistorySaveSnapshot("42", "1", "3", "4", 0L), snapshot(null))
    }

    @Test fun blankLocalVoiceUsesDefaultWhileKeepingMatchingPosition() = runBlocking {
        assertEquals(HistorySaveSnapshot("42", "1", "3", "4", 125_000L), snapshot(local.copy(voice = " ")))
    }

    @Test fun mirrorUrlWithSameFilmKeyStillMatches() = runBlocking {
        assertEquals(HistorySaveSnapshot("42", "2", "3", "4", 125_000L),
            snapshot(local.copy(url = "https://mirror.example/series/42-example.html")))
    }
}
