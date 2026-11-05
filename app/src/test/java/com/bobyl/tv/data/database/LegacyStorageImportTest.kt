package com.bobyl.tv.data.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import com.bobyl.tv.data.PersonalRatingOwner
import com.bobyl.tv.data.PersonalRatings
import com.bobyl.tv.data.PlaybackSelection
import com.bobyl.tv.data.PlaybackSelections
import com.bobyl.tv.data.WatchHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LegacyStorageImportTest : RoomTestBase() {
    private val provider = "https://legacy.example/"
    private val json = """[{"id":"42:2:3","url":"https://legacy.example/films/42-title.html","title":"Title","poster":"poster","voice":"7","season":"2","episode":"3","position":123456,"updated":456789,"quality":"1080p","subtitle":"RU","player":"test.player","decoder":4,"watched":true}]"""
    @Test fun realPreferencesImportAllFieldsAndKnownOwnersWithoutModifyingOriginalStores() = io {
        val owner = PersonalRatingOwner.fromCookies(provider, "dle_user_id=42; dle_password=credential")
        val historyPrefs = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
        val ratingPrefs = context.getSharedPreferences("personal_ratings", Context.MODE_PRIVATE)
        val globalPrefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        historyPrefs.edit().putString(provider, json).commit()
        ratingPrefs.edit().putInt("v2|$provider|${owner.namespace}|42", 8).putInt("$provider|43", 6).commit()
        globalPrefs.edit().putString("lastVoice:42", "7").putString("lastSeason:42", "2").putString("lastEpisode:42", "3").putInt("startupScreen", 4).commit()
        LegacyStorageImport(database).migrate(context)
        val record = WatchHistory.withDatabase(database, provider).all().single()
        assertEquals("42:2:3", record.filmId); assertEquals("2", record.season); assertEquals("3", record.episode)
        assertEquals(123456L, record.positionMs); assertEquals(456789L, record.updated)
        assertEquals("1080p", record.quality); assertEquals("RU", record.subtitle); assertEquals("test.player", record.playerPackage)
        assertEquals(4, record.decoder); assertTrue(record.watched)
        assertEquals(8, PersonalRatings.withDatabase(database).read(owner, "42"))
        assertEquals(PlaybackSelection("7", "2", "3"), PlaybackSelections.withDatabase(database).read("42"))
        assertEquals(json, historyPrefs.getString(provider, null))
        assertEquals(6, ratingPrefs.getInt("$provider|43", 0)); assertEquals(4, globalPrefs.getInt("startupScreen", 0))
        assertEquals("7", globalPrefs.getString("lastVoice:42", null))
    }
    @Test fun malformedSourcesAreArchivedInFullWhileValidRecordsStillImport() = io {
        val partiallyBad = json.dropLast(1) + ",{\"id\":\"broken\"}]"
        LegacyStorageImport(database).migrate(mapOf(provider to partiallyBad, "https://bad.example/" to "not-json"),
            mapOf("v2|$provider|guest|42" to 99, "$provider|99" to 4), mapOf("lastVoice:42" to 17))
        assertEquals(1, WatchHistory.withDatabase(database, provider).all().size)
        val archive = database.storage().archivedImports()
        assertTrue(archive.any { it.raw == partiallyBad && it.reason.isNotBlank() })
        assertTrue(archive.any { it.raw == "not-json" })
        assertEquals(4, database.storage().archivedRatings().single().value)
        assertNotNull(database.storage().meta(LegacyStorageImport.Marker))
    }
    @Test fun transactionFailureRollsBackImportedRowsAndMarkerThenRetrySucceeds() = io {
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER isolated_import_failure BEFORE INSERT ON storage_meta WHEN NEW.`key`='${LegacyStorageImport.Marker}' BEGIN SELECT RAISE(ABORT,'isolated importer failure'); END")
        try {
            LegacyStorageImport(database).migrate(mapOf(provider to json), emptyMap<String, Any>(), emptyMap<String, Any>())
            fail("Transaction must roll back")
        } catch (failure: SQLiteConstraintException) { assertTrue(failure.message.orEmpty().contains("isolated importer failure")) }
        assertNull(database.storage().meta(LegacyStorageImport.Marker))
        assertTrue(WatchHistory.withDatabase(database, provider).all().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER isolated_import_failure")
        LegacyStorageImport(database).migrate(mapOf(provider to json), emptyMap<String, Any>(), emptyMap<String, Any>())
        reopen()
        assertEquals(1, WatchHistory.withDatabase(database, provider).all().size)
        assertEquals("complete", database.storage().meta(LegacyStorageImport.Marker))
    }
    @Test fun cancellationAfterCommitAndRestartNeverReimportsDeletedHistory() = io {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val committed = CompletableDeferred<Unit>()
        val job = scope.launch {
            LegacyStorageImport(database).migrate(mapOf(provider to json), emptyMap<String, Any>(), emptyMap<String, Any>())
            committed.complete(Unit)
            awaitCancellation()
        }
        committed.await(); job.cancelAndJoin(); scope.cancel()
        WatchHistory.withDatabase(database, provider).clear()
        reopen()
        LegacyStorageImport(database).migrate(mapOf(provider to json), emptyMap<String, Any>(), emptyMap<String, Any>())
        assertTrue(WatchHistory.withDatabase(database, provider).all().isEmpty())
        assertNotNull(database.storage().meta(LegacyStorageImport.Marker))
    }

    @Test fun oversizedLegacyHistoryIsCappedImmediatelyAndArchivedWithoutOverwritingExistingRows() = io {
        val source = JSONArray().apply {
            for (id in 1..550) put(JSONObject().put("id", id.toString()).put("url", "${provider}films/$id-title.html")
                .put("title", "Legacy $id").put("poster", "poster").put("updated", id))
        }.toString()
        val prefs = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
        prefs.edit().putString(provider, source).commit()
        val history = WatchHistory.withDatabase(database, provider)
        history.put(com.bobyl.tv.data.WatchRecord("550", "${provider}films/550-title.html", "Already saved", "poster", "voice", "2", "3", 777, updated = 999999))
        LegacyStorageImport(database).migrate(context)
        assertEquals(500, history.all().size)
        assertNull(history.find("1"))
        assertEquals("Already saved", history.find("550")!!.title)
        assertEquals(777L, history.find("550")!!.positionMs)
        val archive = database.storage().archivedImports().single { it.source == "watch_history" && it.originalKey == provider }
        assertEquals(source, archive.raw)
        assertEquals(550, JSONArray(archive.raw).length())
        assertTrue(archive.reason.contains("500"))
        assertEquals(source, prefs.getString(provider, null))
        reopen()
        LegacyStorageImport(database).migrate(context)
        assertEquals(500, WatchHistory.withDatabase(database, provider).all().size)
        assertEquals("Already saved", WatchHistory.withDatabase(database, provider).find("550")!!.title)
        assertEquals("complete", database.storage().meta(LegacyStorageImport.Marker))
    }
}
