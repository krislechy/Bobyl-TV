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
    private var provider = ""
    private var session: Deferred<String?>? = null
    private var contentReady = CompletableDeferred<Unit>()
    private var background: Job? = null
    private var badge: Job? = null
    private var historyRevision = 0
    private var startupComplete = false
    private val pendingImages = MutableStateFlow(0)
    private val mutableUpdateCount = MutableStateFlow(0)
    val updateCount = mutableUpdateCount.asStateFlow()
    private val mutablePreparationErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val preparationErrors = mutablePreparationErrors.asStateFlow()

    suspend fun prepareSession(value: String, startMenu: Int): String? {
        val nextIdentity = "${CatalogUrls.provider(value)}|${PageCache.generation()}"
        if (identity != nextIdentity) {
            identity = nextIdentity
            provider = value
            background?.cancel()
            badge?.cancel()
            session?.cancel()
            contentReady = CompletableDeferred()
            mutableUpdateCount.value = 0
            mutablePreparationErrors.value = emptyMap()
            val restored = viewModelScope.async { auth.restore(value) }
            session = restored
            val ready = contentReady
            viewModelScope.launch {
                optional("Локальная история") {
                    withContext(Dispatchers.IO) { WatchHistory(getApplication(), value).all() }
                }
                optional("Плееры") { withContext(Dispatchers.IO) { installedPlayers(getApplication()) } }
            }
            background = viewModelScope.launch {
                val account = try { restored.await() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    mutablePreparationErrors.update { it + ("Сессия" to networkErrorMessage(failure)) }
                    null
                }
                ready.await()
                // One optional network operation at a time, after primary content.
                optional("Новые серии") {
                    mutableUpdateCount.value = ExtrasRepository().trackedUpdates(value).count { it.tracked }
                }
                if (PageCache.durationMillis() == 0L) return@launch
                optional("Категории") { BrowseRepository().filters(value) }
                if (account != null) {
                    optional("Избранное") { LibraryRepository().categories(value) }
                    optional("Серверная история") { ExtrasRepository().history(value) }
                }
                if (startMenu != 0) optional("Главная") {
                    CatalogRepository().load(value, CatalogSection.Popular, 1, "")
                }
                if (startMenu != 1) optional("Новинки") {
                    CatalogRepository().load(value, CatalogSection.Slider, 1, "")
                }
            }
        }
        return checkNotNull(session).await()
    }

    fun primaryContentReady() { contentReady.complete(Unit) }

    fun refreshUpdateCount(revision: Int) {
        if (revision == historyRevision) return
        historyRevision = revision
        val currentProvider = provider.takeIf { it.isNotBlank() } ?: return
        val currentSession = session ?: return
        val ready = contentReady
        badge?.cancel()
        badge = viewModelScope.launch {
            optional("Новые серии") {
                currentSession.await()
                ready.await()
                mutableUpdateCount.value = ExtrasRepository().trackedUpdates(currentProvider, force = true).count { it.tracked }
            }
        }
    }

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
