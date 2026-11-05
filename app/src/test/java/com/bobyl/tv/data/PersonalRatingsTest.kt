package com.bobyl.tv.data

import com.bobyl.tv.data.database.LegacyStorageImport
import com.bobyl.tv.data.database.RoomTestBase
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PersonalRatingsTest : RoomTestBase() {
    private val provider = "https://ratings-test.example/"
    private fun cookies(id: String, password: String = "credential-$id") = "dle_user_id=$id; dle_password=$password; PHPSESSID=temporary-session"
    private fun owner(id: String, origin: String = provider) = PersonalRatingOwner.fromCookies(origin, cookies(id))
    @Test fun accountIdRemainsStableAcrossCookieRefreshAndContainsNoCredentials() {
        val first = owner("42")
        assertEquals(first, PersonalRatingOwner.fromCookies(provider, cookies("42", "refreshed-secret")))
        assertEquals(first, PersonalRatingOwner.fromCookies("ratings-test.example", cookies("00042")))
        assertTrue(first.namespace.matches(Regex("user-[a-f0-9]{64}")))
        assertFalse(first.toString().contains("credential-42"))
        assertNotEquals(first, owner("43"))
    }
    @Test fun accountsGuestProvidersAndFilmsHaveIndependentValuesAfterRealReopen() = io {
        val ratings = PersonalRatings.withDatabase(database)
        val a = owner("42"); val b = owner("43")
        val guest = PersonalRatingOwner.fromCookies(provider, "")
        val other = owner("42", "https://another-provider.example/")
        for ((identity, score) in listOf(a to 7, b to 3, guest to 5, other to 9)) ratings.save(identity, "100", score, { identity }) { Unit }
        reopen()
        val restarted = PersonalRatings.withDatabase(database)
        assertEquals(7, restarted.read(PersonalRatingOwner.fromCookies(provider, cookies("42", "new-after-restore")), "100"))
        assertEquals(3, restarted.read(b, "100")); assertEquals(5, restarted.read(guest, "100"))
        assertEquals(9, restarted.read(other, "100")); assertEquals(0, restarted.read(a, "101"))
        assertEquals(7, restarted.read(a, "${provider}films/100-alias.html"))
    }
    @Test fun ownerlessLegacyValuesAreArchivedWithoutAccountOrGuestFallback() = io {
        val legacy = mapOf("${provider}|100" to 9)
        LegacyStorageImport(database).migrate(emptyMap<String, Any>(), legacy, emptyMap<String, Any>())
        val ratings = PersonalRatings.withDatabase(database)
        val a = owner("42"); val b = owner("43"); val guest = PersonalRatingOwner.fromCookies(provider, "")
        assertEquals(0, ratings.read(a, "100")); assertEquals(0, ratings.read(b, "100")); assertEquals(0, ratings.read(guest, "100"))
        ratings.save(a, "100", 4, { a }) { Unit }
        assertEquals(9, database.storage().archivedRatings().single().value)
        assertEquals(legacy.keys.single(), database.storage().archivedRatings().single().originalKey)
        assertEquals(4, ratings.read(a, "100")); assertEquals(0, ratings.read(b, "100"))
    }
    @Test fun incompleteOrInvalidCredentialsNeverFallBackToGuest() {
        for (header in listOf("dle_user_id=42", "dle_password=credential", "dle_user_id=bad; dle_password=credential",
            "dle_user_id=0; dle_password=credential", "dle_user_id=42; dle_password=deleted")) {
            try { PersonalRatingOwner.fromCookies(provider, header); fail("Incomplete identity must fail") }
            catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("учётную запись")) }
        }
    }
    @Test fun serverFailureOrCancellationNeverOverwriteLocalValue() = io {
        val ratings = PersonalRatings.withDatabase(database); val a = owner("42")
        ratings.save(a, "100", 7, { a }) { Unit }
        for (failure in listOf(IOException("offline"), IllegalStateException("rejected"), CancellationException("cancelled"))) {
            try { ratings.save(a, "100", 9, { a }) { throw failure }; fail("Submission must fail") }
            catch (actual: Exception) { assertSame(failure, actual) }
            assertEquals(7, ratings.read(a, "100"))
        }
    }
    @Test fun changedOwnerIsRejectedBeforeHttpAndLocalWrite() = io {
        var requests = 0
        try { PersonalRatings.withDatabase(database).save(owner("42"), "100", 7, { owner("43") }) { requests++ }; fail("Owner mismatch") }
        catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("Учётная запись изменилась")) }
        assertEquals(0, requests)
        assertEquals(0, PersonalRatings.withDatabase(database).read(owner("42"), "100"))
    }
    @Test fun ownerIsCapturedBeforeHttpAndAggregateNeverBecomesPersonalValue() = io {
        val ratings = PersonalRatings.withDatabase(database); val a = owner("42"); val b = owner("43")
        var current = a; var captures = 0
        val server = ServerRatingResult("8.25", "1000", null)
        assertSame(server, ratings.save(a, "100", 4, { captures++; current }) { current = b; server })
        assertEquals(1, captures); assertEquals(4, ratings.read(a, "100")); assertEquals(0, ratings.read(b, "100"))
    }
    @Test fun localSqlFailureAfterServerSuccessIsReportedAndPreviousCommittedValueSurvives() = io {
        val ratings = PersonalRatings.withDatabase(database); val a = owner("42")
        ratings.save(a, "100", 7, { a }) { Unit }
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER isolated_rating_failure BEFORE UPDATE ON personal_ratings WHEN NEW.value=9 BEGIN SELECT RAISE(ABORT,'isolated write failure'); END")
        var requests = 0
        try { ratings.save(a, "100", 9, { a }) { requests++ }; fail("Local SQL transaction must fail") }
        catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("Оценка отправлена")) }
        assertEquals(1, requests)
        reopen()
        assertEquals(7, PersonalRatings.withDatabase(database).read(a, "100"))
    }
    @Test fun outOfRangeValueCannotBeSubmittedOrWritten() = io {
        val ratings = PersonalRatings.withDatabase(database); val a = owner("42")
        for (score in listOf(0, 11)) {
            try { ratings.save(a, "100", score, { a }) { fail("Invalid value must not reach HTTP") }; fail("Invalid value") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, ratings.read(a, "100"))
    }
}
