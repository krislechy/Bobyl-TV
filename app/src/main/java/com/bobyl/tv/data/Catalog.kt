package com.bobyl.tv.data

import androidx.compose.runtime.Immutable
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

@Immutable
data class CatalogFilm(
    val id: String,
    val url: String,
    val title: String,
    val poster: String,
    val metadata: String,
    val badge: String,
    val kind: String,
    val rating: String,
)

enum class CatalogSection(val filter: String, val genre: String = "0") {
    Slider("newest_slider_content"), Latest("last"), Popular("popular"),
    Soon("soon"), Watching("watching"), Series("last", "2"), New("new"),
}

data class Person(val name: String, val photo: String, val job: String, val url: String = "")
data class BookmarkCategory(val id: String, val name: String, val link: String = "", val count: Int = 0, val checked: Boolean = false)
data class FilmDetails(
    val title: String, val originalTitle: String, val poster: String, val description: String,
    val metadata: List<String>, val playback: PlaybackInfo? = null, val actors: List<Person> = emptyList(),
    val creators: List<Person> = emptyList(), val siteRating: String = "", val siteVotes: String = "",
    val canRate: Boolean = false, val bookmarks: List<BookmarkCategory> = emptyList(),
    val schedule: List<ScheduleEntry> = emptyList(), val collection: List<CatalogFilm> = emptyList(),
    val related: List<CatalogFilm> = emptyList(), val fullPoster: String = "", val id: String = "",
    val links: List<BrowseLink> = emptyList(), val personalRating: Int? = null,
)

object DetailsParser {
    fun parse(document: Document): FilmDetails {
        val title = document.select("div.b-post__title h1").text()
        require(title.isNotBlank()) { "Сайт вернул страницу без описания фильма" }
        return FilmDetails(
            title,
            document.select("div.b-post__origtitle").text(),
            document.selectFirst("div.b-sidecover a img")?.absUrl("src").orEmpty(),
            document.select("div.b-post__description_text").text(),
            document.select("table.b-post__info tr").mapNotNull { row ->
                val cells = row.select("td")
                if (cells.size >= 2) "${cells[0].text()} ${cells[1].text()}" else null
            },
            PlaybackParser.info(document),
            document.select("div.persons-list-holder").filter { it.select("span.inline h2").text() == "В ролях актеры" }
                .flatMap { it.select("span.item") }.mapNotNull { item ->
                    val node = item.selectFirst("span.person-name-item") ?: item
                    val name = node.select("a span").text().ifBlank { node.text() }
                    if (name.isBlank()) null else Person(name, node.absUrl("data-photo"), node.attr("data-job"), item.selectFirst("a[href]")?.absUrl("href").orEmpty())
                }.distinctBy { it.name },
            document.select("div.persons-list-holder").filter { it.select("span.inline h2").text() != "В ролях актеры" }
                .flatMap { it.select("span.item") }.mapNotNull { item ->
                    val node = item.selectFirst("span.person-name-item") ?: item.selectFirst("span") ?: return@mapNotNull null
                    val name = item.select("a span").text().ifBlank { item.text() }
                    if (name.isBlank()) null else Person(name, node.absUrl("data-photo"), node.attr("data-job"), item.selectFirst("a[href]")?.absUrl("href").orEmpty())
                }.distinctBy { it.name },
            document.select("div.b-post__rating span.num").text(),
            document.select("div.b-post__rating span.votes span").text(),
            document.select("div.b-post__rating_wrapper").isNotEmpty(),
            document.select("div.hd-label-row").mapNotNull { row ->
                val input = row.selectFirst("input") ?: return@mapNotNull null
                val id = input.attr("value")
                if (id.isBlank()) null else BookmarkCategory(id, row.selectFirst("label")?.ownText().orEmpty().ifBlank { "Список $id" }, checked = input.hasAttr("checked"))
            },
            ExtrasParser.schedule(document), ExtrasParser.collection(document), CatalogParser.parse(document),
            document.selectFirst("div.b-sidecover a[href]")?.absUrl("href").orEmpty(),
            document.selectFirst("input#post_id")?.attr("value").orEmpty().ifBlank { PlaybackParser.info(document)?.id.orEmpty() }
                .ifBlank { Regex("/(\\d+)-").find(document.location())?.groupValues?.get(1).orEmpty() },
            document.select("table.b-post__info a[href]").mapNotNull {
                val url = it.absUrl("href")
                if (it.text().isBlank() || !PlaybackParser.isMediaUrl(url) || URI(url).host != URI(document.baseUri()).host || "/person/" in url)
                    null else BrowseLink(it.text(), url)
            }.distinctBy { it.url },
            personalRating = PersonalRatingParser.parse(document),
        )
    }
}

object CatalogUrls {
    fun provider(value: String): String {
        val uri = URI(if ("://" in value) value.trim() else "https://${value.trim()}")
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Укажите HTTPS-адрес сайта без логина и пароля"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Укажите адрес без пути" }
        require(uri.query == null && uri.fragment == null) { "Укажите адрес без параметров" }
        return "https://${uri.rawAuthority}/"
    }

    fun page(provider: String, section: CatalogSection, page: Int, query: String = "", kind: String = section.genre): String {
        require(page > 0)
        val base = provider(provider)
        if (query.isNotBlank()) {
            return base + "search/?do=search&subaction=search&q=" +
                URLEncoder.encode(query.trim(), "UTF-8") + "&page=$page"
        }
        val path = (if (section == CatalogSection.New) "new/" else "") +
            (if (page > 1) "page/$page/" else "")
        val params = buildList {
            if (section != CatalogSection.New) add("filter=${section.filter}")
            require(kind in listOf("0", "1", "2", "3", "4"))
            if (kind != "0") add("genre=$kind")
        }
        return base + path + if (params.isEmpty()) "" else "?${params.joinToString("&")}"
    }
}

object CatalogParser {
    fun parse(document: Document): List<CatalogFilm> = document
        .select("div.b-content__inline_item")
        .mapNotNull { element ->
            val title = element.select("div.b-content__inline_item-link a").text()
            val anchor = element.selectFirst("div.b-content__inline_item-cover a")
                ?: element.selectFirst("div.b-content__inline_item-link a")
            val url = anchor?.absUrl("href").orEmpty().ifBlank { element.absUrl("data-url") }
            if (title.isBlank() || !url.startsWith("http")) return@mapNotNull null
            val image = element.selectFirst("div.b-content__inline_item-cover a img")
            val poster = image?.absUrl("src").orEmpty().ifBlank { image?.absUrl("data-src").orEmpty() }
            CatalogFilm(
                id = element.attr("data-id").ifBlank { url },
                url = url,
                title = title,
                poster = poster,
                metadata = element.select("div.b-content__inline_item-link div").text(),
                // Same server-provided status/episode text as the original FilmsListModel.SUB_INFO.
                badge = element.select("div.b-content__inline_item-cover a span.info").text(),
                kind = element.select("span.cat i.entity").text(),
                rating = element.select("i.b-category-bestrating").text(),
            )
        }.distinctBy { it.url }
}
