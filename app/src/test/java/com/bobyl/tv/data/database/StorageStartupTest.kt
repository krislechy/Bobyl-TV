package com.bobyl.tv.data.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.bobyl.tv.BobylApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = BobylApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StorageStartupTest {
    @Test fun actualApplicationEagerlyCreatesAllTablesBeforeAnyDaoConsumer() = runBlocking {
        val application = RuntimeEnvironment.getApplication() as BobylApplication
        try {
            withTimeout(20_000L) { application.storage.state.first { it != StorageState.Loading } }
            assertEquals(StorageState.Ready, application.storage.state.value)
            withContext(Dispatchers.IO) {
                // Independent read-only platform open cannot create a missing database or call a lazy DAO.
                val path = application.getDatabasePath(BobylDatabase.Name)
                assertTrue(path.isFile)
                SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                    val expected = setOf("storage_meta", "films", "film_details", "watch_history", "personal_ratings",
                        "legacy_ratings_archive", "playback_selections", "legacy_import_archive", "cache_entries", "local_files")
                    val names = sqlite.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    }
                    assertTrue(names.containsAll(expected))
                    sqlite.rawQuery("SELECT value FROM storage_meta WHERE key=?", arrayOf(LegacyStorageImport.Marker)).use {
                        assertTrue(it.moveToFirst()); assertEquals("complete", it.getString(0))
                    }
                }
            }
        } finally { application.storage.closeForTests() }
    }

    @Test @Config(application = Application::class)
    fun readinessWaitsForInitializationFailureRetriesOnceAndSuccessCannotBeRestarted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        var attempts = 0
        val startup = StorageStartup(scope) {
            attempts++
            if (attempts == 1) { firstStarted.complete(Unit); finishFirst.await(); error("isolated failure") }
        }
        try {
            startup.start(); firstStarted.await(); startup.start(); startup.start()
            assertEquals(StorageState.Loading, startup.state.value); assertEquals(1, attempts)
            finishFirst.complete(Unit)
            withTimeout(5_000) { startup.state.first { it == StorageState.Failed } }
            startup.start()
            withTimeout(5_000) { startup.state.first { it == StorageState.Ready } }
            startup.start()
            assertEquals(2, attempts)
        } finally { scope.cancel() }
    }

    @Test @Config(application = Application::class)
    fun cancellingAnUnfinishedInitializerNeverPublishesReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val startup = StorageStartup(scope) { started.complete(Unit); finish.await() }
        startup.start(); started.await()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        finish.complete(Unit)
        assertEquals(StorageState.Loading, startup.state.value)
    }
}
