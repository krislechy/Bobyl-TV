package com.bobyl.tv.data

import com.bobyl.tv.data.database.CacheRecord
import com.bobyl.tv.data.database.PersistentCacheStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PersistentCacheTest {
    private val film = CatalogFilm("12", "https://site.test/films/12-film.html", "Фильм", "", "", "", "1", "8")
    private class Store : PersistentCacheStore {
        val rows = linkedMapOf<String, CacheRecord>()
        var clearFailure = false
        var readFailure = false
        var markerFailure = false
        var clearGate: CompletableDeferred<Unit>? = null
        val clearEntered = CompletableDeferred<Unit>()
        var deletes = 0
        override suspend fun cacheGet(key: String): CacheRecord? {
            if (readFailure) throw IOException("read failure")
            return rows[key]
        }
        override suspend fun cachePut(record: CacheRecord, maxEntries: Int, maxBytes: Long) {
            if (markerFailure && record.key == PersistentCacheController.QuarantineKey) throw IOException("marker failure")
            rows[record.key] = record
        }
        override suspend fun cacheRemove(key: String) { deletes++; rows.remove(key) }
        override suspend fun cacheClear() {
            clearEntered.complete(Unit)
            clearGate?.await()
            if (clearFailure) throw IOException("clear failure")
            rows.clear()
        }
    }

    @Test fun survivesControllerRestartAndServesFreshDataOffline() = runTest {
        val store = Store()
        val first = PersistentCacheController(store) { 1_000L }
        val initial = first.load<List<CatalogFilm>>("provider", "user-a", "catalog:Latest:0", "url", 60_000, false, { true }) { listOf(film) }
        val restarted = PersistentCacheController(store) { 2_000L }
        val offline = restarted.load<List<CatalogFilm>>("provider", "user-a", "catalog:Latest:0", "url", 60_000, false, { true }) {
            throw IOException("offline")
        }
        assertEquals(initial, offline)
        assertTrue(store.rows.keys.none { it.contains("url") || it.contains("user-a") })
    }

    @Test fun expiryAndClockRollbackDoNotHideNetworkFailure() = runTest {
        val store = Store()
        var now = 1_000L
        val cache = PersistentCacheController(store, clock = { now })
        suspend fun get(): List<CatalogFilm> = cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(film) }
        get()
        now = 1_100
        try { cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { throw IOException("offline") }; fail() }
        catch (expected: IOException) { assertEquals("offline", expected.message) }
        assertTrue(store.rows.isEmpty())
        now = 2_000; get(); now = 1_999
        try { cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { throw IOException("clock") }; fail() }
        catch (expected: IOException) { assertEquals("clock", expected.message) }
        assertTrue(store.rows.isEmpty())
    }

    @Test fun accountsGuestsAndProvidersNeverShareRows() = runTest {
        val store = Store()
        val cache = PersistentCacheController(store) { 1_000L }
        for ((provider, owner) in listOf("p" to "user-a", "p" to "user-b", "p" to "guest", "other" to "user-a")) {
            val value = film.copy(title = "$provider/$owner")
            assertEquals(listOf(value), cache.load<List<CatalogFilm>>(provider, owner, "browse-films", "url", 100, false, { true }) { listOf(value) })
        }
        assertEquals(4, store.rows.size)
        assertEquals(listOf(film.copy(title = "p/user-a")), cache.load<List<CatalogFilm>>("p", "user-a", "browse-films", "url", 100, false, { true }) { error("cache miss") })
    }

    @Test fun forceRefreshAndUnknownCodecVersionFetchFreshData() = runTest {
        val store = Store()
        val cache = PersistentCacheController(store) { 1_000L }
        cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(film) }
        val refreshed = film.copy(title = "Новый")
        assertEquals(listOf(refreshed), cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, true, { true }) { listOf(refreshed) })
        val row = store.rows.values.single()
        store.rows[row.key] = row.copy(codecVersion = 999)
        assertEquals(listOf(film), cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(film) })
        assertEquals(1, store.deletes)
    }

    @Test fun clearFencesAResponseThatWasAlreadyLoading() = runTest {
        val store = Store()
        val cache = PersistentCacheController(store) { 1_000L }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loading = async {
            cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) {
                entered.complete(Unit); release.await(); listOf(film)
            }
        }
        entered.await()
        cache.invalidate()
        release.complete(Unit)
        assertEquals(listOf(film), loading.await())
        assertTrue(store.rows.isEmpty())
    }

    @Test fun changedSessionDoesNotReadOrPersistAnotherOwnersResult() = runTest {
        val store = Store()
        val cache = PersistentCacheController(store) { 1_000L }
        var valid = true
        cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { valid }) { valid = false; listOf(film) }
        assertTrue(store.rows.isEmpty())
    }

    @Test fun clearWhileWaitingForSessionValidationCannotReturnTheOldDiskValue() = runTest {
        val store = Store()
        val cache = PersistentCacheController(store) { 1_000L }
        cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(film) }
        var checks = 0
        val fresh = film.copy(title = "После изменения")
        val result = cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false,
            valid = { checks++; if (checks == 2) cache.invalidate(); true }) { listOf(fresh) }
        assertEquals(listOf(fresh), result)
        assertTrue(store.rows.isEmpty())
    }

    @Test fun oversizedPayloadRemainsOnlineOnly() = runTest {
        val store = Store()
        val cache = PersistentCacheController(store) { 1_000L }
        val large = film.copy(metadata = "x".repeat(4 * 1024 * 1024))
        assertEquals(listOf(large), cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(large) })
        assertTrue(store.rows.isEmpty())
    }

    @Test fun failedMutationInvalidationDisablesDiskUntilSuccessfulClearWithoutFailingAcceptedWork() = runTest {
        val store = Store()
        val failures = mutableListOf<Exception>()
        val cache = PersistentCacheController(store, reportFailure = { failures.add(it) }, clock = { 1_000L })
        cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(film) }
        store.clearFailure = true
        cache.invalidate(strict = false)
        val fresh = film.copy(title = "Сервер уже изменён")
        assertEquals(listOf(fresh), cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(fresh) })
        assertEquals(film.title, (CachePayloadCodec.decode("browse-films", store.rows.values.single { it.operation == "browse-films" }.payload) as List<*>).let { (it.single() as CatalogFilm).title })
        try { cache.invalidate(strict = true); fail() } catch (_: IOException) { }
        store.clearFailure = false
        cache.invalidate()
        cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(fresh) }
        assertEquals(listOf(fresh), cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { error("missing") })
        assertEquals(1, failures.size)
    }

    @Test fun failedClearQuarantinesFreshOldRowsAcrossRecreationAndSuccessfulClearRecovers() = runTest {
        val store = Store()
        val first = PersistentCacheController(store) { 1_000L }
        first.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 60_000, false, { true }) { listOf(film) }
        store.clearFailure = true
        first.invalidate(strict = false)
        assertTrue(store.rows.containsKey(PersistentCacheController.QuarantineKey))

        val restarted = PersistentCacheController(store) { 2_000L }
        try {
            restarted.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 60_000, false, { true }) { throw IOException("offline") }
            fail("Quarantined old data must not be returned")
        } catch (expected: IOException) { assertEquals("offline", expected.message) }
        val fresh = film.copy(title = "После серверного изменения")
        assertEquals(listOf(fresh), restarted.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 60_000, true, { true }) { listOf(fresh) })
        assertEquals(2, store.rows.size) // Old row and fence; normal writes stay disabled.
        assertEquals(film.title, (CachePayloadCodec.decode("browse-films", store.rows.values.single { it.operation == "browse-films" }.payload) as List<*>).let { (it.single() as CatalogFilm).title })

        store.clearFailure = false
        restarted.invalidate()
        assertTrue(store.rows.isEmpty())
        restarted.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 60_000, false, { true }) { listOf(fresh) }
        val secondRestart = PersistentCacheController(store) { 3_000L }
        assertEquals(listOf(fresh), secondRestart.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 60_000, false, { true }) { error("offline") })
    }

    @Test fun cancellationAfterDurableMarkerLeavesTheNextControllerQuarantined() = runTest {
        val store = Store()
        val first = PersistentCacheController(store) { 1_000L }
        first.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 60_000, false, { true }) { listOf(film) }
        store.clearGate = CompletableDeferred()
        val clearing = launch { first.invalidate(strict = false) }
        store.clearEntered.await()
        assertTrue(store.rows.containsKey(PersistentCacheController.QuarantineKey))
        clearing.cancelAndJoin()
        val restarted = PersistentCacheController(store) { 2_000L }
        try {
            restarted.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 60_000, false, { true }) { throw IOException("offline") }
            fail("Cancelled clear must not resurrect old data")
        } catch (expected: IOException) { assertEquals("offline", expected.message) }
        assertTrue(store.rows.containsKey(PersistentCacheController.QuarantineKey))
    }

    @Test fun failedFenceWriteIsSafeWhenTheActualClearSucceeds() = runTest {
        val store = Store()
        val cache = PersistentCacheController(store) { 1_000L }
        cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 60_000, false, { true }) { listOf(film) }
        store.markerFailure = true
        cache.invalidate(strict = true)
        assertTrue(store.rows.isEmpty())
    }

    @Test fun cacheReadFailureDoesNotReplaceFreshOnlineDataWithAnError() = runTest {
        val store = Store().apply { readFailure = true }
        val cache = PersistentCacheController(store) { 1_000L }
        assertEquals(listOf(film), cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(film) })
        assertTrue(store.rows.isEmpty())
    }

    @Test fun cancellationNeverPersistsOrPoisonsTheNextRequest() = runTest {
        val store = Store()
        val cache = PersistentCacheController(store) { 1_000L }
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) {
                entered.complete(Unit); CompletableDeferred<Unit>().await(); listOf(film)
            }
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(store.rows.isEmpty())
        assertEquals(listOf(film), cache.load<List<CatalogFilm>>("p", "a", "browse-films", "url", 100, false, { true }) { listOf(film) })
    }
}
