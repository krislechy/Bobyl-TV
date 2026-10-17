package com.bobyl.tv.data

import org.jsoup.nodes.Document
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BrowseLink(val title: String, val url: String)
data class BrowseCategory(val title: String, val url: String, val genres: List<BrowseLink>)
data class BrowseFilters(val categories: List<BrowseCategory>, val years: List<String>)

object BrowseParser {
    fun filters(doc: Document) = BrowseFilters(
        doc.select("li.b-topnav__item:not(.single)").mapNotNull { item ->
            val a = item.selectFirst("a.b-topnav__item-link") ?: return@mapNotNull null
            BrowseCategory(a.text(), a.absUrl("href"), item.select("select.select-category option").mapNotNull {
                val value = it.attr("value")
                if (value.isBlank()) null else BrowseLink(it.text(), URI(doc.baseUri()).resolve(value).toString())
            }.distinctBy { it.url })
        }, doc.selectFirst("select.select-year")?.select("option")?.map { it.text() }?.filter { it.matches(Regex("\\d{4}")) }.orEmpty(),
    )
    fun categoryUrl(category: BrowseCategory, genre: String, year: String, page: Int): String {
        require(page > 0 && (year.isEmpty() || year.matches(Regex("\\d{4}"))))
        val base = if (genre.isBlank()) category.url.trimEnd('/') + "/best/" else genre.trimEnd('/') + "/"
        return base + (if (year.isBlank()) "" else "$year/") + "page/$page/"
    }
    fun suggestions(doc: Document) = doc.select("li").mapNotNull { item ->
        val a = item.selectFirst("a[href]") ?: return@mapNotNull null
        val title = item.select("span.enty").text()
        val image = item.selectFirst("img")
        val cover = item.selectFirst("[data-cover_url]")?.absUrl("data-cover_url").orEmpty()
        val poster = cover.ifBlank { image?.absUrl("data-src").orEmpty() }
            .ifBlank { image?.absUrl("src").orEmpty() }
        val metadata = listOf(a.ownText(), item.select(".year, .info, .meta").text())
            .filter { it.isNotBlank() }.distinct().joinToString(" • ")
        if (title.isBlank()) null else CatalogFilm(a.absUrl("href"), a.absUrl("href"), title,
            poster, metadata, "", "", item.select("span.rating i").text())
    }.distinctBy { it.url }
}

class BrowseRepository(private val transport: PageTransport = HtmlTransport()) {
    suspend fun filmsAt(url: String, page: Int): List<CatalogFilm> {
        require(PlaybackParser.isMediaUrl(url) && page > 0)
        val uri = URI(url)
        val target = if (page == 1) url else URI(uri.scheme, uri.authority,
            uri.path.replace(Regex("/page/\\d+/?$"), "").trimEnd('/') + "/page/$page/", uri.query, null).toString()
        return CatalogParser.parse(transport.cachedGet(target))
    }
    suspend fun filters(provider: String, force: Boolean = false): BrowseFilters {
        val url = CatalogUrls.provider(provider)
        return transport.prepared(url, "filters", 10 * 60_000L, force) {
            val document = transport.preparedRoot(url, force)
            withContext(Dispatchers.Default) { BrowseParser.filters(document) }
        }
    }
    suspend fun films(category: BrowseCategory, genre: String, year: String, page: Int) =
        CatalogParser.parse(transport.cachedGet(BrowseParser.categoryUrl(category, genre, year, page)))
    suspend fun suggestions(provider: String, query: String) = BrowseParser.suggestions(
        transport.post(CatalogUrls.provider(provider) + "engine/ajax/search.php", mapOf("q" to query.trim())))

    suspend fun quickSearch(provider: String, query: String): List<CatalogFilm> {
        val suggestions = suggestions(provider, query)
        if (suggestions.none { it.poster.isBlank() }) return suggestions
        // Fill missing thumbnails with one cached search page, rather than loading every film's details.
        val matches = try {
            CatalogParser.parse(transport.cachedGet(CatalogUrls.page(provider, CatalogSection.Latest, 1, query)))
                .associateBy { it.url }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return suggestions
        }
        return suggestions.map { film ->
            val match = matches[film.url] ?: return@map film
            film.copy(poster = film.poster.ifBlank { match.poster },
                metadata = listOf(film.metadata, match.metadata).filter { it.isNotBlank() }.distinct().joinToString(" • "))
        }
    }
}
