package com.bobyl.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class AccountSession(val provider: String, val token: String)
internal data class AccountCookieRequest(val session: AccountSession, val epoch: Long)
internal class AccountSessionUnavailable(message: String) : IllegalStateException(message)

/** Coordinates accepted writes with native auth and the website's ownership of cookies. */
internal class AccountSessionGuard(private val io: CoroutineDispatcher = Dispatchers.IO) {
    companion object {
        val shared = AccountSessionGuard()
        fun credentials(header: String): List<String> {
            val values = header.split(';').mapNotNull {
                val parts = it.trim().split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            return listOf("dle_user_id", "dle_password").map { values[it].orEmpty() }
        }
    }
    private class Entry {
        val metadata = Any()
        val cookies = Any()
        val transaction = Mutex()
        val transition = Mutex()
        @Volatile var token = UUID.randomUUID().toString()
        var cookieEpoch = 0L
        var changing = false
        @Volatile var website = false
        val pending = mutableSetOf<Permit>()
    }
    internal class Permit internal constructor(val session: AccountSession, val rejection: String?) {
        internal val done = CompletableDeferred<Unit>()
        val accepted: Boolean get() = rejection == null
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    private fun entry(provider: String): Entry {
        val origin = CatalogUrls.provider(provider)
        return entries[origin] ?: Entry().let { entries.putIfAbsent(origin, it) ?: it }
    }

    /** No cookies, disk or coroutine mutex are read from the input callback. */
    fun current(provider: String): AccountSession {
        val origin = CatalogUrls.provider(provider)
        return AccountSession(origin, entry(origin).token)
    }

    fun requestCookies(url: String, read: () -> String): Pair<AccountCookieRequest, String> {
        val uri = java.net.URI(url)
        val origin = CatalogUrls.provider("${uri.scheme}://${uri.rawAuthority}")
        val entry = entry(origin)
        return synchronized(entry.cookies) { AccountCookieRequest(AccountSession(origin, entry.token), entry.cookieEpoch) to read() }
    }

    fun register(session: AccountSession): Permit {
        val entry = entry(session.provider)
        return synchronized(entry.metadata) {
            val reason = when {
                session.token != entry.token -> "Учётная запись изменилась. Откройте карточку заново."
                entry.website -> "Дождитесь закрытия страницы учётной записи и повторите действие."
                entry.changing -> "Учётная запись меняется. Повторите действие после завершения входа."
                else -> null
            }
            Permit(session, reason).also { if (it.accepted) entry.pending.add(it) }
        }
    }

    fun finish(permit: Permit) {
        val entry = entry(permit.session.provider)
        synchronized(entry.metadata) { entry.pending.remove(permit) }
        permit.done.complete(Unit)
    }

    suspend fun <T> server(permit: Permit, work: suspend () -> T): T {
        val entry = entry(permit.session.provider)
        return entry.transaction.withLock {
            synchronized(entry.metadata) {
                val reason = permit.rejection ?: when {
                    entry.token != permit.session.token -> "Учётная запись изменилась. Серверное действие не выполнено."
                    entry.website -> "Страница учётной записи ещё открыта. Серверное действие не выполнено."
                    else -> null
                }
                if (reason != null) throw AccountSessionUnavailable(reason)
            }
            work()
        }
    }

    /** A card query is cancellable and never becomes accepted work or a retained permit. */
    suspend fun <T> read(session: AccountSession, work: suspend () -> T): T {
        val entry = entry(session.provider)
        return entry.transaction.withLock {
            synchronized(entry.metadata) {
                if (session.token != entry.token || entry.website || entry.changing) {
                    throw AccountSessionUnavailable("Учётная запись меняется. Откройте карточку фильма заново.")
                }
            }
            currentCoroutineContext().ensureActive()
            work()
        }
    }

    /** A stale request may return data, but cannot resurrect the previous session's cookies. */
    fun commitResponse(request: AccountCookieRequest, commit: () -> Unit): Boolean {
        val session = request.session
        val entry = entry(session.provider)
        return synchronized(entry.cookies) {
            if (entry.token != session.token || entry.cookieEpoch != request.epoch || entry.website) false
            else { commit(); true }
        }
    }

    private fun beginChange(entry: Entry): List<Permit> = synchronized(entry.metadata) {
        check(!entry.website) { "Сначала закройте страницу учётной записи" }
        entry.changing = true
        entry.pending.toList()
    }
    private fun endChange(entry: Entry) = synchronized(entry.metadata) { entry.changing = false }

    suspend fun <T> change(provider: String, commit: () -> T): T = withContext(io) {
        val entry = entry(provider)
        entry.transition.withLock {
            val prefix = beginChange(entry)
            try {
                prefix.forEach { it.done.await() }
                entry.transaction.withLock {
                    synchronized(entry.cookies) {
                        entry.token = UUID.randomUUID().toString()
                        entry.cookieEpoch++
                        commit()
                    }
                }
            } finally { endChange(entry) }
        }
    }

    /** Re-applying the same saved credentials is not an account switch. */
    suspend fun <T> restore(provider: String, unchanged: () -> Boolean, commit: () -> T): T =
        restore(provider, prepare = { Unit }, unchanged = { unchanged() }, commit = { commit() })

    /** Read saved credentials only after earlier login/logout transitions have finished. */
    suspend fun <S, T> restore(provider: String, prepare: suspend () -> S,
        unchanged: (S) -> Boolean, commit: (S) -> T): T = withContext(io) {
        val entry = entry(provider)
        entry.transition.withLock {
            check(!entry.website) { "Сначала закройте страницу учётной записи" }
            val snapshot = prepare()
            val operationContext = currentCoroutineContext()
            operationContext.ensureActive()
            // Native transitions are serialized; this check never competes with another restore/login.
            val same = entry.transaction.withLock { synchronized(entry.cookies) { unchanged(snapshot) } }
            if (same) entry.transaction.withLock {
                operationContext.ensureActive()
                synchronized(entry.cookies) {
                    operationContext.ensureActive()
                    entry.cookieEpoch++
                    commit(snapshot)
                }
            }
            else {
                val prefix = beginChange(entry)
                try {
                    prefix.forEach { it.done.await() }
                    entry.transaction.withLock {
                        operationContext.ensureActive()
                        synchronized(entry.cookies) {
                            operationContext.ensureActive()
                            entry.token = UUID.randomUUID().toString()
                            entry.cookieEpoch++
                            commit(snapshot)
                        }
                    }
                } finally { endChange(entry) }
            }
        }
    }

    suspend fun openWebsite(provider: String, credentials: () -> List<String>): AutoCloseable {
        var owned: AutoCloseable? = null
        try { return withContext(io) {
        val entry = entry(provider)
        entry.transition.withLock {
            val prefix = beginChange(entry)
            try {
                prefix.forEach { it.done.await() }
                var originalCredentials: List<String> = emptyList()
                entry.transaction.withLock {
                    synchronized(entry.cookies) {
                        originalCredentials = credentials()
                        synchronized(entry.metadata) {
                            check(!entry.website) { "Страница учётной записи уже открыта" }
                            entry.website = true
                            entry.cookieEpoch++
                        }
                    }
                }
                val closed = AtomicBoolean()
                AutoCloseable {
                    if (closed.compareAndSet(false, true)) synchronized(entry.cookies) {
                        var changed = true
                        try { changed = credentials() != originalCredentials }
                        finally { synchronized(entry.metadata) {
                                entry.website = false
                                entry.cookieEpoch++
                                if (changed) entry.token = UUID.randomUUID().toString()
                            }
                        }
                    }
                }.also { owned = it }
            } finally { endChange(entry) }
        }
        } } catch (failure: Throwable) {
            withContext(NonCancellable) {
                withContext(io) {
                    try { owned?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                }
            }
            throw failure
        }
    }
}
