package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdatesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val origin = "https://updates.example/"
    private val account = AccountSession(origin, "owner")
    private fun row(title: String = "Series") = SeriesUpdate("Сегодня",
        CatalogFilm("42", "${origin}series/42-example.html", title, "poster", "", "", "", ""), "1 сезон · 2 серия", true)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun unchangedReentrySharesPreviewWithoutRequestsAndHiddenEventsOnlyMarkDirty() = runTest(dispatcher) {
        val events = MutableStateFlow(0)
        val forces = mutableListOf<Boolean>()
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, force -> forces += force; listOf(row()) },
            changes = events, sessionFor = { account })
        try {
            model.preview(origin); runCurrent()
            model.open(origin); model.preview(origin); model.open(origin); runCurrent()
            assertEquals(listOf(false), forces)
            events.value = 1; runCurrent()
            assertTrue(model.state.value.dirty)
            assertEquals(listOf(false), forces)
            model.preview(origin); runCurrent()
            assertEquals(1, forces.size)
            model.open(origin); runCurrent()
            assertEquals(listOf(false, true), forces)
            assertFalse(model.state.value.dirty)
            model.open(origin); runCurrent()
            assertEquals(2, forces.size)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun eventDuringRequestStaysDirtyUntilNextOpenAndKeepsVisibleRows() = runTest(dispatcher) {
        val events = MutableStateFlow(0)
        val response = CompletableDeferred<List<SeriesUpdate>>()
        var reads = 0
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ ->
            if (++reads == 2) response.await() else listOf(row("Version $reads"))
        }, changes = events, sessionFor = { account })
        try {
            model.open(origin); runCurrent()
            events.value = 1; runCurrent()
            model.open(origin); runCurrent()
            assertTrue(model.state.value.busy)
            assertEquals("Version 1", model.state.value.rows.single().film.title)
            events.value = 2; runCurrent()
            response.complete(listOf(row("Version 2"))); runCurrent()
            assertTrue(model.state.value.dirty)
            assertEquals(2, reads)
            model.preview(origin); runCurrent()
            assertEquals(2, reads)
            model.open(origin); runCurrent()
            assertEquals(3, reads)
            assertFalse(model.state.value.dirty)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun newEventsBeforeFirstVisitBypassPersistentCache() = runTest(dispatcher) {
        val forces = mutableListOf<Boolean>()
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, force -> forces += force; emptyList() },
            changes = MutableStateFlow(4), sessionFor = { account })
        try {
            model.open(origin); runCurrent()
            assertEquals(listOf(true), forces)
            assertFalse(model.state.value.dirty)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun explicitForegroundTokenAndRetryWorkWhileErrorsKeepCachedRows() = runTest(dispatcher) {
        val events = MutableStateFlow(0)
        val forces = mutableListOf<Boolean>()
        var reads = 0
        var fail = false
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, force ->
            forces += force
            reads++; if (fail) throw IOException("offline") else listOf(row("Loaded $reads"))
        }, changes = events, sessionFor = { account })
        try {
            model.open(origin, 0); runCurrent()
            fail = true
            model.open(origin, 1); runCurrent()
            assertEquals(2, reads)
            assertEquals(listOf(false, true), forces)
            assertEquals("Loaded 1", model.state.value.rows.single().film.title)
            assertFalse(model.state.value.busy)
            assertTrue(model.state.value.dirty)
            assertTrue(model.state.value.error.contains("offline"))
            model.preview(origin); runCurrent()
            assertEquals(2, reads)
            fail = false
            model.retry(origin); runCurrent()
            assertEquals(3, reads)
            assertEquals("", model.state.value.error)
            model.open(origin, 1); runCurrent()
            assertEquals(3, reads)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun watchAndUnwatchEventsInvalidateWithoutFetchingUntilTheSectionOpens() = runTest(dispatcher) {
        var reads = 0
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ -> reads++; listOf(row()) },
            changes = ServerHistoryChanges.revision, sessionFor = { account })
        try {
            model.open(origin); runCurrent()
            ServerHistoryChanges.filmChanged(account, row().film.url, watched = true)
            runCurrent()
            assertTrue(model.state.value.dirty)
            assertEquals(1, reads)
            model.open(origin); runCurrent()
            assertEquals(2, reads)
            ServerHistoryChanges.filmChanged(account, row().film.url, watched = false)
            runCurrent()
            assertTrue(model.state.value.dirty)
            assertEquals(2, reads)
            model.open(origin); runCurrent()
            assertEquals(3, reads)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun sessionChangeDuringRequestDiscardsItsResultEvenBeforeAnotherOpen() = runTest(dispatcher) {
        val response = CompletableDeferred<List<SeriesUpdate>>()
        var session = account
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ -> response.await() },
            changes = MutableStateFlow(0), sessionFor = { session })
        try {
            model.open(origin); runCurrent()
            session = account.copy(token = "new-owner")
            response.complete(listOf(row("Old account"))); runCurrent()
            assertTrue(model.state.value.rows.isEmpty())
            assertFalse(model.state.value.busy)
            assertTrue(model.state.value.dirty)
        } finally { response.complete(emptyList()); model.viewModelScope.cancel() }
    }

    @Test fun lateOldSessionResponseCannotOverwriteNewAccountsRows() = runTest(dispatcher) {
        val response = CompletableDeferred<List<SeriesUpdate>>()
        var session = account
        var reads = 0
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ ->
            if (++reads == 1) withContext(NonCancellable) { response.await() } else listOf(row("New account"))
        }, changes = MutableStateFlow(0), sessionFor = { session })
        try {
            model.open(origin); runCurrent()
            session = account.copy(token = "new-owner")
            model.open(origin); runCurrent()
            assertEquals("New account", model.state.value.rows.single().film.title)
            response.complete(listOf(row("Old account"))); runCurrent()
            assertEquals("New account", model.state.value.rows.single().film.title)
            assertFalse(model.state.value.busy)
        } finally { response.complete(emptyList()); model.viewModelScope.cancel() }
    }

    @Test fun providerChangeDoesNotReuseAnotherProvidersList() = runTest(dispatcher) {
        val reads = mutableListOf<String>()
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { provider, _ -> reads += provider; listOf(row(provider)) },
            changes = MutableStateFlow(0), sessionFor = { AccountSession(it, "owner") })
        try {
            model.open(origin); runCurrent()
            model.open("https://other.example/"); runCurrent()
            assertEquals(listOf(origin, "https://other.example/"), reads)
            assertEquals("https://other.example/", model.state.value.rows.single().film.title)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun removingATrackedFilmNeverAcknowledgesAnUnrelatedPendingEvent() = runTest(dispatcher) {
        val guard = AccountSessionGuard(dispatcher)
        val removalOwner = guard.current(origin)
        val events = MutableStateFlow(0)
        val removal = CompletableDeferred<Unit>()
        var reads = 0
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ -> reads++; listOf(row()) },
            changes = events, sessionFor = guard::current, removeUpdate = { _, _ -> removal.await() },
            notifyRemoval = { session, _ -> assertEquals(removalOwner, session); events.value++ }, sessions = guard)
        try {
            model.open(origin); runCurrent()
            model.remove(origin, row().film.url); runCurrent()
            events.value++; runCurrent()
            removal.complete(Unit); runCurrent()
            assertFalse(model.state.value.rows.single().tracked)
            assertTrue(model.state.value.dirty)
            assertEquals(1, reads)
            model.open(origin); runCurrent()
            assertEquals(2, reads)
        } finally { removal.complete(Unit); model.viewModelScope.cancel() }
    }

    @Test fun playbackServerCompletionInvalidatesAListReadBetweenLocalAndRemoteSave() = runTest(dispatcher) {
        val server = CompletableDeferred<Unit>()
        var serverVersion = "Before remote save"
        var reads = 0
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ -> reads++; listOf(row(serverVersion)) },
            changes = ServerHistoryChanges.revision, sessionFor = { account })
        try {
            model.open(origin); runCurrent()
            val film = row().film
            val snapshot = PlaybackProgressSnapshot("progress", FilmActionKey.of(account, film.url),
                WatchRecord(film.id, film.url, film.title, film.poster, "1", "1", "2", 1000), false, false, 1000)
            val save = launch { persistPlaybackProgress(snapshot, local = { _, _ -> }, server = {
                server.await(); serverVersion = "After remote save"
            }) }
            runCurrent()
            model.open(origin); runCurrent()
            assertFalse(model.state.value.dirty)
            assertEquals("Before remote save", model.state.value.rows.single().film.title)
            server.complete(Unit); runCurrent(); save.join()
            assertTrue(model.state.value.dirty)
            assertEquals(2, reads)
            model.open(origin); runCurrent()
            assertEquals(3, reads)
            assertEquals("After remote save", model.state.value.rows.single().film.title)
        } finally { server.complete(Unit); model.viewModelScope.cancel() }
    }

    @Test fun acceptedRemovalKeepsOneAccountAcrossAllRequests() = runTest(dispatcher) {
        val guard = AccountSessionGuard(dispatcher)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cookie = "old-account"
        val observed = mutableListOf<String>()
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ -> listOf(row()) },
            changes = MutableStateFlow(0), sessionFor = guard::current, sessions = guard,
            removeUpdate = { _, _ ->
                observed += cookie
                started.complete(Unit)
                release.await()
                observed += cookie
            }, notifyRemoval = { _, _ -> })
        try {
            model.open(origin); runCurrent()
            model.remove(origin, row().film.url); started.await()
            val switch = async { guard.change(origin) { cookie = "new-account" } }
            runCurrent()
            assertFalse(switch.isCompleted)
            release.complete(Unit)
            switch.await()
            assertEquals(listOf("old-account", "old-account"), observed)
            assertEquals("new-account", cookie)
        } finally { release.complete(Unit); model.viewModelScope.cancel() }
    }

    @Test fun cancellingBeforeRemovalStartsReleasesTheAcceptedPermit() = runTest(dispatcher) {
        val guard = AccountSessionGuard(dispatcher)
        var requests = 0
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ -> listOf(row()) },
            changes = MutableStateFlow(0), sessionFor = guard::current, sessions = guard,
            removeUpdate = { _, _ -> requests++ }, notifyRemoval = { _, _ -> })
        model.open(origin); runCurrent()
        model.remove(origin, row().film.url)
        model.viewModelScope.cancel()
        val switch = async { guard.change(origin) { "changed" } }
        runCurrent()
        assertEquals("changed", switch.await())
        assertEquals(0, requests)
    }

    @Test fun manualClearRefreshesHomePreviewOnceAndRouteOpenSharesTheSameToken() = runTest(dispatcher) {
        var clearEpoch = 0
        val events = MutableStateFlow(0)
        val forces = mutableListOf<Boolean>()
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, force ->
            forces += force; listOf(row("Read ${forces.size}"))
        }, changes = events, sessionFor = { account }, userClearRevision = { clearEpoch })
        try {
            model.preview(origin, 3); runCurrent()
            events.value++; runCurrent()
            model.preview(origin, 4); runCurrent() // Ordinary dirty/foreground changes stay lazy on Home.
            assertEquals(listOf(false), forces)
            clearEpoch++
            model.preview(origin, 5); runCurrent()
            model.preview(origin, 5); model.open(origin, 5); runCurrent()
            assertEquals(listOf(false, true), forces)
            assertEquals("Read 2", model.state.value.rows.single().film.title)
            assertFalse(model.state.value.dirty)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun newerManualClearDuringVisiblePreviewReadCannotBeAcknowledgedByOldResponse() = runTest(dispatcher) {
        var clearEpoch = 0
        val initial = CompletableDeferred<List<SeriesUpdate>>()
        val cleared = CompletableDeferred<List<SeriesUpdate>>()
        var reads = 0
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ ->
            when (++reads) { 1 -> initial.await(); 2 -> cleared.await(); else -> listOf(row("Latest")) }
        }, changes = MutableStateFlow(0), sessionFor = { account }, userClearRevision = { clearEpoch })
        try {
            model.preview(origin, 0); runCurrent()
            clearEpoch = 1; model.preview(origin, 1)
            initial.complete(listOf(row("Old"))); runCurrent()
            assertEquals(2, reads)
            clearEpoch = 2; model.preview(origin, 2)
            cleared.complete(listOf(row("Cleared again"))); runCurrent()
            assertEquals(3, reads)
            assertEquals("Latest", model.state.value.rows.single().film.title)
            model.open(origin, 2); model.preview(origin, 2); runCurrent()
            assertEquals(3, reads)
        } finally { initial.complete(emptyList()); cleared.complete(emptyList()); model.viewModelScope.cancel() }
    }

    @Test fun failedManualPreviewRefreshKeepsRowsAndDoesNotRetryUntilExplicitRetryOrNewClear() = runTest(dispatcher) {
        var clearEpoch = 0
        var reads = 0
        val model = UpdatesViewModel(RuntimeEnvironment.getApplication(), readUpdates = { _, _ ->
            if (++reads == 2) throw IOException("offline") else listOf(row("Read $reads"))
        }, changes = MutableStateFlow(0), sessionFor = { account }, userClearRevision = { clearEpoch })
        try {
            model.preview(origin); runCurrent()
            clearEpoch++
            model.preview(origin, 1); runCurrent()
            assertEquals("Read 1", model.state.value.rows.single().film.title)
            assertEquals("offline", model.state.value.error)
            repeat(3) { model.preview(origin, 1) }; runCurrent()
            assertEquals(2, reads)
            model.retry(origin); runCurrent()
            model.open(origin, 1); model.preview(origin, 1); runCurrent()
            assertEquals(3, reads)
            assertEquals("Read 3", model.state.value.rows.single().film.title)
        } finally { model.viewModelScope.cancel() }
    }
}
