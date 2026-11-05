package com.bobyl.tv.data.database

import android.content.Context
import com.bobyl.tv.data.LocalFileIndex
import com.bobyl.tv.data.PersistentCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

internal sealed interface StorageState {
    data object Loading : StorageState
    data object Ready : StorageState
    data object Failed : StorageState
}

/** Process-owned startup only. No screen queries or accepted account mutations live in this scope. */
internal class StorageStartup(private val scope: CoroutineScope, private val initialize: suspend () -> Unit) {
    private val mutableState = MutableStateFlow<StorageState>(StorageState.Loading)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Synchronized fun start() {
        if ((job?.isActive == true && mutableState.value != StorageState.Failed) || mutableState.value == StorageState.Ready) return
        mutableState.value = StorageState.Loading
        job = scope.launch(Dispatchers.IO) {
            try {
                initialize()
                currentCoroutineContext().ensureActive()
                mutableState.value = StorageState.Ready
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = StorageState.Failed }
        }
    }
}

/** One instance per Application, so test/application sandboxes never inherit another database. */
internal class AppStorage(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var opened: BobylDatabase? = null
    private var films: FilmMetadataStore? = null
    private val startup = StorageStartup(scope) { initialize() }
    val state = startup.state
    fun start() = startup.start()
    fun database(): BobylDatabase = checkNotNull(opened) { "Local storage is not ready" }
    internal suspend fun closeForTests() = withContext(Dispatchers.IO) {
        scope.coroutineContext[Job]?.cancelAndJoin()
        if (FilmMetadataStore.currentOrNull() === films && films != null) {
            PersistentCache.resetForTests()
            LocalFileIndex.resetForTests()
            films?.let(FilmMetadataStore::removeIfInstalled)
        }
        opened?.close()
        opened = null
    }

    private suspend fun initialize() = withContext(Dispatchers.IO) {
        val database = BobylDatabase.build(context)
        try {
            // build() is lazy: explicitly open now, before publishing Ready or mounting any consumers.
            database.openHelper.writableDatabase
            LegacyStorageImport(database).migrate(context)
            com.bobyl.tv.data.PageCache.configure(context.getSharedPreferences("${context.packageName}_preferences",
                Context.MODE_PRIVATE).getInt("cacheMinutes", 60))
            currentCoroutineContext().ensureActive()
            val stores = RoomStorageStores(database)
            PersistentCache.install(stores)
            LocalFileIndex.install(context, stores)
            FilmMetadataStore(database).also { films = it; FilmMetadataStore.install(it) }
            opened = database
            // Optional observations cannot delay the schema gate or turn a file permission problem into DB failure.
            scope.launch {
                try { withTimeoutOrNull(3_000L) { LocalFileIndex.reconcile() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The file index retries when its data is requested. */ }
            }
        } catch (failure: Throwable) {
            database.close()
            throw failure
        }
    }

    companion object {
        private fun owner(context: Context) = (context.applicationContext as com.bobyl.tv.BobylApplication).storage
        fun database(context: Context) = owner(context).database()
    }
}
