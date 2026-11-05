package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.BookmarkCategory
import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.AccountProfile
import com.bobyl.tv.data.FilmDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HomeDashboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun film(id: String) = CatalogFilm(id, "https://home.example/$id", id, "", "", "", "", "")
    private fun categories(count: Int) = listOf(BookmarkCategory("0", "Всё", count = count))

    @Test fun accountSummaryDisplaysTheServerNicknameRatherThanTheSavedLoginEmail() = runTest(dispatcher) {
        val savedLogin = "saved-login@example.invalid"
        var reads = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(),
            loadFilms = { _, _ -> emptyList() }, loadCategories = { _, _ -> emptyList() },
            readAccountProfile = {
                reads++
                if (savedLogin.isNotBlank()) AccountProfile(true, "avatar", false, "", nickname = "FixtureNickname") else null
            })
        try {
            model.loadAccount("home.example", 0)
            runCurrent()
            assertEquals("FixtureNickname", model.state.value.nickname)
            assertNotEquals(savedLogin, model.state.value.nickname)
            assertFalse(model.state.value.accountLoading)
            model.loadAccount("home.example", 0)
            runCurrent()
            assertEquals(1, reads)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun missingOrUnconfirmedProfileNicknameKeepsGreetingNeutral() = runTest(dispatcher) {
        var response: AccountProfile? = AccountProfile(true, "", false, "")
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(),
            loadFilms = { _, _ -> emptyList() }, loadCategories = { _, _ -> emptyList() }, readAccountProfile = { response })
        try {
            model.loadAccount("home.example", 0); runCurrent()
            assertEquals("", model.state.value.nickname)
            response = AccountProfile(false, "", false, "", nickname = "StaleNickname")
            model.loadAccount("home.example", 1); runCurrent()
            assertEquals("", model.state.value.nickname)
            response = null
            model.loadAccount("home.example", 2); runCurrent()
            assertEquals("", model.state.value.nickname)
            assertFalse(model.state.value.accountLoading)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun accountFailureAndUnconfirmedResponseAreNotPresentedAsGuest() = runTest(dispatcher) {
        var response = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(), readAccountProfile = {
            when (response) {
                0 -> error("Fixture network failure")
                1 -> null
                else -> AccountProfile(null, "", false, "", "UnconfirmedNickname")
            }
        })
        try {
            model.loadAccount("account-error.example", 0); runCurrent()
            assertNotNull(model.state.value.accountSession)
            assertTrue(model.state.value.accountError.isNotBlank())
            assertFalse(model.state.value.accountLoading)
            assertNull(model.state.value.profile)
            response = 1
            model.loadAccount("account-error.example", 1); runCurrent()
            assertEquals("", model.state.value.accountError)
            assertFalse(model.state.value.accountLoading)
            assertNull(model.state.value.profile)
            response = 2
            model.loadAccount("account-error.example", 2); runCurrent()
            assertTrue(model.state.value.accountError.isNotBlank())
            assertNull(model.state.value.profile)
            assertEquals("", model.state.value.nickname)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun foregroundRefreshAfterAccountFailureKeepsUnknownStateLoadingUntilTheResponse() = runTest(dispatcher) {
        val nextProfile = CompletableDeferred<AccountProfile?>()
        var reads = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(), readAccountProfile = {
            if (++reads == 1) error("Fixture initial failure") else nextProfile.await()
        })
        try {
            model.loadAccount("account-refresh-error.example", 0); runCurrent()
            assertTrue(model.state.value.accountError.isNotBlank())
            assertFalse(model.state.value.accountLoading)
            assertNull(model.state.value.profile)
            model.loadAccount("account-refresh-error.example", 0, refreshRevision = 1)
            assertTrue(model.state.value.accountLoading)
            assertEquals("", model.state.value.accountError)
            assertNull(model.state.value.profile)
            runCurrent()
            assertEquals(2, reads)
            assertTrue(model.state.value.accountLoading)
            assertNull(model.state.value.profile)
            nextProfile.complete(AccountProfile(true, "", false, "", "RefreshedNickname")); runCurrent()
            assertFalse(model.state.value.accountLoading)
            assertEquals("", model.state.value.accountError)
            assertEquals("RefreshedNickname", model.state.value.nickname)
        } finally { nextProfile.complete(null); model.viewModelScope.cancel() }
    }

    @Test fun explicitAccountErrorRetryUsesOneSharedRequestAndStopsAfterRecovery() = runTest(dispatcher) {
        val recovered = CompletableDeferred<AccountProfile?>()
        var reads = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(), readAccountProfile = {
            if (++reads == 1) error("Fixture unavailable") else recovered.await()
        })
        try {
            model.loadAccount("account-retry.example", 0); runCurrent()
            val session = requireNotNull(model.state.value.accountSession)
            model.loadAccount("account-retry.example", 0); runCurrent()
            assertEquals(1, reads)
            model.retryAccount("account-retry.example", 1, 0, session); runCurrent()
            assertEquals(1, reads) // A callback from another revision cannot reset the current request key.
            model.retryAccount("account-retry.example", 0, 0, session)
            assertTrue(model.state.value.accountLoading)
            model.retryAccount("account-retry.example", 0, 0, session)
            model.loadAccount("account-retry.example", 0); runCurrent()
            assertEquals(2, reads)
            recovered.complete(AccountProfile(true, "", false, "", "RecoveredNickname")); runCurrent()
            assertEquals("", model.state.value.accountError)
            assertFalse(model.state.value.accountLoading)
            assertEquals("RecoveredNickname", model.state.value.nickname)
            model.retryAccount("account-retry.example", 0, 0, session); runCurrent()
            assertEquals(2, reads)
        } finally { recovered.complete(null); model.viewModelScope.cancel() }
    }

    @Test fun staleAccountRetryCannotReplaceTheNewSessionRequest() = runTest(dispatcher) {
        val nextProfile = CompletableDeferred<AccountProfile?>()
        var reads = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(), readAccountProfile = {
            if (++reads == 1) error("Old owner fixture failure") else nextProfile.await()
        })
        try {
            model.loadAccount("account-owner.example", 0); runCurrent()
            val oldSession = requireNotNull(model.state.value.accountSession)
            com.bobyl.tv.data.AccountSessionGuard.shared.change("account-owner.example") { }
            model.loadAccount("account-owner.example", 0); runCurrent()
            val newSession = requireNotNull(model.state.value.accountSession)
            assertNotEquals(oldSession, newSession)
            assertNull(model.state.value.profile)
            model.retryAccount("account-owner.example", 0, 0, oldSession); runCurrent()
            assertEquals(2, reads)
            nextProfile.complete(AccountProfile(true, "", false, "", "CurrentOwner")); runCurrent()
            assertEquals(newSession, model.state.value.accountSession)
            assertEquals("CurrentOwner", model.state.value.nickname)
            assertEquals("", model.state.value.accountError)
        } finally { nextProfile.complete(null); model.viewModelScope.cancel() }
    }

    @Test fun optionalPurchasedPeriodNeverBlocksNicknameAndUnchangedReentryDoesNotReadAgain() = runTest(dispatcher) {
        val period = CompletableDeferred<Int?>()
        var periodReads = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(),
            readAccountProfile = { AccountProfile(true, "", true, "Осталось 109 дней", "FixtureNickname") },
            readPremiumPeriod = { _, _, _ -> periodReads++; period.await() })
        try {
            model.loadAccount("premium.example", 0); runCurrent()
            assertEquals("FixtureNickname", model.state.value.nickname)
            assertFalse(model.state.value.accountLoading)
            assertNull(model.state.value.premiumFraction)
            model.loadAccount("premium.example", 0); runCurrent()
            assertEquals(1, periodReads)
            period.complete(180); runCurrent()
            assertEquals(109f / 180f, model.state.value.premiumFraction!!, .0001f)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun sameAccountBackgroundRefreshKeepsVisibleProfileAndFillUntilNewEvidenceArrives() = runTest(dispatcher) {
        val nextProfile = CompletableDeferred<AccountProfile?>()
        val nextPeriod = CompletableDeferred<Int?>()
        var profiles = 0
        var periods = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(),
            readAccountProfile = { if (++profiles == 1) AccountProfile(true, "", true, "109 дней", "FixtureNickname") else nextProfile.await() },
            readPremiumPeriod = { _, _, _ -> if (++periods == 1) 180 else nextPeriod.await() })
        try {
            model.loadAccount("premium.example", 0); runCurrent()
            val original = model.state.value
            model.loadAccount("premium.example", 0, refreshRevision = 1); runCurrent()
            assertEquals(original.nickname, model.state.value.nickname)
            assertEquals(original.profile, model.state.value.profile)
            assertEquals(original.premiumFraction, model.state.value.premiumFraction)
            nextProfile.complete(AccountProfile(true, "", true, "108 дней", "FixtureNickname")); runCurrent()
            assertEquals(original.premiumFraction, model.state.value.premiumFraction)
            nextPeriod.complete(null); runCurrent()
            assertNull(model.state.value.premiumFraction)
            assertEquals("108 дней", model.state.value.profile?.premiumInfo)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun premiumActivationExplicitlyInvalidatesNegativeEvidence() = runTest(dispatcher) {
        var premium = false
        val forces = mutableListOf<Boolean>()
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(),
            readAccountProfile = { AccountProfile(true, "", premium, "109 дней", "FixtureNickname") },
            readPremiumPeriod = { _, _, force -> forces += force; 180 })
        try {
            model.loadAccount("premium.example", 0); runCurrent()
            assertTrue(forces.isEmpty())
            premium = true
            model.loadAccount("premium.example", 1); runCurrent()
            assertEquals(listOf(true), forces)
            assertNotNull(model.state.value.premiumFraction)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun lateOptionalResponseCannotPaintAnotherAccountAfterSessionChanges() = runTest(dispatcher) {
        val oldPeriod = CompletableDeferred<Int?>()
        val nextProfile = CompletableDeferred<AccountProfile?>()
        var profiles = 0
        var periods = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(),
            readAccountProfile = { if (++profiles == 1) AccountProfile(true, "", true, "109 дней", "OldNickname") else nextProfile.await() },
            readPremiumPeriod = { _, _, _ -> if (++periods == 1) withContext(NonCancellable) { oldPeriod.await() } else 30 })
        try {
            model.loadAccount("premium.example", 0); runCurrent()
            com.bobyl.tv.data.AccountSessionGuard.shared.change("premium.example") { }
            model.loadAccount("premium.example", 0); runCurrent()
            assertEquals("", model.state.value.nickname)
            assertNull(model.state.value.profile)
            assertNull(model.state.value.premiumFraction)
            oldPeriod.complete(180); runCurrent()
            assertNull(model.state.value.premiumFraction)
            nextProfile.complete(AccountProfile(true, "", true, "15 дней", "NewNickname")); runCurrent()
            assertEquals("NewNickname", model.state.value.nickname)
            assertEquals(.5f, model.state.value.premiumFraction!!, .0001f)
        } finally { oldPeriod.complete(null); model.viewModelScope.cancel() }
    }

    @Test fun scopedLibraryChangeRefreshesOnlySummaryWhileForegroundAndSessionRefreshContent() = runTest(dispatcher) {
        var filmReads = 0
        val summaryForces = mutableListOf<Boolean>()
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(),
            loadFilms = { _, _ -> filmReads++; listOf(film("one")) },
            loadCategories = { _, force -> summaryForces += force; categories(summaryForces.size) })
        try {
            model.loadContent("home.example", 0, 0)
            model.loadFavorites("home.example", 0, 0, 0)
            runCurrent()
            model.loadContent("home.example", 0, 0)
            model.loadFavorites("home.example", 0, 0, 0)
            runCurrent()
            assertEquals(1, filmReads)
            assertEquals(listOf(false), summaryForces)

            model.loadContent("home.example", 0, 0)
            model.loadFavorites("home.example", 0, 0, 1)
            runCurrent()
            assertEquals(1, filmReads)
            assertEquals(listOf(false, true), summaryForces)
            assertEquals(2, model.state.value.favorites)

            model.loadContent("home.example", 0, 1)
            model.loadFavorites("home.example", 0, 1, 1)
            runCurrent()
            assertEquals(2, filmReads)
            assertEquals(3, summaryForces.size)

            model.loadContent("home.example", 1, 1)
            model.loadFavorites("home.example", 1, 1, 1)
            runCurrent()
            assertEquals(3, filmReads)
            assertEquals(4, summaryForces.size)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun explicitHomeRefreshAndRetryReachTheRepositoryForceFlag() = runTest(dispatcher) {
        val forces = mutableListOf<Boolean>()
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(), loadFilms = { _, force ->
            forces += force; listOf(film("one"))
        }, loadCategories = { _, _ -> emptyList() })
        try {
            model.loadContent("home.example", 0, 0); runCurrent()
            model.loadContent("home.example", 0, 0); runCurrent()
            assertEquals(listOf(false), forces)
            model.loadContent("home.example", 0, 1); runCurrent()
            model.loadContent("home.example", 0, 1, force = true); runCurrent()
            assertEquals(listOf(false, true, true), forces)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun multipleBookmarkChangesWaitUntilCardClosesAndReadOnlyReturnDoesNotFetch() = runTest(dispatcher) {
        var reads = 0
        val forces = mutableListOf<Boolean>()
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(), loadFilms = { _, _ -> emptyList() },
            loadCategories = { _, force -> reads++; forces += force; categories(reads) })
        try {
            model.loadFavorites("home.example", 0, 0, 0); runCurrent()
            model.loadFavorites("home.example", 0, 0, 0, visible = false); runCurrent()
            model.loadFavorites("home.example", 0, 0, 0); runCurrent()
            assertEquals(1, reads)
            model.loadFavorites("home.example", 0, 0, 1, visible = false)
            model.loadFavorites("home.example", 0, 0, 2, visible = false)
            runCurrent()
            assertEquals(1, reads)
            model.loadFavorites("home.example", 0, 0, 2); runCurrent()
            assertEquals(2, reads)
            assertEquals(listOf(false, true), forces)
            model.loadFavorites("home.example", 0, 0, 2); runCurrent()
            assertEquals(2, reads)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun refreshingKeepsVisibleContentAndFavoriteCountUntilNewValuesArrive() = runTest(dispatcher) {
        val nextFilms = CompletableDeferred<List<CatalogFilm>>()
        val nextSummary = CompletableDeferred<List<BookmarkCategory>>()
        var filmReads = 0
        var summaryReads = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(),
            loadFilms = { _, _ -> if (++filmReads == 1) listOf(film("old")) else nextFilms.await() },
            loadCategories = { _, _ -> if (++summaryReads == 1) categories(5) else nextSummary.await() })
        try {
            model.loadContent("home.example", 0, 0)
            model.loadFavorites("home.example", 0, 0, 0)
            runCurrent()
            model.loadContent("home.example", 0, 1)
            model.loadFavorites("home.example", 0, 1, 0)
            runCurrent()
            assertTrue(model.state.value.loading)
            assertTrue(model.state.value.favoritesLoading)
            assertEquals("old", model.state.value.films.single().id)
            assertEquals(5, model.state.value.favorites)
            nextFilms.complete(listOf(film("new")))
            nextSummary.complete(categories(7))
            runCurrent()
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.favoritesLoading)
            assertEquals("new", model.state.value.films.single().id)
            assertEquals(7, model.state.value.favorites)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun changingAccountDoesNotDisplayThePreviousAccountsFavoriteCount() = runTest(dispatcher) {
        val nextSummary = CompletableDeferred<List<BookmarkCategory>>()
        var reads = 0
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(), loadFilms = { _, _ -> emptyList() },
            loadCategories = { _, _ -> if (++reads == 1) categories(5) else nextSummary.await() })
        try {
            model.loadFavorites("home.example", 0, 0, 0)
            runCurrent()
            assertEquals(5, model.state.value.favorites)
            model.loadFavorites("home.example", 1, 0, 0)
            runCurrent()
            assertNull(model.state.value.favorites)
            nextSummary.complete(categories(2))
            runCurrent()
            assertEquals(2, model.state.value.favorites)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun optionalFeaturedMetadataFinishesWithAnErrorAfterFourSeconds() = runTest(dispatcher) {
        val response = CompletableDeferred<FilmDetails>()
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(), readFeatured = { response.await() })
        try {
            model.loadFeatured("https://home.example/series/42-fixture.html"); runCurrent()
            assertTrue(model.state.value.featuredLoading)
            advanceTimeBy(4_000); runCurrent()
            assertFalse(model.state.value.featuredLoading)
            assertTrue(model.state.value.featuredError.isNotBlank())
            assertNull(model.state.value.featured)
            assertEquals("https://home.example/series/42-fixture.html", model.state.value.featuredRequestUrl)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun lateOldFeatureCannotReplaceNewUrlAndSameUrlDoesNotDuplicateRequests() = runTest(dispatcher) {
        val old = CompletableDeferred<FilmDetails>()
        val calls = mutableListOf<String>()
        val first = "https://home.example/series/1-old.html"
        val next = "https://home.example/series/2-new.html"
        val model = HomeDashboardViewModel(RuntimeEnvironment.getApplication(), readFeatured = { url ->
            calls += url
            if (url == first) withContext(NonCancellable) { old.await() }
            else FilmDetails("New", "", "", "", emptyList(), id = "2")
        })
        try {
            model.loadFeatured(first); runCurrent()
            model.loadFeatured(next); runCurrent()
            model.loadFeatured(next); runCurrent()
            assertEquals(listOf(first, next), calls)
            assertEquals("New", model.state.value.featured?.title)
            old.complete(FilmDetails("Old", "", "", "", emptyList(), id = "1")); runCurrent()
            assertEquals(next, model.state.value.featuredRequestUrl)
            assertEquals("New", model.state.value.featured?.title)
            assertFalse(model.state.value.featuredLoading)
        } finally { old.complete(FilmDetails("Old", "", "", "", emptyList())); model.viewModelScope.cancel() }
    }
}
