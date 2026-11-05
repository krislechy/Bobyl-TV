package com.bobyl.tv

import android.app.Application
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import com.bobyl.tv.data.database.BobylDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val origin = "https://history.example/"
    private val owner = AccountSession(origin, "owner")
    private val film = CatalogFilm("1", "${origin}films/1-name.html", "Film", "poster", "", "", "", "")
    private val local = WatchRecord("1", film.url, "Film", "poster", "1", "1", "2", 1000, 100)
    private val remote = ContinueItem("10", film, "account", viewedTimestamp = 100)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun filmChangeAndReturningToTabNeverRefetchWholeHistory() = runTest(dispatcher) {
        val patches = MutableStateFlow(HistoryFilmChanges())
        var reads = 0
        val model = HistoryViewModel.forTests(RuntimeEnvironment.getApplication(), readRecords = { listOf(local) },
            readServer = { _, _ -> reads++; listOf(remote) }, changes = patches, currentSession = { owner })
        try {
            model.load(origin); runCurrent()
            assertEquals(1, reads)
            patches.value = HistoryFilmChanges(1, listOf(HistoryFilmChange(owner, "1",
                records = listOf(local.copy(watched = true)), server = listOf(remote.copy(watched = true)), sequence = 1)))
            runCurrent()
            val entry = historyEntries(model.state.value.records, model.state.value.server).single()
            assertTrue(entry.watched)
            assertEquals(1, entry.records.size)
            assertEquals(1, entry.server.size)
            model.load(origin); runCurrent()
            assertEquals(1, reads)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun initialLoadOverlaysPriorFilmPatchEvenWhenReaderReturnsOldCachedSnapshot() = runTest(dispatcher) {
        val patches = MutableStateFlow(HistoryFilmChanges(1, listOf(HistoryFilmChange(owner, "1",
            records = emptyList(), server = emptyList(), sequence = 1))))
        val model = HistoryViewModel.forTests(RuntimeEnvironment.getApplication(), readRecords = { listOf(local) },
            readServer = { _, _ -> listOf(remote) }, changes = patches, currentSession = { owner })
        try {
            model.load(origin); runCurrent()
            assertTrue(model.state.value.records.isEmpty())
            assertTrue(model.state.value.server.isEmpty())
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun patchDuringInitialRequestWinsOverLateStaleResponse() = runTest(dispatcher) {
        val patches = MutableStateFlow(HistoryFilmChanges())
        val response = CompletableDeferred<List<ContinueItem>>()
        val model = HistoryViewModel.forTests(RuntimeEnvironment.getApplication(), readRecords = { listOf(local) },
            readServer = { _, _ -> response.await() }, changes = patches, currentSession = { owner })
        try {
            model.load(origin); runCurrent()
            patches.value = HistoryFilmChanges(1, listOf(HistoryFilmChange(owner, "1", watched = true, sequence = 1)))
            runCurrent()
            response.complete(listOf(remote)); runCurrent()
            assertTrue(historyEntries(model.state.value.records, model.state.value.server).single().watched)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun anotherProviderOrAccountCannotPatchCurrentHistory() = runTest(dispatcher) {
        val patches = MutableStateFlow(HistoryFilmChanges())
        var active = owner
        val model = HistoryViewModel.forTests(RuntimeEnvironment.getApplication(), readRecords = { listOf(local) },
            readServer = { _, _ -> listOf(remote) }, changes = patches, currentSession = { active })
        try {
            model.load(origin); runCurrent()
            patches.value = HistoryFilmChanges(2, listOf(
                HistoryFilmChange(owner.copy(provider = "https://other.example/"), "1", watched = true, sequence = 1),
                HistoryFilmChange(owner.copy(token = "other-account"), "1", server = emptyList(), sequence = 2)))
            runCurrent()
            assertFalse(model.state.value.server.single().watched)
            active = owner.copy(token = "new-account")
            patches.value = HistoryFilmChanges(3, listOf(HistoryFilmChange(owner, "1", watched = true, sequence = 3)))
            runCurrent()
            assertFalse(model.state.value.server.single().watched)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun explicitRefreshCanReplaceOldPatchWithNewServerTruth() = runTest(dispatcher) {
        val patches = MutableStateFlow(HistoryFilmChanges(1, listOf(HistoryFilmChange(owner, "1", watched = true, sequence = 1))))
        val forces = mutableListOf<Boolean>()
        val model = HistoryViewModel.forTests(RuntimeEnvironment.getApplication(), readRecords = { listOf(local) },
            readServer = { _, force -> forces += force; listOf(remote) }, changes = patches, currentSession = { owner })
        try {
            model.load(origin); runCurrent()
            assertTrue(model.state.value.server.single().watched)
            model.refresh(origin); runCurrent()
            assertFalse(model.state.value.server.single().watched)
            assertEquals(listOf(false, true), forces)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun successfulExplicitRefreshAcknowledgesOldPatchSoNewScreenDoesNotResurrectIt() = runTest(dispatcher) {
        ServerHistoryChanges.acknowledge(owner, Long.MAX_VALUE)
        ServerHistoryChanges.filmChanged(owner, film.url, watched = true)
        fun create() = HistoryViewModel.forTests(RuntimeEnvironment.getApplication(),
            readRecords = { listOf(local) }, readServer = { _, _ -> listOf(remote) },
            changes = ServerHistoryChanges.films, currentSession = { owner },
            acknowledgeChanges = ServerHistoryChanges::acknowledge)
        val first = create()
        var next: HistoryViewModel? = null
        try {
            first.load(origin); runCurrent()
            assertTrue(first.state.value.server.single().watched)
            first.refresh(origin); runCurrent()
            assertFalse(first.state.value.server.single().watched)
            assertTrue(ServerHistoryChanges.films.value.films.none { it.session == owner })
            next = create()
            next.load(origin); runCurrent()
            assertFalse(next.state.value.server.single().watched)
        } finally {
            first.viewModelScope.cancel(); next?.viewModelScope?.cancel()
            ServerHistoryChanges.acknowledge(owner, Long.MAX_VALUE)
        }
    }

    @Test fun explicitRefreshKeepsNewerPatchPublishedWhileItsResponseWasPending() = runTest(dispatcher) {
        ServerHistoryChanges.acknowledge(owner, Long.MAX_VALUE)
        val response = CompletableDeferred<List<ContinueItem>>()
        val model = HistoryViewModel.forTests(RuntimeEnvironment.getApplication(), readRecords = { listOf(local) },
            readServer = { _, force -> if (force) response.await() else listOf(remote) },
            changes = ServerHistoryChanges.films, currentSession = { owner },
            acknowledgeChanges = ServerHistoryChanges::acknowledge)
        try {
            model.load(origin); runCurrent()
            model.refresh(origin); runCurrent()
            ServerHistoryChanges.filmChanged(owner, film.url, watched = true)
            runCurrent()
            response.complete(listOf(remote)); runCurrent()
            assertTrue(model.state.value.server.single().watched)
            assertTrue(ServerHistoryChanges.films.value.films.any { it.session == owner && it.key == "1" })
        } finally {
            model.viewModelScope.cancel()
            ServerHistoryChanges.acknowledge(owner, Long.MAX_VALUE)
        }
    }

    @Test fun sourceClearUpdatesRetainedRowsAndOlderFilmPatchesCannotResurrectThem() = runTest(dispatcher) {
        val patches = MutableStateFlow(HistoryFilmChanges())
        var reads = 0
        val model = HistoryViewModel.forTests(RuntimeEnvironment.getApplication(), readRecords = { listOf(local) },
            readServer = { _, _ -> reads++; listOf(remote) }, changes = patches, currentSession = { owner })
        try {
            model.load(origin); runCurrent()
            patches.value = HistoryFilmChanges(2,
                listOf(HistoryFilmChange(owner, "1", records = listOf(local), server = listOf(remote), sequence = 1)),
                listOf(HistoryClearChange(owner, device = true, sequence = 2)))
            runCurrent()
            assertTrue(model.state.value.records.isEmpty())
            assertEquals(listOf(remote), model.state.value.server)
            model.load(origin); runCurrent()
            assertEquals(1, reads)
            patches.value = patches.value.copy(sequence = 3, clears = patches.value.clears +
                HistoryClearChange(owner, device = false, sequence = 3))
            runCurrent()
            assertTrue(model.state.value.server.isEmpty())
            assertEquals(1, reads)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun partialClearRetainsRowsUntilNextOpenForcesOneRecoveryRead() = runTest(dispatcher) {
        val patches = MutableStateFlow(HistoryFilmChanges())
        val forces = mutableListOf<Boolean>()
        val model = HistoryViewModel.forTests(RuntimeEnvironment.getApplication(), readRecords = { listOf(local) },
            readServer = { _, force -> forces += force; if (force) emptyList() else listOf(remote) },
            changes = patches, currentSession = { owner }, acknowledgeChanges = { session, sequence ->
                patches.value = patches.value.copy(clears = patches.value.clears.filterNot {
                    it.session == session && it.sequence <= sequence
                })
            })
        try {
            model.load(origin); runCurrent()
            patches.value = HistoryFilmChanges(1, clears = listOf(
                HistoryClearChange(owner, device = false, sequence = 1, completed = false)))
            assertEquals(listOf(remote), model.state.value.server)
            // Open can precede the flow collector; it must observe the clear synchronously.
            model.load(origin); runCurrent()
            assertEquals(listOf(false, true), forces)
            assertTrue(model.state.value.server.isEmpty())
            model.load(origin); runCurrent()
            assertEquals(2, forces.size)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun watchedPatchAfterAConfirmedClearNeverCarriesTheOldSourceBack() = runTest(dispatcher) {
        ServerHistoryChanges.acknowledge(owner, Long.MAX_VALUE)
        try {
            ServerHistoryChanges.filmChanged(owner, film.url, records = listOf(local), server = listOf(remote))
            ServerHistoryChanges.historyCleared(owner, device = false)
            ServerHistoryChanges.filmChanged(owner, film.url, watched = true)
            val patch = ServerHistoryChanges.films.value.films.single { it.session == owner }
            assertTrue(patch.server!!.isEmpty())
            assertEquals(listOf(local), patch.records)
        } finally { ServerHistoryChanges.acknowledge(owner, Long.MAX_VALUE) }
    }

    @Test fun markingSeveralAccountRowsHoldsTheSessionUntilEveryRequestFinishes() = runTest(dispatcher) {
        val application = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(application, BobylDatabase::class.java).build()
        val guard = AccountSessionGuard(dispatcher)
        val mutationOwner = guard.current(origin)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cookie = "old-account"
        val observed = mutableListOf<String>()
        val repository = ExtrasRepository(object : PageTransport {
            override suspend fun get(url: String, details: Boolean): Document = error("Unexpected GET")
            override suspend fun post(url: String, form: Map<String, String>): Document {
                assertTrue(url.endsWith("cdn_saves_view.php"))
                observed += cookie
                if (observed.size == 1) { started.complete(Unit); release.await() }
                return Jsoup.parse("""{"success":true}""")
            }
        })
        val model = HistoryViewModel.forTests(application, readRecords = { emptyList() },
            readServer = { _, _ -> listOf(remote, remote.copy(id = "11")) },
            changes = MutableStateFlow(HistoryFilmChanges()), currentSession = guard::current,
            sessions = guard, repository = repository, historyFor = { WatchHistory.withDatabase(database, it) })
        try {
            model.load(origin); runCurrent()
            model.setWatched(origin, "1", true); started.await()
            val switch = async { guard.change(origin) { cookie = "new-account" } }
            runCurrent()
            assertFalse(switch.isCompleted)
            release.complete(Unit); switch.await()
            assertEquals(listOf("old-account", "old-account"), observed)
            assertEquals("new-account", cookie)
            assertTrue(model.state.value.server.all { it.watched })
        } finally {
            release.complete(Unit); model.viewModelScope.cancel(); database.close()
            ServerHistoryChanges.acknowledge(mutationOwner, Long.MAX_VALUE)
        }
    }
}
