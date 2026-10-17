package com.bobyl.tv.data

import android.os.SystemClock
import org.jsoup.nodes.Document

/** Process-local, bounded cache. Never persists account pages or cookies to disk. */
internal object PageCache {
    private data class Entry(val html: String, val baseUrl: String, val created: Long)
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private const val MAX_CHARS = 4 * 1024 * 1024
    private var chars = 0
    private var durationMs = 60L * 60 * 1000
    private var generation = 0L

    @Synchronized fun configure(minutes: Int) {
        val next = minutes.coerceIn(0, 1440) * 60_000L
        if (next != durationMs) { durationMs = next; clear() }
    }

    @Synchronized fun clear() { entries.clear(); chars = 0; generation++; PreparedDataCache.clear() }
    @Synchronized fun generation(): Long = generation
    @Synchronized internal fun durationMillis(): Long = durationMs

    @Synchronized fun get(key: String): Document? {
        val entry = entries[key] ?: return null
        if (durationMs == 0L || SystemClock.elapsedRealtime() - entry.created >= durationMs) {
            entries.remove(key); chars -= entry.html.length; return null
        }
        // Callers get their own mutable DOM, never a shared Document.
        return ResponseDocument.parse(entry.html, entry.baseUrl)
    }

    @Synchronized fun put(key: String, document: Document, expectedGeneration: Long) {
        if (durationMs == 0L || generation != expectedGeneration) return
        val html = document.outerHtml()
        if (html.length > MAX_CHARS) return
        entries.remove(key)?.let { chars -= it.html.length }
        entries[key] = Entry(html, document.baseUri(), SystemClock.elapsedRealtime())
        chars += html.length
        while (chars > MAX_CHARS || entries.size > 64) {
            val iterator = entries.entries.iterator()
            chars -= iterator.next().value.html.length; iterator.remove()
        }
    }
}
