package com.bobyl.tv.data

import android.webkit.CookieManager
import com.bobyl.tv.data.database.CacheRecord
import com.bobyl.tv.data.database.PersistentCacheStore
import com.bobyl.tv.data.database.CacheQuarantineKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal data class PersistentCacheScope(val provider: String, val owner: String, val session: AccountSession)

/** Serialized disk access also fences clears against responses that began before a mutation. */
internal class PersistentCacheController(private val store: PersistentCacheStore,
    private val reportFailure: (Exception) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var dirty = false
    private var quarantineInspected = false

    suspend fun invalidate(strict: Boolean = true, clearMemory: () -> Unit = {}) = mutex.withLock {
        generation.incrementAndGet()
        dirty = true
        quarantineInspected = true
        clearMemory()
        var markerFailure: Exception? = null
        try {
            // Separate committed write: a failed/cancelled clear must survive process recreation.
            store.cachePut(CacheRecord(QuarantineKey, "", "", "quarantine", "", 0,
                clock(), Long.MAX_VALUE, 0), MaximumEntries, MaximumBytes)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { markerFailure = failure }
        try {
            store.cacheClear()
            dirty = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            markerFailure?.takeUnless { it === failure }?.let(failure::addSuppressed)
            if (strict) throw failure
            reportFailure(failure)
        }
    }

    suspend fun <T : Any> load(provider: String, owner: String, operation: String, url: String,
        durationMillis: Long, force: Boolean, valid: suspend () -> Boolean, loader: suspend () -> T): T {
        if (durationMillis <= 0 || !CachePayloadCodec.supported(operation)) return loader()
        mutex.withLock {
            if (!quarantineInspected) {
                if (tryStore { store.cacheGet(QuarantineKey) } != null) dirty = true
                quarantineInspected = true
            }
        }
        val epoch = generation.get()
        val key = digest("$operation|$url|$provider|$owner")
        if (!force && !dirty && valid()) {
            val record = mutex.withLock { if (generation.get() == epoch && !dirty) tryStore { store.cacheGet(key) } else null }
            if (record != null) {
                val now = clock()
                val metadataValid = record.provider == provider && record.owner == owner && record.operation == operation &&
                    record.codecVersion == CachePayloadCodec.Version && record.sizeBytes == record.payload.toByteArray(Charsets.UTF_8).size.toLong() &&
                    record.sizeBytes <= MaximumPayloadBytes && now >= record.createdAtMs && now < record.expiresAtMs &&
                    now - record.createdAtMs < durationMillis
                val decoded = if (metadataValid) withContext(Dispatchers.Default) { CachePayloadCodec.decode(operation, record.payload) } else null
                if (decoded != null && valid() && generation.get() == epoch && !dirty) {
                    @Suppress("UNCHECKED_CAST")
                    return decoded as T
                }
                mutex.withLock { if (generation.get() == epoch) tryStore { store.cacheRemove(key) } }
            }
        }
        val result = loader()
        if (generation.get() != epoch || dirty || !valid()) return result
        val payload = withContext(Dispatchers.Default) {
            // Unknown/unsafe DTOs remain usable online, but cannot enter persistent storage.
            runCatching { CachePayloadCodec.encode(operation, result) }.getOrNull()
        } ?: return result
        val size = payload.toByteArray(Charsets.UTF_8).size.toLong()
        if (size > MaximumPayloadBytes) return result
        val now = clock()
        val record = CacheRecord(key, provider, owner, operation, payload, CachePayloadCodec.Version,
            now, now + durationMillis.coerceAtMost(86_400_000L), size)
        mutex.withLock {
            if (generation.get() == epoch && !dirty) tryStore { store.cachePut(record, MaximumEntries, MaximumBytes) }
        }
        return result
    }

    private suspend fun <T> tryStore(block: suspend () -> T): T? = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { dirty = true; reportFailure(failure); null }

    companion object {
        const val MaximumEntries = 128
        const val MaximumBytes = 16L * 1024 * 1024
        const val MaximumPayloadBytes = 4L * 1024 * 1024
        internal const val QuarantineKey = CacheQuarantineKey
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Installed only after startup has opened and migrated the database. */
internal object PersistentCache {
    @Volatile private var controller: PersistentCacheController? = null
    private val mutableUserClearRevision = MutableStateFlow(0)
    val userClearRevision = mutableUserClearRevision.asStateFlow()
    internal fun resetForTests() { controller = null; mutableUserClearRevision.value = 0 }
    fun install(store: PersistentCacheStore) {
        controller = PersistentCacheController(store, reportFailure = {
            android.util.Log.w("BobylStorage", "Cache invalidation deferred: ${it.javaClass.simpleName}")
        })
    }

    suspend fun invalidate(strict: Boolean = false) = withContext(Dispatchers.IO) {
        val current = controller
        if (current == null) PageCache.clear() else current.invalidate(strict, PageCache::clear)
    }

    /** Explicit data-cache clearing is distinct from routine mutation invalidation. */
    suspend fun clearByUser() = withContext(Dispatchers.IO) {
        val current = checkNotNull(controller) { "Хранилище кэша ещё не готово" }
        current.invalidate(strict = true, clearMemory = PageCache::clear)
        mutableUserClearRevision.update { it + 1 }
    }

    suspend fun <T : Any> load(url: String, operation: String, durationMillis: Long,
        force: Boolean, loader: suspend () -> T): T {
        val current = controller ?: return loader()
        if (!CachePayloadCodec.supported(operation)) return loader()
        val scope = scope(url) ?: return loader()
        return withContext(Dispatchers.IO) {
            current.load(scope.provider, scope.owner, operation, url, durationMillis, force,
                valid = { isCurrent(scope) }, loader = loader)
        }
    }

    private suspend fun scope(url: String): PersistentCacheScope? = withContext(Dispatchers.IO) {
        val uri = URI(url)
        val provider = CatalogUrls.provider("${uri.scheme}://${uri.rawAuthority}")
        val session = AccountSessionGuard.shared.current(provider)
        try {
            AccountSessionGuard.shared.read(session) {
                val (_, cookies) = AccountSessionGuard.shared.requestCookies(url) { CookieManager.getInstance().getCookie(url).orEmpty() }
                val owner = PersonalRatingOwner.fromCookies(provider, cookies)
                PersistentCacheScope(provider, owner.namespace, session)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalStateException) { null }
    }

    private suspend fun isCurrent(scope: PersistentCacheScope): Boolean = try {
        AccountSessionGuard.shared.read(scope.session) {
            val (_, cookies) = AccountSessionGuard.shared.requestCookies(scope.provider) {
                CookieManager.getInstance().getCookie(scope.provider).orEmpty()
            }
            PersonalRatingOwner.fromCookies(scope.provider, cookies).namespace == scope.owner
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IllegalStateException) { false }
}
