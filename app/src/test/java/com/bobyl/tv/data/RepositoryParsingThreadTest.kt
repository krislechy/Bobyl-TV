package com.bobyl.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RepositoryParsingThreadTest {
    private val origin = "https://example.org/"
    private val filmUrl = "${origin}films/42-example.html"
    private val filmHtml = """<div class="b-content__inline_item" data-id="42">
        <div class="b-content__inline_item-cover"><a href="$filmUrl"><img src="/poster.jpg"></a></div>
        <div class="b-content__inline_item-link"><a href="$filmUrl">Example</a><div>2026</div></div>
        </div>"""

    /** Observe actual DOM reads, not merely the transport's already-background network phase. */
    private class OffCallerDocument(html: String, url: String, private val caller: Thread) : Document(url) {
        init { appendChildren(Jsoup.parse(html, url).childNodesCopy()) }
        override fun select(cssQuery: String): Elements {
            assertNotSame("DOM parsing must not run on the caller thread", caller, Thread.currentThread())
            return super.select(cssQuery)
        }
        override fun body(): Element {
            assertNotSame("JSON/HTML extraction must not run on the caller thread", caller, Thread.currentThread())
            return super.body()
        }
    }

    private class FakeTransport(
        val read: suspend (String) -> Document,
        val submit: suspend (String, Map<String, String>) -> Document = { _, _ -> error("Unexpected POST") },
    ) : PageTransport {
        override suspend fun get(url: String, details: Boolean) = read(url)
        override suspend fun post(url: String, form: Map<String, String>) = submit(url, form)
    }

    private fun jsonDocument(json: String, url: String, caller: Thread): Document {
        val shell = Document.createShell(url).apply { body().text(json) }
        return OffCallerDocument(shell.outerHtml(), url, caller)
    }

    @Test fun browseAndLinkedCatalogParseOffCallerAndKeepPagination() = runBlocking {
        val caller = Thread.currentThread()
        val urls = mutableListOf<String>()
        val repository = BrowseRepository(FakeTransport({ url ->
            urls += url
            OffCallerDocument(filmHtml, url, caller)
        }))
        assertEquals("Example", repository.filmsAt("${origin}films/", 2).single().title)
        assertEquals(filmUrl, repository.films(BrowseCategory("Films", "${origin}films/", emptyList()), "", "2026", 3).single().url)
        assertEquals(listOf("${origin}films/page/2/", "${origin}films/best/2026/page/3/"), urls)
    }

    @Test fun quickSearchParsesBothResponsesOffCallerAndFillsMissingPoster() = runBlocking {
        val caller = Thread.currentThread()
        val repository = BrowseRepository(FakeTransport(
            read = { OffCallerDocument(filmHtml, it, caller) },
            submit = { url, fields ->
                assertEquals("${origin}engine/ajax/search.php", url)
                assertEquals(mapOf("q" to "Example"), fields)
                OffCallerDocument("""<li><a href="$filmUrl"><span class="enty">Example</span></a></li>""", origin, caller)
            },
        ))
        val film = repository.quickSearch(origin, " Example ").single()
        assertEquals("${origin}poster.jpg", film.poster)
        assertEquals("2026", film.metadata)
    }

    @Test fun optionalSearchFallbackKeepsSuggestionsOnIoFailureButPropagatesCancellation() = runBlocking {
        val caller = Thread.currentThread()
        suspend fun search(failure: Exception): List<CatalogFilm> = BrowseRepository(FakeTransport(
            read = { throw failure },
            submit = { _, _ -> OffCallerDocument("""<li><a href="$filmUrl"><span class="enty">Example</span></a></li>""", origin, caller) },
        )).quickSearch(origin, "Example")
        assertEquals("Example", search(IOException("offline")).single().title)
        val cancelled = CancellationException("cancelled")
        try { search(cancelled); fail("Cancellation must escape") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }

    @Test fun favoritesFilmsParseOffCallerAndKeepFilters() = runBlocking {
        val caller = Thread.currentThread()
        val repository = LibraryRepository(FakeTransport({ url ->
            assertEquals("${origin}favorites/list/page/2/?filter=year&genre=2", url)
            OffCallerDocument(filmHtml, url, caller)
        }))
        assertEquals("42", repository.films(BookmarkCategory("1", "List", "${origin}favorites/list/"), 2, "year", "2").single().id)
    }

    @Test fun filmographyParsesOffCallerAndPreservesCareerOrder() = runBlocking {
        val caller = Thread.currentThread()
        val html = """<table class="b-post__info"><tr><td>Born</td><td>1980</td></tr></table>
            <div class="b-person__career"><h2>Actor</h2>$filmHtml</div>
            <div class="b-person__career"><h2>Director</h2>$filmHtml</div>"""
        val result = ExtrasRepository(FakeTransport({ OffCallerDocument(html, it, caller) })).person("${origin}person/1/")
        assertEquals("Born 1980", result.description)
        assertEquals(listOf("Actor", "Director"), result.careers.keys.toList())
        assertEquals(filmUrl, result.careers.getValue("Actor").single().url)
    }

    @Test fun commentsDecodeAndParseOffCaller() = runBlocking {
        val caller = Thread.currentThread()
        val comments = """<div id="comment-id-7"><span class="name">Reader</span><span class="date">Today</span>
            <div class="text">Message</div><span class="b-comment__likes_count"><i>3</i></span></div>"""
        val json = JSONObject().put("success", true).put("comments", comments).toString()
        val result = CommentsRepository(FakeTransport({ url ->
            assertTrue(url.contains("news_id=42&cstart=2"))
            jsonDocument(json, url, caller)
        })).load(origin, "42", 2)
        assertEquals("7", result.single().id)
        assertEquals("Message", result.single().text)
    }

    @Test fun trailerResponseParsesOffCaller() = runBlocking {
        val caller = Thread.currentThread()
        val json = JSONObject().put("success", true).put("code", "<iframe src=\"https://video.example/trailer\"></iframe>").toString()
        val repository = ExtrasRepository(FakeTransport(read = { error("Unexpected GET") }, submit = { _, fields ->
            assertEquals(mapOf("id" to "42"), fields)
            jsonDocument(json, origin, caller)
        }))
        assertEquals("https://video.example/trailer", repository.trailer(origin, "42"))
    }

    @Test fun ratingResponseParsesOffCallerAndPreservesValues() = runBlocking {
        val caller = Thread.currentThread()
        val json = JSONObject().put("success", true).put("num", "8.2").put("votes", "100")
            .put("html", "<span>Ваша оценка: 7</span>").toString()
        val result = LibraryRepository(FakeTransport({ jsonDocument(json, it, caller) })).rate(origin, "42", 7)
        assertEquals(ServerRatingResult("8.2", "100", 7), result)
    }

    @Test fun ratingPreservesTransportCancellationAndIoErrors() = runBlocking {
        for (failure in listOf(CancellationException("cancel"), IOException("offline"))) {
            try {
                LibraryRepository(FakeTransport({ throw failure })).rate(origin, "42", 7)
                fail("Transport failure must escape")
            } catch (actual: Exception) { assertSame(failure, actual) }
        }
    }

    @Test fun malformedRatingResponseRetainsActionableFormatError() = runBlocking {
        val caller = Thread.currentThread()
        try {
            LibraryRepository(FakeTransport({ OffCallerDocument("not json", it, caller) })).rate(origin, "42", 7)
            fail("Malformed response must fail")
        } catch (failure: IllegalStateException) {
            assertEquals("Сайт вернул неожиданный ответ на оценку", failure.message)
        }
    }

    @Test fun playbackMovieSeasonsAndEpisodeReadTheResponseDomOffCaller() = runBlocking {
        val caller = Thread.currentThread()
        val voice = VoiceOption("7", "Studio")
        val movie = PlaybackInfo("42", true, listOf(voice))
        val series = movie.copy(isMovie = false)
        val actions = mutableListOf<String>()
        val repository = PlaybackRepository(FakeTransport(read = { error("Unexpected GET") }, submit = { url, fields ->
            val action = fields.getValue("action")
            actions += action
            val response = JSONObject().put("success", true)
            if (action == "get_episodes") response.put("episodes",
                """<ul class="b-simple_episodes__list" id="simple-episodes-list-2"><li class="b-simple_episode__item" data-episode_id="3"></li></ul>""")
            else response.put("url", "[720p]https://cdn.example/video.mp4").put("subtitle", false)
            jsonDocument(response.toString(), url, caller)
        }))
        assertEquals("720p", repository.movie(origin, movie, voice).streams.single().label)
        assertEquals(mapOf("2" to listOf("3")), repository.seasons(origin, series, voice))
        assertEquals("https://cdn.example/video.mp4", repository.episode(origin, series, voice, "2", "3").streams.single().url)
        assertEquals(listOf("get_movie", "get_episodes", "get_stream"), actions)
    }
}
