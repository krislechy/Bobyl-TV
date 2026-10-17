package com.bobyl.tv.data

import android.os.Build
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.Connection
import org.jsoup.nodes.Document
import java.io.IOException

interface PageTransport {
    suspend fun get(url: String, details: Boolean = false): Document
    suspend fun cachedGet(url: String): Document = get(url)
    suspend fun post(url: String, form: Map<String, String>): Document
        = error("POST is not implemented by this transport")
}

object ResponseDocument {
    fun parse(body: String, url: String): Document = if (body.trimStart().startsWith("{") || body.trimStart().startsWith("["))
        Document.createShell(url).apply { body().text(body) } else Jsoup.parse(body, url)
}

/** Uses Android's URL-scoped cookie store, also usable by the later login WebView. */
class HtmlTransport : PageTransport {
    override suspend fun cachedGet(url: String): Document = preparedDocument(url, "html", PageCache.durationMillis()) {
        readCachedPage(url)
    }

    private suspend fun readCachedPage(url: String): Document = runInterruptible(Dispatchers.IO) {
        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(cookies.toByteArray()).joinToString("") { "%02x".format(it) }
        val key = "$url|$digest"
        PageCache.get(key)?.let { return@runInterruptible it }
        val generation = PageCache.generation()
        val request = connection(url)
        if (cookies.isNotBlank()) request.header("Cookie", cookies)
        val response = request.execute()
        saveResponseCookies(response)
        val doc = ResponseDocument.parse(response.body(), response.url().toString())
        // Do not cache challenge pages, unexpected responses or redirects to login.
        if (response.statusCode() == 200 && response.url().toString() == url &&
            doc.select(".b-content__inline_items, .b-content__search_wrapper, .b-content__search_results, .b-person__career").isNotEmpty()) {
            PageCache.put(key, doc, generation)
        }
        doc
    }
    private fun connection(url: String): Connection = Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .header("X-Hdrezka-Android-App", "1")
        .header("X-Hdrezka-Android-App-Version", "2.2.5")
        .timeout(20_000).maxBodySize(8 * 1024 * 1024).ignoreContentType(true)

    suspend fun postResponse(url: String, form: Map<String, String>, login: Boolean = false): Connection.Response = runInterruptible(Dispatchers.IO) {
        val request = connection(url).data(form).method(Connection.Method.POST).followRedirects(!login)
        if (!login) {
            val session = CookieManager.getInstance().getCookie(url).orEmpty()
            val header = if (form["action"] in listOf("get_movie", "get_stream") || url.contains("add_comment") || url.contains("schedule_watched"))
                "$session; allowed_comments=1; _ym_isad=1; _ym_visorc=b; dle_newpm=0;" else session
            if (header.isNotBlank()) request.header("Cookie", header)
        }
        val response = request.execute()
        // Login cookies are committed by AuthRepository only after success=true.
        if (!login) saveResponseCookies(response)
        response
    }

    override suspend fun post(url: String, form: Map<String, String>): Document {
        val response = postResponse(url, form)
        return runInterruptible(Dispatchers.IO) { ResponseDocument.parse(response.body(), response.url().toString()) }
    }

    private fun saveResponseCookies(response: Connection.Response) {
        val cookies = CookieManager.getInstance()
        response.multiHeaders().filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
            .values.flatten().forEach { cookies.setCookie(response.url().toString(), it) }
        cookies.flush()
    }

    override suspend fun get(url: String, details: Boolean): Document = runInterruptible(Dispatchers.IO) {
        val cookies = CookieManager.getInstance()
        val connection = connection(url)
        val sessionCookies = cookies.getCookie(url).orEmpty()
        val cookieHeader = if (details) {
            "$sessionCookies; allowed_comments=1; _ym_isad=1; _ym_visorc=b; dle_newpm=0;"
        } else sessionCookies
        if (cookieHeader.isNotBlank()) connection.header("Cookie", cookieHeader)
        val response = connection.execute()
        saveResponseCookies(response)
        ResponseDocument.parse(response.body(), response.url().toString())
    }
}

class CatalogRepository(private val transport: PageTransport = HtmlTransport()) {
    suspend fun details(url: String): FilmDetails {
        val document = transport.get(url, details = true)
        return withContext(Dispatchers.Default) { DetailsParser.parse(document) }
    }

    suspend fun load(provider: String, section: CatalogSection, page: Int, query: String, kind: String = section.genre): List<CatalogFilm> {
        val url = CatalogUrls.page(provider, section, page, query, kind)
        return transport.prepared(url, "catalog:${section.name}:$kind", PageCache.durationMillis()) {
            loadPage(provider, section, page, query, kind)
        }
    }

    private suspend fun loadPage(provider: String, section: CatalogSection, page: Int, query: String, kind: String): List<CatalogFilm> {
        if (section == CatalogSection.Slider && query.isBlank()) {
            if (page != 1) return emptyList()
            val doc = transport.post(CatalogUrls.provider(provider) + "engine/ajax/get_newest_slider_content.php", mapOf("id" to kind))
            return withContext(Dispatchers.Default) { CatalogParser.parse(doc) }
        }
        val document = transport.cachedGet(CatalogUrls.page(provider, section, page, query, kind))
        return withContext(Dispatchers.Default) {
            val films = CatalogParser.parse(document)
            if (films.isEmpty() && document.select(".b-content__inline_items, .b-content__search_wrapper, .b-content__search_results").isEmpty()) {
                throw IOException("Сайт вернул страницу без каталога. Проверьте адрес зеркала или доступность сайта.")
            }
            films
        }
    }
}
