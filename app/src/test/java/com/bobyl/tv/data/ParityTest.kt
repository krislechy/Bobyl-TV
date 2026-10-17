package com.bobyl.tv.data

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

class ParityTest {
    private class Fake(val reply: String = "{\"success\":true}") : PageTransport {
        var url = ""; var form = emptyMap<String, String>()
        override suspend fun get(url: String, details: Boolean): Document { this.url = url; return ResponseDocument.parse(reply, url) }
        override suspend fun post(url: String, form: Map<String, String>): Document { this.url = url; this.form = form; return ResponseDocument.parse(reply, url) }
    }
    @Test fun categoryPathsMatchReference() {
        val cat = BrowseCategory("Фильмы", "https://hdrzk.org/films/", emptyList())
        assertEquals("https://hdrzk.org/films/best/2024/page/2/", BrowseParser.categoryUrl(cat, "", "2024", 2))
        assertEquals("https://hdrzk.org/films/drama/page/1/", BrowseParser.categoryUrl(cat, "https://hdrzk.org/films/drama/", "", 1))
    }
    @Test fun categoryParsingResolvesRelativeLinks() {
        val data = BrowseParser.filters(Jsoup.parse("""<li class="b-topnav__item"><a class="b-topnav__item-link" href="/films/">Фильмы</a><select class="select-category"><option value="/films/drama/">Драма</option></select></li><select class="select-year"><option>за все время</option><option>2024</option></select>""", "https://hdrzk.org/"))
        assertEquals("https://hdrzk.org/films/drama/", data.categories.single().genres.single().url)
        assertEquals(listOf("2024"), data.years)
    }
    @Test fun newsTypeDoesNotChangeEndpoint() {
        assertEquals("https://hdrzk.org/new/page/2/?genre=4", CatalogUrls.page("hdrzk.org", CatalogSection.New, 2, kind = "4"))
    }
    @Test fun suggestionsUseOriginalPost() = runBlocking {
        val fake = Fake("<ul><li><a href='/films/12-test.html'><span class='enty'>Test</span></a></li></ul>")
        val result = BrowseRepository(fake).suggestions("hdrzk.org", " test ")
        assertEquals(mapOf("q" to "test"), fake.form)
        assertTrue(fake.url.endsWith("engine/ajax/search.php")); assertEquals("Test", result.single().title)
    }
    @Test fun historySkipsHeaderWithoutSkippingFirstMovie() {
        val doc = Jsoup.parse("""<div class="b-videosaves__list_item">Header</div><div class="b-videosaves__list_item" id="videosave-12"><div class="title"><a href="/films/42-a.html">A</a></div><div class="info">12:34</div></div>""", "https://hdrzk.org/")
        assertEquals("12", ExtrasParser.history(doc).single().id)
    }
    @Test fun historyProgressUsesRealSeconds() = runBlocking {
        val fake = Fake(); ExtrasRepository(fake).saveProgress("hdrzk.org", "42", "5", "2", "3", 123)
        assertEquals(mapOf("post_id" to "42", "translator_id" to "5", "season" to "2", "episode" to "3", "current_time" to "123"), fake.form)
    }
    @Test fun historyRemoveUsesOriginalEndpoint() = runBlocking {
        val fake = Fake(); ExtrasRepository(fake).removeHistory("hdrzk.org", "12")
        assertTrue(fake.url.endsWith("cdn_saves_remove.php")); assertEquals(mapOf("id" to "12"), fake.form)
    }
    @Test fun scheduleKeepsWatchState() {
        val doc = Jsoup.parse("""<div class="b-post__schedule_block"><div class="b-post__schedule_list"><table><tr><td class="td-1">1 серия</td><td class="td-3"><i class="watched" data-id="88"></i></td><td class="td-4">2026</td></tr></table></div></div>""")
        val row = ExtrasParser.schedule(doc).single(); assertTrue(row.watched); assertEquals("88", row.id)
    }
    @Test fun watchMutationRequiresServerSuccess() = runBlocking {
        val fake = Fake("{\"success\":false,\"message\":\"login required\"}")
        try { ExtrasRepository(fake).watch("hdrzk.org", "88"); fail("Expected rejection") } catch (_: IllegalStateException) { }
    }
    @Test fun updatesPreserveTrackedAndDate() {
        val rows = ExtrasParser.updates(Jsoup.parse("""<div class="b-seriesupdate__block"><div class="b-seriesupdate__block_date">Сегодня развернуть</div><li class="b-seriesupdate__block_list_item tracked"><a class="b-seriesupdate__block_list_link" href="/series/a.html">A</a><span class="season">1</span><span class="cell">2 <i>Voice</i></span></li></div>""", "https://hdrzk.org/"))
        assertEquals("Сегодня", rows.single().date); assertTrue(rows.single().tracked)
    }
    @Test fun collectionLinksAreAbsolute() {
        val result = ExtrasParser.collection(Jsoup.parse("<div class='b-post__partcontent_item'><div class='title'><a href='/films/1-a.html'>A</a></div></div>", "https://hdrzk.org/"))
        assertEquals("https://hdrzk.org/films/1-a.html", result.single().url)
    }
    @Test fun trailerExtractsIframeWithoutExecutingCode() = runBlocking {
        val fake = Fake("""{"success":true,"code":"<iframe src='https://www.youtube.com/embed/abc?autoplay=1'></iframe>"}""")
        assertEquals("https://www.youtube.com/embed/abc?autoplay=1", ExtrasRepository(fake).trailer("hdrzk.org", "42"))
    }
    @Test fun commentsContract() = runBlocking {
        val fake = Fake("{\"comments\":\"\"}")
        assertTrue(CommentsRepository(fake).load("hdrzk.org", "42", 2).isEmpty())
        assertTrue(fake.url.contains("news_id=42&cstart=2&type=0&comment_id=0&skin=hdrezka"))
    }
    @Test fun postingCommentKeepsOriginalFields() = runBlocking {
        val fake = Fake(); CommentsRepository(fake).post("hdrzk.org", "42", "Text", "10")
        assertEquals(mapOf("comments" to "Text", "post_id" to "42", "parent" to "10", "type" to "0", "g_recaptcha_response" to "", "has_adb" to "1"), fake.form)
    }
    @Test fun parseCommentsDeletionAndLike() {
        val doc = Jsoup.parse("""<div id="comment-id-12"><span class="name">User</span><div class="text">Text</div><span class="show-likes-comment disabled"></span><div class="actions"><ul class="edit"><li><a onclick="sof.comments.deleteComment(12, 'hash', 0, 'ajax');">Удалить</a></li></ul></div></div>""")
        val c = CommentsParser.parse(doc).single(); assertTrue(c.liked); assertEquals("hash", c.deleteHash)
    }
    @Test fun favoritesPaginationAndFilters() = runBlocking {
        val fake = Fake(""); LibraryRepository(fake).films(BookmarkCategory("1", "A", "https://hdrzk.org/favorites/1/"), 3, "year", "2")
        assertEquals("https://hdrzk.org/favorites/1/page/3/?filter=year&genre=2", fake.url)
    }
    @Test fun createFavoritesUsesAddCat() = runBlocking {
        val fake = Fake(); LibraryRepository(fake).create("hdrzk.org", " List ")
        assertEquals(mapOf("name" to "List", "action" to "add_cat"), fake.form)
    }
    @Test fun favoritesRejectExplicitFailure() = runBlocking {
        try { LibraryRepository(Fake("{\"success\":false}")).toggle("hdrzk.org", "42", "1"); fail("Expected rejection") }
        catch (_: IllegalStateException) { }
    }
    @Test fun ratingUpdatesBothValues() = runBlocking {
        assertEquals("8.5" to "101", LibraryRepository(Fake("{\"success\":true,\"num\":\"8.5\",\"votes\":\"101\"}")).rate("hdrzk.org", "42", 8))
    }
    @Test fun registrationEscapesNestedForm() {
        val data = AuthContract.registrationFields("a+b@example.org", "n&x", "p=+ &").getValue("data")
        assertTrue(data.contains("email=a%2Bb%40example.org")); assertTrue(data.contains("name=n%26x")); assertTrue(data.contains("password1=p%3D%2B+%26"))
    }
    @Test fun sessionStateDistinguishesChallengeFromLogout() {
        assertNull(AccountParser.parse(Jsoup.parse("<h1>Challenge</h1>")).authenticated)
        assertEquals(false, AccountParser.parse(Jsoup.parse("<div class='b-tophead__login'>Login</div>")).authenticated)
        assertEquals(true, AccountParser.parse(Jsoup.parse("<a href='/?action=logout'>Logout</a>")).authenticated)
    }
}
