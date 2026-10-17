package com.bobyl.tv.data

import android.os.SystemClock
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import java.security.MessageDigest

/** Bounded process-local results. Account data never reaches the disk cache. */
internal object PreparedDataCache {
    private const val MaximumEntries = 64
    private const val MaximumWeight = 4 * 1024 * 1024L
    private data class Entry(val value: Any, val created: Long, val serial: Long, val weight: Long)
    private class RequestLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private val requests = mutableMapOf<String, RequestLock>()
    private var generation = 0L
    private var serial = 0L
    private var weight = 0L

    @Synchronized fun clear() {
        entries.clear()
        weight = 0L
        generation++
    }

    suspend fun <T : Any> load(key: String, durationMillis: Long, force: Boolean, loader: suspend () -> T): T {
        val requestGeneration: Long
        val requestSerial: Long
        val lock: RequestLock
        synchronized(this) {
            requestGeneration = generation
            requestSerial = serial
            lock = requests.getOrPut(key) { RequestLock() }.also { it.users++ }
        }
        try {
            return lock.mutex.withLock {
                val cached = synchronized(this) {
                    entries[key]?.takeIf {
                        generation == requestGeneration &&
                            ((!force && SystemClock.elapsedRealtime() - it.created < durationMillis) || it.serial > requestSerial)
                    }
                }
                @Suppress("UNCHECKED_CAST")
                if (cached != null) return@withLock cached.value as T
                val result = loader()
                val resultWeight = when (result) {
                    is PreparedPage -> result.html.length.toLong()
                    is Collection<*> -> result.size * 1_024L
                    is BrowseFilters -> (result.categories.sumOf { it.genres.size + 1 } + result.years.size) * 1_024L
                    else -> 1_024L
                }
                synchronized(this) {
                    if (generation == requestGeneration && resultWeight <= MaximumWeight && (result !is PreparedPage || result.cacheable)) {
                        entries.remove(key)?.let { weight -= it.weight }
                        entries[key] = Entry(result, SystemClock.elapsedRealtime(), ++serial, resultWeight)
                        weight += resultWeight
                        while (entries.size > MaximumEntries || weight > MaximumWeight) {
                            entries.remove(entries.keys.first())?.let { weight -= it.weight }
                        }
                    }
                }
                result
            }
        } finally {
            synchronized(this) {
                lock.users--
                if (lock.users == 0) requests.remove(key)
            }
        }
    }
}

internal suspend fun <T : Any> PageTransport.prepared(
    url: String, operation: String, durationMillis: Long = 30_000L,
    force: Boolean = false, loader: suspend () -> T,
): T {
    // Keep injected transports isolated, including existing repository fixtures.
    if (this !is HtmlTransport) return loader()
    val identity = withContext(Dispatchers.IO) {
        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(cookies.toByteArray())
            .joinToString("") { "%02x".format(it) }
        "$operation|$url|$digest|${PageCache.generation()}"
    }
    return PreparedDataCache.load(identity, minOf(durationMillis, PageCache.durationMillis()), force, loader)
}

private data class PreparedPage(val html: String, val url: String, val cacheable: Boolean)

/** Share a short-lived root response without sharing a mutable Jsoup document. */
internal suspend fun PageTransport.preparedRoot(url: String, force: Boolean = false): Document {
    return preparedDocument(url, "root", 30_000L, force) { get(url) }
}

internal suspend fun PageTransport.preparedDocument(
    url: String, operation: String, durationMillis: Long, force: Boolean = false,
    loader: suspend () -> Document,
): Document {
    val page = prepared(url, operation, durationMillis, force) {
        val document = loader()
        withContext(Dispatchers.Default) {
            val valid = document.select(".b-content__inline_items, .b-content__search_wrapper, .b-content__search_results, .b-person__career").isNotEmpty()
            val html = document.outerHtml()
            PreparedPage(html, document.baseUri(), valid && html.length <= 4 * 1024 * 1024)
        }
    }
    return withContext(Dispatchers.Default) { ResponseDocument.parse(page.html, page.url) }
}
