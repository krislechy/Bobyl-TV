package com.bobyl.tv.data.database

import android.database.sqlite.SQLiteConstraintException
import org.junit.Assert.*
import org.junit.Test

class BobylDatabaseTest : RoomTestBase() {
    @Test fun firstOpenCreatesAllTenTablesAndCompositeKeysAreEnforced() = io {
        val expected = setOf("storage_meta", "films", "film_details", "watch_history", "personal_ratings",
            "legacy_ratings_archive", "playback_selections", "legacy_import_archive", "cache_entries", "local_files")
        val names = database.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertTrue(names.containsAll(expected))
        database.openHelper.readableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        val dao = database.storage()
        dao.putRating(PersonalRatingRow("https://one.example/", "guest", "42", 5))
        dao.putRating(PersonalRatingRow("https://two.example/", "guest", "42", 7))
        assertEquals(5, dao.rating("https://one.example/", "guest", "42"))
        try {
            database.openHelper.writableDatabase.execSQL("INSERT INTO personal_ratings(provider,owner,filmId,value) VALUES ('https://one.example/','guest','42',9)")
            fail("Composite primary key must reject a duplicate")
        } catch (_: SQLiteConstraintException) { }
        try {
            database.openHelper.writableDatabase.execSQL("INSERT INTO personal_ratings(provider,owner,filmId,value) VALUES (NULL,'guest','43',9)")
            fail("Owner/provider fields cannot be null")
        } catch (_: SQLiteConstraintException) { }
    }
    @Test fun unsupportedDatabaseVersionFailsWithoutDestroyingExistingRows() = io {
        database.storage().putRating(PersonalRatingRow("provider", "guest", "42", 7))
        val path = context.getDatabasePath(requireNotNull(database.openHelper.databaseName))
        database.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(path.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("PRAGMA user_version=99")
        }
        try { reopen(); fail("Unsupported schema must not be reset") }
        catch (_: IllegalStateException) { }
        android.database.sqlite.SQLiteDatabase.openDatabase(path.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT value FROM personal_ratings WHERE filmId='42'", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(7, cursor.getInt(0))
            }
        }
    }
    @Test fun cachePersistsAcrossReopenRecalculatesUtf8WeightAndEvictsLeastRecentlyRead() = io {
        var now = 100L
        var store = RoomStorageStores(database) { now++ }
        fun entry(key: String, payload: String) = CacheRecord(key, "provider", "guest", "catalog", payload, 1, 10, 10000, 0)
        store.cachePut(entry("a", "яя"), 2, 100)
        store.cachePut(entry("b", "b"), 2, 100)
        assertEquals(4L, store.cacheGet("a")!!.sizeBytes)
        store.cachePut(entry("c", "c"), 2, 100)
        assertNull(store.cacheGet("b"))
        reopen(); store = RoomStorageStores(database) { now++ }
        assertEquals("яя", store.cacheGet("a")!!.payload)
        assertNotNull(store.cacheGet("c"))
        store.cachePut(entry("large", "123456"), 2, 5)
        assertNull(store.cacheGet("large"))
        store.cacheClear()
        assertNull(store.cacheGet("a"))
    }
    @Test fun fileReconciliationReplacesOnlyVerifiedScopeAndNeverDeletesTheFile(): Unit = io {
        val store = RoomStorageStores(database)
        val physical = java.io.File(context.cacheDir, "isolated-subtitle.srt").apply { writeText("test") }
        fun row(id: String, scope: String) = LocalFileRecord(id, "subtitle", scope, "42", null, null,
            physical.path, "", "Subtitle", "text/plain", 4, 0, "ready", null, null, 1)
        store.upsertFile(row("a", "one")); store.upsertFile(row("b", "two"))
        store.reconcileFileScope("subtitle", "one", listOf(row("c", "one")))
        assertEquals(setOf("b", "c"), store.listFiles("subtitle").map { it.id }.toSet())
        try { store.reconcileFileScope("subtitle", "one", listOf(row("bad", "another"))); fail("Wrong scan scope") }
        catch (_: IllegalArgumentException) { }
        assertEquals(setOf("b", "c"), store.listFiles("subtitle").map { it.id }.toSet())
        store.removeFile("c")
        assertTrue(physical.exists())
        physical.delete()
    }

    @Test fun quarantineMarkerSurvivesFullCacheAndClockTiesWhileCountingAgainstQuota() = io {
        val store = RoomStorageStores(database) { 0L }
        fun entry(key: String) = CacheRecord(key, "provider", "guest", "catalog", "x", 1, 0, Long.MAX_VALUE, 1)
        for (index in 0 until 128) store.cachePut(entry("normal:$index"), 128, 16L * 1024 * 1024)
        store.cachePut(entry(CacheQuarantineKey), 128, 16L * 1024 * 1024)
        assertNotNull(store.cacheGet(CacheQuarantineKey))
        val rows = database.storage().cachesOldestFirst()
        assertEquals(128, rows.size)
        assertEquals(127, rows.count { it.key != CacheQuarantineKey })
        reopen()
        val reopened = RoomStorageStores(database) { -1L }
        assertNotNull(reopened.cacheGet(CacheQuarantineKey))
        reopened.cachePut(entry("new-normal"), 1, 16L * 1024 * 1024)
        assertEquals(listOf(CacheQuarantineKey), database.storage().cachesOldestFirst().map { it.key })
        assertNull(reopened.cacheGet("new-normal"))
    }
}
