package com.bobyl.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface StartupImageTracker {
    fun imageStarted(): () -> Unit
}

/** Owns preparation across navigation; optional requests never hold the splash. */
internal class StartupViewModel(application: Application) : AndroidViewModel(application), StartupImageTracker {
    private val auth = AuthRepository(application)
    private var identity = ""
    private var session: Deferred<String?>? = null
    private val preparedSessions = MutableStateFlow<Pair<String, Deferred<String?>>?>(null)
    private var contentReady = CompletableDeferred<Unit>()
    private var background: Job? = null
    private var startupComplete = false
    private var primaryPrepared = false
    private var homePrepared = false
    private val mutableContentPrepared = MutableStateFlow(false)
    val contentPrepared = mutableContentPrepared.asStateFlow()
    private val pendingImages = MutableStateFlow(0)
    private val mutableUpdateCount = MutableStateFlow(0)
    val updateCount = mutableUpdateCount.asStateFlow()
    private val mutablePreparationErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val preparationErrors = mutablePreparationErrors.asStateFlow()

    suspend fun prepareSession(value: String, startMenu: Int): String? {
        val nextIdentity = "${CatalogUrls.provider(value)}|${PageCache.generation()}"
        if (identity != nextIdentity) {
            val replacingSession = identity.isNotBlank()
            identity = nextIdentity
            background?.cancel()
            session?.cancel()
            contentReady = CompletableDeferred()
            if (replacingSession && !startupComplete) {
                primaryPrepared = false
                homePrepared = false
                mutableContentPrepared.value = false
            }
            publishContentPrepared()
            mutableUpdateCount.value = 0
            mutablePreparationErrors.value = emptyMap()
            val restored = viewModelScope.async { auth.restore(value) }
            session = restored
            preparedSessions.value = CatalogUrls.provider(value) to restored
            val ready = contentReady
            viewModelScope.launch {
                optional("Локальная история") {
                    withContext(Dispatchers.IO) { WatchHistory(getApplication(), value).all() }
                }
                optional("Плееры") { withContext(Dispatchers.IO) { installedPlayers(getApplication()) } }
            }
            background = viewModelScope.launch {
                try { restored.await() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    mutablePreparationErrors.update { it + ("Сессия" to networkErrorMessage(failure)) }
                }
                ready.await()
                // One optional network operation at a time, after primary content.
                if (PageCache.durationMillis() == 0L) return@launch
                // Home now prepares its real shared models. Do not retry those requests via legacy warming.
                if (startMenu != 6) optional("Категории") { BrowseRepository().filters(value) }
            }
        }
        return checkNotNull(session).await()
    }

    suspend fun awaitPreparedSession(value: String): String? {
        val provider = CatalogUrls.provider(value)
        val prepared = preparedSessions.filterNotNull().first { it.first == provider }
        return prepared.second.await()
    }

    fun primaryContentReady() { primaryPrepared = true; publishContentPrepared() }

    fun homeContentReady() { homePrepared = true; publishContentPrepared() }

    private fun publishContentPrepared() {
        if (primaryPrepared && homePrepared) {
            contentReady.complete(Unit)
            mutableContentPrepared.value = true
        }
    }

    suspend fun awaitPreparedContent() { contentReady.await(); awaitVisibleImages() }

    fun showUpdateCount(count: Int) { mutableUpdateCount.value = count }

    override fun imageStarted(): () -> Unit {
        if (startupComplete) return {}
        pendingImages.update { it + 1 }
        return { pendingImages.update { (it - 1).coerceAtLeast(0) } }
    }

    suspend fun awaitVisibleImages() {
        withTimeoutOrNull(1_200L) { pendingImages.first { it == 0 } }
    }

    fun finishStartup() { startupComplete = true }

    private suspend fun optional(name: String, load: suspend () -> Any?) {
        try {
            val completed = withTimeoutOrNull(4_000L) { load(); true } ?: false
            if (!completed) mutablePreparationErrors.update { it + (name to "Подготовка не завершилась вовремя") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // The screen will retry normally; unsuccessful results are never cached.
            mutablePreparationErrors.update { it + (name to networkErrorMessage(failure)) }
        }
    }
}
