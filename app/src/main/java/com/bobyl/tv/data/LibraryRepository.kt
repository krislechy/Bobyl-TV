package com.bobyl.tv.data

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ServerRatingResult(val average: String, val votes: String, val personal: Int?)

class LibraryRepository(private val transport: PageTransport = HtmlTransport()) {
    companion object {
        private val mutableChanges = MutableStateFlow<Map<AccountSession, Int>>(emptyMap())
        internal val changes = mutableChanges.asStateFlow()
        internal fun revision(session: AccountSession): Int = changes.value[session] ?: 0

        @Synchronized private fun changed(session: AccountSession) {
            if (AccountSessionGuard.shared.current(session.provider) != session) return
            val next = mutableChanges.value.toMutableMap()
            val revision = (next.remove(session) ?: 0) + 1
            next[session] = revision
            mutableChanges.value = next.entries.toList().takeLast(32).associate { it.toPair() }
        }
    }
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

    suspend fun films(category: BookmarkCategory, page: Int = 1, sort: String = "added", kind: String = "0", force: Boolean = false): List<CatalogFilm> {
        require(page > 0 && sort in listOf("added", "year", "popular") && kind in listOf("0", "1", "2", "3", "4"))
        val url = category.link.trimEnd('/') + "/page/$page/?filter=$sort" + if (kind == "0") "" else "&genre=$kind"
        val result = transport.prepared(url, "browse-films", PageCache.durationMillis(), force) {
            val document = transport.get(url)
            withContext(Dispatchers.Default) { CatalogParser.parse(document) }
        }
        transport.rememberFilms(url, result)
        return result
    }
    suspend fun create(provider: String, name: String) = create(AccountSessionGuard.shared.current(provider), name)

    /** The UI captures this session before launching the accepted command. */
    internal suspend fun create(session: AccountSession, name: String) = owned(session) {
        require(name.isNotBlank()) { "Введите название списка" }
        try {
            val document = transport.post(session.provider + "ajax/favorites/", mapOf("name" to name.trim(), "action" to "add_cat"))
            withContext(Dispatchers.Default) { requireSuccess(document) }
        }
        finally { withContext(NonCancellable) { PersistentCache.invalidate(); changed(session) } }
        Unit
    }

    suspend fun toggle(provider: String, filmId: String, categoryId: String) =
        owned(AccountSessionGuard.shared.current(provider)) { session -> toggleAccepted(session, filmId, categoryId) }

    /** Only called while the existing FilmActions command owns this session's server permit. */
    internal suspend fun toggleAccepted(session: AccountSession, filmId: String, categoryId: String) {
        require(filmId.isNotBlank() && filmId.all(Char::isDigit) && categoryId.isNotBlank())
        try {
            val doc = transport.post(session.provider + "ajax/favorites/", mapOf("post_id" to filmId, "cat_id" to categoryId, "action" to "add_post"))
            withContext(Dispatchers.Default) { if (doc.body().text().trim().startsWith("{")) requireSuccess(doc) }
        } finally { withContext(NonCancellable) { PersistentCache.invalidate(); changed(session) } }
        // Never infer success from an empty/unknown mutation response: verify membership on the film page in the caller.
    }

    internal suspend fun rate(provider: String, filmId: String, rating: Int): ServerRatingResult {
        require(filmId.all(Char::isDigit) && rating in 1..10)
        val url = CatalogUrls.provider(provider) + "engine/ajax/rating.php?news_id=$filmId&go_rate=$rating&skin=hdrezka"
        val document = transport.get(url)
        val result = withContext(Dispatchers.Default) {
            val json = try { JSONObject(document.body().text()) }
                catch (_: Exception) { error("Сайт вернул неожиданный ответ на оценку") }
            check(json.optBoolean("success")) {
                Jsoup.parse(json.optString("message")).text().take(500).ifBlank { "Сайт не принял оценку. Попробуйте ещё раз." }
            }
            ServerRatingResult(json.optString("num"), json.optString("votes"),
                PersonalRatingParser.parse(Jsoup.parse(json.optString("html")), fragment = true))
        }
        PersistentCache.invalidate()
        return result
    }

    private suspend fun <T> owned(session: AccountSession, work: suspend (AccountSession) -> T): T {
        val guard = AccountSessionGuard.shared
        val permit = guard.register(session)
        try { return guard.server(permit) { work(session) } }
        finally { guard.finish(permit) }
    }

    internal suspend fun removeFromAll(provider: String, url: String): List<BookmarkCategory> =
        owned(AccountSessionGuard.shared.current(provider)) { session -> removeFromAllAccepted(session, url) }

    internal suspend fun removeFromAllAccepted(session: AccountSession, url: String): List<BookmarkCategory> {
        val catalog = CatalogRepository(transport)
        var details = catalog.details(url)
        val filmId = details.id.ifBlank { historyFilmKey(url) }
        require(filmId.isNotBlank() && filmId.all(Char::isDigit)) { "Не удалось определить фильм" }
        val categoryIds = details.bookmarks.filter { it.checked }.map { it.id }
        for (categoryId in categoryIds) {
            // add_post toggles membership: always re-read before mutating, never add a removed film back.
            details = catalog.details(url)
            if (details.bookmarks.none { it.id == categoryId && it.checked }) continue
            toggleAccepted(session, filmId, categoryId)
            details = catalog.details(url)
            check(details.bookmarks.none { it.id == categoryId && it.checked }) {
                "Сервер не подтвердил удаление. Попробуйте ещё раз."
            }
        }
        check(details.bookmarks.none { it.checked }) { "Фильм ещё есть в избранном. Повторите удаление." }
        return details.bookmarks
    }
}
