package com.bobyl.tv.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document

class CatalogTest {
    @Test fun posterBadgePreservesServerStatusWithoutGuessingCompletion() {
        for (status in listOf("В ожидании", "Сериал завершен", "2 сезон, 8 серия", "")) {
            val html = """<div class="b-content__inline_item" data-id="42">
                <div class="b-content__inline_item-cover"><a href="/series/test.html">
                    <img src="/poster.jpg"><span class="info">$status</span></a></div>
                <div class="b-content__inline_item-link"><a href="/series/test.html">Тест</a></div>
                <span class="info">Посторонний текст</span>
                </div>"""
            assertEquals(status, CatalogParser.parse(Jsoup.parse(html, "https://example.org/")).single().badge)
        }
    }
    @Test fun referencePaginationAndFilters() {
        assertEquals("https://hdrzk.org/page/2/?filter=last&genre=2", CatalogUrls.page("hdrzk.org", CatalogSection.Series, 2))
        assertEquals("https://hdrzk.org/new/", CatalogUrls.page("hdrzk.org", CatalogSection.New, 1))
        assertEquals("https://hdrzk.org/new/page/3/", CatalogUrls.page("hdrzk.org", CatalogSection.New, 3))
    }
    @Test fun searchEncodesQuery() {
        assertEquals("https://hdrzk.org/search/?do=search&subaction=search&q=A+%26+B&page=1", CatalogUrls.page("hdrzk.org", CatalogSection.Latest, 1, "A & B"))
    }
    @Test fun partialCardsDoNotCrashParser() {
        val html = """<div class="b-content__inline_item" data-id="42">
          <div class="b-content__inline_item-cover"><a href="/films/test.html"><img src="/poster.jpg"></a></div>
          <div class="b-content__inline_item-link"><a href="/films/test.html">Тест</a><div>2026</div></div>
        </div><div class="b-content__inline_item"></div>"""
        val films = CatalogParser.parse(Jsoup.parse(html, "https://hdrzk.org/"))
        assertEquals(1, films.size)
        assertEquals("42", films.single().id)
        assertEquals("https://hdrzk.org/poster.jpg", films.single().poster)
        assertEquals("2026", films.single().metadata)
        assertEquals("", films.single().kind)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonHttpsProvider() {
        CatalogUrls.provider("http://example.org")
    }
    @Test fun detailsUseReferenceSelectors() {
        val html = """<div class="b-post__title"><h1>Фильм</h1></div>
          <div class="b-post__origtitle">Film</div>
          <div class="b-sidecover"><a><img src="/cover.jpg"></a></div>
          <table class="b-post__info"><tr><td>Год:</td><td>2026</td></tr></table>
          <div class="b-post__description_text">Описание</div>"""
        val info = DetailsParser.parse(Jsoup.parse(html, "https://hdrzk.org/"))
        assertEquals("Фильм", info.title)
        assertEquals("https://hdrzk.org/cover.jpg", info.poster)
        assertEquals(listOf("Год: 2026"), info.metadata)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsLoginPageAsDetails() {
        DetailsParser.parse(Jsoup.parse("<h1>Войти</h1>"))
    }
    @Test fun detailRequestUsesReferenceCookieMode() = runBlocking {
        var detailMode = false
        val repository = CatalogRepository(object : PageTransport {
            override suspend fun get(url: String, details: Boolean): Document {
                detailMode = details
                return Jsoup.parse("""<div class="b-post__title"><h1>Тест</h1></div>""")
            }
        })
        repository.details("https://hdrzk.org/films/test.html")
        assertTrue(detailMode)
    }
    @Test fun denialHasActionableMessage() {
        val message = networkErrorMessage(org.jsoup.HttpStatusException("Denied", 403, "https://example.org/private?token=secret"))
        assertTrue(message.contains("403"))
        assertFalse(message.contains("secret"))
    }
}
