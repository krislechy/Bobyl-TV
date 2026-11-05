package com.bobyl.tv.data.database

import com.bobyl.tv.data.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UserCacheClearTest : RoomTestBase() {
    private val provider = "https://user-clear-test.example/"
    private val url = "${provider}films/42-fixture.html"
    private val film = CatalogFilm("42", url, "Fixture", "", "", "", "1", "8")

    @Before fun installCache() {
        PersistentCache.resetForTests()
        PageCache.configure(60)
        PageCache.clear()
        PersistentCache.install(RoomStorageStores(database))
    }

    @After fun releaseCache() { PersistentCache.resetForTests(); PageCache.clear() }

    private fun countCache(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM cache_entries").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    @Test fun explicitClearRemovesRealRoomAndRamCachesAcrossReopenWithoutDeletingUserData() = io {
        val dao = database.storage()
        dao.putMeta(StorageMeta("premium:fixture", "180|2026-08-25"))
        val record = WatchRecord("42", url, "History", "${provider}poster.jpg", "1", "1", "2", 1234, 100)
        WatchHistory.withDatabase(database, provider).put(record)
        dao.putRating(PersonalRatingRow(provider, "guest", "42", 8))
        dao.putSelection(PlaybackSelectionRow("42", "1", "1", "2", 100))
        dao.putFilms(listOf(FilmRow(provider, "42", "42", url, "Metadata", "", "", "", "1", "8", 100)))
        dao.putDetails(FilmDetailsRow(provider, "42", "fixture-details", 1, 100))
        val media = File(context.filesDir, "protected-media.fixture").apply { writeText("fixture") }
        val auth = File(context.noBackupFilesDir, "protected-auth.fixture").apply { writeText("auth-fixture") }
        val prefs = context.getSharedPreferences("clear-fixture", 0)
        prefs.edit().putString("provider", provider).putInt("columns", 6).commit()
        dao.putFile(LocalFileRow("fixture", "download", "fixture", "42", provider, "guest", media.absolutePath,
            media.toURI().toString(), "fixture.mp4", "video/mp4", 7, 100, "complete", null, null, 100))

        var reads = 0
        suspend fun disk() = PersistentCache.load(url, "browse-films", 60_000, false) { reads++; listOf(film) }
        assertEquals(listOf(film), disk())
        assertEquals(listOf(film), disk())
        assertEquals(1, reads)
        assertEquals(1, countCache())
        var preparedReads = 0
        PreparedDataCache.load("explicit-fixture", 60_000, false) { preparedReads++; listOf(film) }
        PreparedDataCache.load("explicit-fixture", 60_000, false) { preparedReads++; listOf(film) }
        assertEquals(1, preparedReads)
        PageCache.put("html-fixture", Jsoup.parse("fixture", provider), PageCache.generation())
        assertNotNull(PageCache.get("html-fixture"))

        val revision = PersistentCache.userClearRevision.value
        PersistentCache.clearByUser()
        assertEquals(revision + 1, PersistentCache.userClearRevision.value)
        assertEquals(0, countCache())
        assertNull(PageCache.get("html-fixture"))
        PreparedDataCache.load("explicit-fixture", 60_000, false) { preparedReads++; listOf(film) }
        assertEquals(2, preparedReads)

        reopen()
        assertEquals(0, countCache())
        PersistentCache.install(RoomStorageStores(database))
        assertEquals(listOf(film), disk())
        assertEquals(2, reads)
        assertEquals(listOf(record), WatchHistory.withDatabase(database, provider).all())
        assertEquals(8, database.storage().rating(provider, "guest", "42"))
        assertEquals(PlaybackSelectionRow("42", "1", "1", "2", 100), database.storage().selection("42"))
        assertEquals("180|2026-08-25", database.storage().meta("premium:fixture"))
        assertEquals("Metadata", database.storage().film(provider, "42")?.title)
        assertEquals("fixture-details", database.storage().details(provider, "42")?.payload)
        assertEquals(1, database.storage().files("download").size)
        assertEquals("fixture", media.readText())
        assertEquals("auth-fixture", auth.readText())
        assertEquals(provider, prefs.getString("provider", ""))
        assertEquals(6, prefs.getInt("columns", 0))
    }

    @Test fun preClearInflightResponsesCannotReinsertIntoDiskPreparedOrHtmlCaches() = io {
        coroutineScope {
            val diskEntered = CompletableDeferred<Unit>()
            val ramEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val oldGeneration = PageCache.generation()
            val disk = async {
                PersistentCache.load(url, "browse-films", 60_000, false) {
                    diskEntered.complete(Unit); release.await(); listOf(film)
                }
            }
            val ram = async {
                PreparedDataCache.load("pending-fixture", 60_000, false) {
                    ramEntered.complete(Unit); release.await(); listOf(film)
                }
            }
            diskEntered.await(); ramEntered.await()
            PersistentCache.clearByUser()
            release.complete(Unit)
            assertEquals(listOf(film), disk.await())
            assertEquals(listOf(film), ram.await()) // Existing UI may retain its visible response.
            assertEquals(0, countCache())
            PageCache.put("late-html", Jsoup.parse("old", provider), oldGeneration)
            assertNull(PageCache.get("late-html"))
            var freshReads = 0
            val fresh = film.copy(title = "Fresh")
            assertEquals(listOf(fresh), PreparedDataCache.load("pending-fixture", 60_000, false) { freshReads++; listOf(fresh) })
            assertEquals(1, freshReads)
        }
    }

    @Test fun realSqlClearFailureDoesNotPublishSuccessAndRetryActuallyEmptiesCache() = io {
        PersistentCache.load(url, "browse-films", 60_000, false) { listOf(film) }
        assertEquals(1, countCache())
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_fixture_clear BEFORE DELETE ON cache_entries BEGIN SELECT RAISE(ABORT, 'fixture clear blocked'); END")
        val revision = PersistentCache.userClearRevision.value
        var failed = false
        try { PersistentCache.clearByUser() } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(revision, PersistentCache.userClearRevision.value)
        assertTrue(countCache() > 0)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_fixture_clear")
        PersistentCache.clearByUser()
        assertEquals(revision + 1, PersistentCache.userClearRevision.value)
        assertEquals(0, countCache())
    }

    @Test fun ordinaryMutationInvalidationDoesNotPublishManualClearRevision() = io {
        PersistentCache.load(url, "browse-films", 60_000, false) { listOf(film) }
        val revision = PersistentCache.userClearRevision.value
        PersistentCache.invalidate(strict = true)
        assertEquals(0, countCache())
        assertEquals(revision, PersistentCache.userClearRevision.value)
    }
}
