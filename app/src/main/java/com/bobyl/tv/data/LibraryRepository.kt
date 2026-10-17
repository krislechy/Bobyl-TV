package com.bobyl.tv.data

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ServerRatingResult(val average: String, val votes: String, val personal: Int?)

class LibraryRepository(private val transport: PageTransport = HtmlTransport()) {
    suspend fun categories(provider: String, force: Boolean = false): List<BookmarkCategory> {
        val url = CatalogUrls.provider(provider) + "favorites/"
        return transport.prepared(url, "favorites", 60_000L, force) {
            val document = transport.get(url)
            withContext(Dispatchers.Default) { parseCategories(document) }
        }
    }

    private fun parseCategories(document: Document): List<BookmarkCategory> = document
        .select("div.b-favorites_content__cats_list_item").mapNotNull { item ->
            val id = item.attr("data-cat_id")
            val link = item.selectFirst("a.b-favorites_content__cats_list_link")?.absUrl("href").orEmpty()
            if (id.isBlank() || link.isBlank()) null else BookmarkCategory(id, item.select("span.name").text(), link,
                item.select("span.num-holder b").text().toIntOrNull() ?: 0)
        }

    suspend fun films(category: BookmarkCategory, page: Int = 1, sort: String = "added", kind: String = "0"): List<CatalogFilm> {
        require(page > 0 && sort in listOf("added", "year", "popular") && kind in listOf("0", "1", "2", "3", "4"))
        return CatalogParser.parse(transport.get(category.link.trimEnd('/') + "/page/$page/?filter=$sort" + if (kind == "0") "" else "&genre=$kind"))
    }
    suspend fun create(provider: String, name: String) {
        require(name.isNotBlank()) { "Введите название списка" }
        requireSuccess(transport.post(CatalogUrls.provider(provider) + "ajax/favorites/", mapOf("name" to name.trim(), "action" to "add_cat")))
        PreparedDataCache.clear()
    }

    suspend fun toggle(provider: String, filmId: String, categoryId: String) {
        require(filmId.all(Char::isDigit) && categoryId.isNotBlank())
        val doc = transport.post(CatalogUrls.provider(provider) + "ajax/favorites/", mapOf("post_id" to filmId, "cat_id" to categoryId, "action" to "add_post"))
        if (doc.body().text().trim().startsWith("{")) requireSuccess(doc)
        PreparedDataCache.clear()
        // Never infer success from an empty/unknown mutation response: verify membership on the film page in the caller.
    }

    internal suspend fun rate(provider: String, filmId: String, rating: Int): ServerRatingResult {
        require(filmId.all(Char::isDigit) && rating in 1..10)
        val url = CatalogUrls.provider(provider) + "engine/ajax/rating.php?news_id=$filmId&go_rate=$rating&skin=hdrezka"
        val json = try { JSONObject(transport.get(url).body().text()) } catch (_: Exception) { error("Сайт вернул неожиданный ответ на оценку") }
        check(json.optBoolean("success")) {
            Jsoup.parse(json.optString("message")).text().take(500).ifBlank { "Сайт не принял оценку. Попробуйте ещё раз." }
        }
        return ServerRatingResult(json.optString("num"), json.optString("votes"),
            PersonalRatingParser.parse(Jsoup.parse(json.optString("html")), fragment = true))
    }
}
