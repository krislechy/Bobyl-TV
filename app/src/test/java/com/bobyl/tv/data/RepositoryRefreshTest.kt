package com.bobyl.tv.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

class RepositoryRefreshTest {
    private val origin = "https://refresh-test.example/"
    private val html = """<div class="b-content__inline_items"><div class="b-content__inline_item" data-id="42">
        <div class="b-content__inline_item-cover"><a href="/films/42-test.html"><img src="/poster.jpg"></a></div>
        <div class="b-content__inline_item-link"><a href="/films/42-test.html">Film</a><div>2026</div></div></div></div>"""
    private class Transport(private val html: String) : PageTransport {
        var fresh = 0; var cached = 0; var posts = 0
        var failPost = false
        override suspend fun get(url: String, details: Boolean): Document { fresh++; return Jsoup.parse(html, url) }
        override suspend fun cachedGet(url: String): Document { cached++; return Jsoup.parse(html, url) }
        override suspend fun post(url: String, form: Map<String, String>): Document {
            posts++
            if (failPost) throw IOException("uncertain write")
            return Jsoup.parse("""{"success":true}""", url)
        }
    }

    @Test fun explicitCatalogRefreshBypassesActualNestedHtmlCachePath() = runBlocking {
        val transport = Transport(html)
        val repository = CatalogRepository(transport)
        repository.load(origin, CatalogSection.Popular, 1, "")
        repository.load(origin, CatalogSection.Popular, 1, "", force = true)
        assertEquals(1, transport.cached)
        assertEquals(1, transport.fresh)
    }

    @Test fun categoryAndLinkedRefreshBypassActualNestedHtmlCachePath() = runBlocking {
        val transport = Transport(html)
        val repository = BrowseRepository(transport)
        val category = BrowseCategory("Films", origin + "films/", emptyList())
        repository.films(category, "", "", 1)
        repository.films(category, "", "", 1, force = true)
        repository.filmsAt(origin + "films/", 1)
        repository.filmsAt(origin + "films/", 1, force = true)
        assertEquals(2, transport.cached)
        assertEquals(2, transport.fresh)
    }

    @Test fun deferredCreateUsesCapturedOwnerAndRejectsAfterAccountSwitchBeforeHttp() = runBlocking {
        val provider = "https://deferred-create-test.example/"
        val guard = AccountSessionGuard.shared
        val captured = guard.current(provider)
        guard.change(provider) { }
        val transport = Transport(html)
        try { LibraryRepository(transport).create(captured, "List"); fail("old owner must be rejected") }
        catch (_: AccountSessionUnavailable) { }
        assertEquals(0, transport.posts)
    }

    @Test fun uncertainMembershipAttemptDirtiesOnlyItsCapturedSession() = runBlocking {
        val provider = "https://uncertain-favorite-test.example/"
        val owner = AccountSessionGuard.shared.current(provider)
        val before = LibraryRepository.revision(owner)
        val unrelated = AccountSessionGuard.shared.current("https://unrelated-favorite-test.example/")
        val otherBefore = LibraryRepository.revision(unrelated)
        val transport = Transport(html).apply { failPost = true }
        try { LibraryRepository(transport).toggle(provider, "42", "1"); fail("failure expected") }
        catch (_: IOException) { }
        assertEquals(1, transport.posts)
        assertEquals(before + 1, LibraryRepository.revision(owner))
        assertEquals(otherBefore, LibraryRepository.revision(unrelated))
    }
}
