package com.bobyl.tv

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobyl.tv.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class FilmActionKey(val session: AccountSession, val film: String) {
    companion object {
        fun of(session: AccountSession, reference: String): FilmActionKey {
            val id = reference.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?: historyFilmKey(reference).ifBlank { reference }
            return FilmActionKey(session, id)
        }
    }
}
internal data class FilmActionStatus(val id: String, val key: FilmActionKey, val pending: Boolean, val error: String = "")
internal class FilmActionReceipt<T>(val id: String, val accepted: Boolean, internal val completion: CompletableDeferred<Result<T>>) {
    suspend fun await(): T = completion.await().getOrThrow()
}
internal interface FilmActionFeedback { val message: String }

/** Selection rows have a global film-only PK, independently of the remote command's session key. */
internal class PlaybackSelectionBarrier {
    private val pending = mutableMapOf<String, Pair<String, FilmActionReceipt<Unit>>>()
    @Synchronized fun track(filmId: String, receipt: FilmActionReceipt<Unit>) {
        pending.entries.removeAll { it.value.second.completion.isCompleted }
        if (receipt.accepted) pending[receipt.id] = filmId to receipt
    }
    suspend fun await(filmId: String) {
        val prefix = synchronized(this) { pending.values.filter { it.first == filmId }.map { it.second.completion } }
        prefix.forEach { it.await() }
    }
}

/** One bounded FIFO owns accepted work; callers only own their cancellable receipt wait. */
internal class FilmActionQueue(
    scope: CoroutineScope,
    private val sessions: AccountSessionGuard,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val capacity: Int = 32,
) {
    private interface Work { val id: String; val key: FilmActionKey; suspend fun run(); fun abandon() }
    private val monitor = Any()
    private val pending = linkedMapOf<String, Pair<FilmActionKey, CompletableDeferred<*>>>()
    private val recentProgress = LinkedHashMap<String, FilmActionReceipt<*>>()
    private val mutableStatus = MutableStateFlow<List<FilmActionStatus>>(emptyList())
    val status = mutableStatus.asStateFlow()
    private val queue = Channel<Work>(capacity, onUndeliveredElement = { work ->
        // Cancellation may discard an element already handed to a parked receiver.
        runCatching { work.abandon() }
    })
    private var closed = false

    init {
        val worker = scope.launch(dispatcher) {
            try { for (work in queue) work.run() }
            finally { closeAndDrain() }
        }
        worker.invokeOnCompletion { closeAndDrain() } // Also covers cancellation before the worker starts.
    }

    private fun closeAndDrain() {
        synchronized(monitor) { closed = true; queue.close() }
        while (true) (queue.tryReceive().getOrNull() ?: break).abandon()
    }

    fun <T> submit(key: FilmActionKey, id: String = UUID.randomUUID().toString(),
        localFirst: Boolean = false, deduplicate: Boolean = false,
        execute: suspend (AccountSessionGuard.Permit) -> T): FilmActionReceipt<T> = synchronized(monitor) {
        @Suppress("UNCHECKED_CAST")
        if (deduplicate) recentProgress[id]?.let { return@synchronized it as FilmActionReceipt<T> }
        val result = CompletableDeferred<Result<T>>()
        fun reject(message: String): FilmActionReceipt<T> {
            val retained = mutableStatus.value.filterNot { it.id == id }
            mutableStatus.value = retained.filter { it.pending } +
                (retained.filterNot { it.pending } + FilmActionStatus(id, key, false, message)).takeLast(16)
            result.complete(Result.failure(IllegalStateException(message)))
            return FilmActionReceipt(id, false, result)
        }
        if (closed || pending.size >= capacity) return@synchronized reject("Очередь сохранения занята. Повторите действие через несколько секунд.")
        val permit = sessions.register(key.session)
        if (!localFirst && !permit.accepted) {
            sessions.finish(permit)
            return@synchronized reject(checkNotNull(permit.rejection))
        }
        val receipt = FilmActionReceipt(id, true, result)
        pending[id] = key to result
        mutableStatus.value = mutableStatus.value.filterNot { it.id == id } + FilmActionStatus(id, key, true)
        val commandKey = key
        val work = object : Work {
            override val id = receipt.id
            override val key = commandKey
            private val finished = AtomicBoolean()
            private fun complete(outcome: Result<T>) {
                if (!finished.compareAndSet(false, true)) return
                synchronized(monitor) {
                    pending.remove(id)
                    val message = outcome.exceptionOrNull()?.let {
                        if (it is Exception) networkErrorMessage(it) else it.message ?: "Не удалось сохранить изменение"
                    }
                        ?: (outcome.getOrNull() as? FilmActionFeedback)?.message.orEmpty()
                    val retained = mutableStatus.value.filterNot { it.id == id }
                    mutableStatus.value = retained.filter { it.pending } +
                        (retained.filterNot { it.pending } + FilmActionStatus(id, key, false, message)).takeLast(16)
                }
                sessions.finish(permit)
                result.complete(outcome)
            }
            override suspend fun run() {
                try { complete(Result.success(execute(permit))) }
                catch (failure: Throwable) {
                    complete(Result.failure(failure))
                    if (failure is Error) throw failure
                    currentCoroutineContext().ensureActive()
                }
            }
            override fun abandon() { complete(Result.failure(CancellationException("Сохранение остановлено при закрытии приложения"))) }
        }
        if (!queue.trySend(work).isSuccess) work.abandon()
        if (deduplicate) {
            recentProgress[id] = receipt
            while (recentProgress.size > 64) recentProgress.remove(recentProgress.keys.first())
        }
        receipt
    }

    suspend fun awaitSettled(key: FilmActionKey) {
        val prefix = synchronized(monitor) { pending.values.filter { it.first == key }.map { it.second } }
        prefix.forEach { it.await() }
    }
    fun latestError(key: FilmActionKey): String = status.value.lastOrNull { it.key == key && !it.pending }?.error.orEmpty()
}

internal data class WatchedSave(val local: WatchRecord?, val rows: List<ContinueItem>, override val message: String = "") : FilmActionFeedback
internal data class PlaybackProgressSnapshot(val id: String, val key: FilmActionKey, val record: WatchRecord,
    val complete: Boolean, val movie: Boolean, val position: Long)
internal data class PlaybackProgressSaved(override val message: String = "") : FilmActionFeedback

internal val LocalFilmActions = staticCompositionLocalOf<FilmActionsViewModel> { error("Film actions owner is missing") }

internal class FilmActionsViewModel(application: Application) : AndroidViewModel(application) {
    private val sessions = AccountSessionGuard.shared
    private val queue = FilmActionQueue(viewModelScope, sessions)
    private val selectionBarrier = PlaybackSelectionBarrier()
    suspend fun awaitPlaybackSelection(filmId: String) = selectionBarrier.await(filmId)
    val status = queue.status
    suspend fun awaitSettled(key: FilmActionKey) = queue.awaitSettled(key)
    fun latestError(key: FilmActionKey) = queue.latestError(key)

    fun release(resource: AutoCloseable?): Job? = resource?.let { releaseFilmResource(viewModelScope, it) }

    fun toggle(key: FilmActionKey, filmId: String, url: String, category: BookmarkCategory) = queue.submit(key) { permit ->
        sessions.server(permit) {
            LibraryRepository().toggleAccepted(key.session, filmId, category.id)
            CatalogRepository().details(url).bookmarks.also { updated ->
                check(updated.firstOrNull { it.id == category.id }?.checked == !category.checked) {
                    "Сервер не подтвердил изменение избранного. Попробуйте ещё раз."
                }
            }
        }
    }

    fun rate(key: FilmActionKey, filmId: String, value: Int, expectedOwner: PersonalRatingOwner) = queue.submit(key) { permit ->
        sessions.server(permit) {
            PersonalRatings(getApplication<Application>()).save(expectedOwner, filmId, value, currentOwner = {
                val (_, cookies) = sessions.requestCookies(key.session.provider) {
                    android.webkit.CookieManager.getInstance().getCookie(key.session.provider).orEmpty()
                }
                PersonalRatingOwner.fromCookies(key.session.provider, cookies)
            }, submit = { LibraryRepository().rate(key.session.provider, filmId, value) })
        }
    }

    fun watched(key: FilmActionKey, film: CatalogFilm, watched: Boolean, rows: List<ContinueItem>): FilmActionReceipt<WatchedSave> {
        val application = getApplication<Application>()
        val snapshot = rows.toList()
        return queue.submit(key) { permit ->
            sessions.server(permit) {
                val local = WatchHistory(application, key.session.provider).setWatched(film, watched)
                    .firstOrNull { historyFilmKey(it.url) == key.film }
                val updated = snapshot.toMutableList()
                var message = ""
                try {
                    for ((index, row) in snapshot.withIndex()) updated[index] = ExtrasRepository().setHistoryWatched(key.session.provider, row, watched)
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    message = "Не удалось обновить историю аккаунта: ${networkErrorMessage(failure)}"
                } finally {
                    ServerHistoryChanges.filmChanged(key.session, film.url, listOfNotNull(local), updated)
                }
                WatchedSave(local, updated, message)
            }
        }
    }

    fun removeBookmarks(key: FilmActionKey, url: String) = queue.submit(key) { permit ->
        sessions.server(permit) { LibraryRepository().removeFromAllAccepted(key.session, url) }
    }

    fun saveProgress(snapshot: PlaybackProgressSnapshot): FilmActionReceipt<PlaybackProgressSaved> {
        val application = getApplication<Application>()
        return queue.submit(snapshot.key, snapshot.id, localFirst = true, deduplicate = true) { permit ->
            persistPlaybackProgress(snapshot,
                local = { record, remove ->
                    val history = WatchHistory(application, snapshot.key.session.provider)
                    if (remove) history.remove(record.filmId) else history.put(record)
                },
                server = { sessions.server(permit) {
                    val record = snapshot.record
                    ExtrasRepository().saveProgress(snapshot.key.session.provider, record.filmId, record.voice,
                        record.season, record.episode, snapshot.position / 1000L)
                } },
            )
        }
    }

    /** Accepted only after a successful external-player launch; no server effect is involved. */
    fun savePlaybackSelection(key: FilmActionKey, filmId: String, snapshot: PlaybackSelection): FilmActionReceipt<Unit> {
        val application = getApplication<Application>()
        return queue.submit(key, localFirst = true) { PlaybackSelections(application).save(filmId, snapshot) }
            .also { selectionBarrier.track(filmId, it) }
    }
}

/** A finalizer must start even if Activity teardown cancelled its parent before dispatch. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun releaseFilmResource(scope: CoroutineScope, resource: AutoCloseable,
    dispatcher: CoroutineDispatcher = Dispatchers.IO): Job = scope.launch(dispatcher, start = CoroutineStart.ATOMIC) {
    runCatching { resource.close() }
}

/** Receives only the callback's immutable snapshot; no Compose state is read by the worker. */
internal suspend fun persistPlaybackProgress(snapshot: PlaybackProgressSnapshot,
    local: (WatchRecord, Boolean) -> Unit, server: suspend () -> Unit): PlaybackProgressSaved {
    val record = snapshot.record
    local(record, snapshot.complete && !snapshot.movie)
    ServerHistoryChanges.filmChanged(snapshot.key.session, record.url,
        records = if (snapshot.complete && !snapshot.movie) emptyList() else listOf(record))
    if (snapshot.position >= 0) try { server() }
    catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        return PlaybackProgressSaved("Не удалось обновить историю аккаунта: ${networkErrorMessage(failure)}")
    } finally {
        // A consumer may have read updates after the immediate local patch but before this attempt finished.
        ServerHistoryChanges.filmChanged(snapshot.key.session, record.url,
            records = if (snapshot.complete && !snapshot.movie) emptyList() else listOf(record))
    }
    return PlaybackProgressSaved()
}
