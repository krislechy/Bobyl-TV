package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
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
class StartupPreparationTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun selectedSectionCannotReleaseStartupBeforeAllSharedHomeModelsAndFeatureFinish() = runTest(dispatcher) {
        val app = RuntimeEnvironment.getApplication()
        val provider = "https://startup-home.example/"
        val session = AccountSessionGuard.shared.current(provider)
        val films = CompletableDeferred<List<CatalogFilm>>()
        val categories = CompletableDeferred<List<BookmarkCategory>>()
        val profile = CompletableDeferred<AccountProfile?>()
        val historyRows = CompletableDeferred<List<ContinueItem>>()
        val updateRows = CompletableDeferred<List<SeriesUpdate>>()
        val feature = CompletableDeferred<FilmDetails>()
        val premium = CompletableDeferred<Int?>()
        val calls = mutableMapOf<String, Int>()
        fun count(name: String) { calls[name] = (calls[name] ?: 0) + 1 }
        val home = HomeDashboardViewModel(app,
            loadFilms = { _, _ -> count("films"); films.await() },
            loadCategories = { _, _ -> count("favorites"); categories.await() },
            readAccountProfile = { count("profile"); profile.await() },
            readPremiumPeriod = { _, _, _ -> premium.await() },
            readFeatured = { count("feature"); feature.await() })
        val history = HistoryViewModel.forTests(app, readRecords = { emptyList() },
            readServer = { _, _ -> count("history"); historyRows.await() },
            changes = MutableStateFlow(HistoryFilmChanges()), currentSession = { session })
        val updates = UpdatesViewModel(app, readUpdates = { _, _ -> count("updates"); updateRows.await() },
            changes = MutableStateFlow(0), sessionFor = { session }, userClearRevision = { 0 })
        val startup = StartupViewModel(app)
        val synchronizeFeature = launch {
            updates.state.map { state -> state.rows.firstOrNull { it.tracked }?.film?.url.orEmpty() }
                .distinctUntilChanged().collect(home::loadFeatured)
        }
        val observeHome = launch {
            combine(home.state, history.state, updates.state) { state, progress, series ->
                homeContentPrepared(state, progress.busy, series.busy,
                    series.rows.firstOrNull { it.tracked }?.film?.url.orEmpty())
            }.collect { if (it) startup.homeContentReady() }
        }
        val ready = async { startup.awaitPreparedContent() }
        try {
            startup.primaryContentReady() // e.g. Settings is already mounted.
            repeat(2) {
                home.loadAccount(provider, 0)
                home.loadContent(provider, 0, 0)
                home.loadFavorites(provider, 0, 0, 0)
                history.load(provider)
                updates.preview(provider)
            }
            runCurrent()
            assertFalse(ready.isCompleted)
            films.complete(emptyList()); runCurrent()
            assertFalse(ready.isCompleted) // Hot releases alone were the old premature readiness signal.
            profile.complete(AccountProfile(true, "", true, "109 дней", nickname = "RealNickname"))
            categories.complete(listOf(BookmarkCategory("0", "Всё", count = 3)))
            historyRows.complete(emptyList()); runCurrent()
            assertFalse(ready.isCompleted)
            val film = CatalogFilm("42", "${provider}series/42-fixture.html", "Actual series", "", "", "", "", "")
            updateRows.complete(listOf(SeriesUpdate("Сегодня", film, "1 сезон · 2 серия", true))); runCurrent()
            assertTrue(home.state.value.featuredLoading)
            assertFalse(ready.isCompleted)
            feature.complete(FilmDetails("Actual series", "", "", "Details", emptyList(), id = "42")); runCurrent()
            assertTrue(ready.isCompleted)
            assertFalse(premium.isCompleted) // Optional purchased-period decoration never holds startup.
            assertEquals("RealNickname", home.state.value.nickname)
            assertEquals(setOf("films", "favorites", "profile", "history", "updates", "feature"), calls.keys)
            assertTrue(calls.values.all { it == 1 }) // Hidden/visible Home share the same real requests.
        } finally {
            premium.complete(null)
            ready.cancel(); synchronizeFeature.cancel(); observeHome.cancel()
            home.viewModelScope.cancel(); history.viewModelScope.cancel(); updates.viewModelScope.cancel(); startup.viewModelScope.cancel()
        }
    }

    @Test fun dataReadinessStillWaitsForAlreadyMountedVisibleImages() = runTest(dispatcher) {
        val startup = StartupViewModel(RuntimeEnvironment.getApplication())
        val imageFinished = startup.imageStarted()
        val ready = async { startup.awaitPreparedContent() }
        try {
            startup.primaryContentReady(); runCurrent()
            assertFalse(ready.isCompleted)
            startup.homeContentReady(); runCurrent()
            assertTrue(startup.contentPrepared.value)
            assertFalse(ready.isCompleted)
            advanceTimeBy(1_000); runCurrent()
            assertFalse(ready.isCompleted)
            imageFinished(); runCurrent()
            assertTrue(ready.isCompleted)
        } finally { ready.cancel(); startup.viewModelScope.cancel() }
    }

    @Test fun unavailableImageHasTheExistingFiniteStartupBound() = runTest(dispatcher) {
        val startup = StartupViewModel(RuntimeEnvironment.getApplication())
        val imageFinished = startup.imageStarted()
        val ready = async { startup.awaitPreparedContent() }
        try {
            startup.primaryContentReady(); startup.homeContentReady(); runCurrent()
            assertFalse(ready.isCompleted)
            advanceTimeBy(1_200); runCurrent()
            assertTrue(ready.isCompleted)
        } finally { imageFinished(); ready.cancel(); startup.viewModelScope.cancel() }
    }
}
