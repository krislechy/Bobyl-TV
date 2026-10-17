package com.bobyl.tv.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class FilmComment(val id: String, val author: String, val date: String, val text: String,
    val likes: String, val liked: Boolean, val self: Boolean, val deleteHash: String, val depth: Int)

object CommentsParser {
    fun parse(doc: Document): List<FilmComment> = doc.select("div[id^=comment-id-]").mapNotNull { node ->
        val id = node.id().removePrefix("comment-id-")
        if (!id.matches(Regex("[0-9]+"))) return@mapNotNull null
        val like = node.selectFirst("span.show-likes-comment")
        val delete = node.select("div.actions ul.edit li a").firstOrNull { it.text() == "Удалить" }?.attr("onclick").orEmpty()
        val hash = Regex("deleteComment\\(\\s*\\d+\\s*,\\s*'([^']+)'").find(delete)?.groupValues?.get(1).orEmpty()
        FilmComment(id, node.selectFirst("span.name")?.text().orEmpty(), node.selectFirst("span.date")?.text().orEmpty(),
            node.selectFirst("div.text")?.text().orEmpty(), node.selectFirst("span.b-comment__likes_count i")?.text().orEmpty(),
            like?.hasClass("disabled") == true, like?.hasClass("self-disabled") == true, hash,
            node.parents().count { it.hasClass("comments-tree-item") }.coerceAtMost(6))
    }.distinctBy { it.id }
}

class CommentsRepository(private val transport: PageTransport = HtmlTransport()) {
    suspend fun load(provider: String, filmId: String, page: Int): List<FilmComment> {
        require(filmId.matches(Regex("[0-9]+")) && page > 0)
        val json = responseJson(transport.get(CatalogUrls.provider(provider) +
            "ajax/get_comments/?t=${System.currentTimeMillis()}&news_id=$filmId&cstart=$page&type=0&comment_id=0&skin=hdrezka"))
        check(!json.has("success") || json.optBoolean("success")) { "Не удалось загрузить комментарии" }
        return CommentsParser.parse(Jsoup.parse(json.optString("comments")))
    }
    suspend fun post(provider: String, filmId: String, text: String, parent: String): String {
        require(text.isNotBlank() && filmId.matches(Regex("[0-9]+")) && parent.matches(Regex("[0-9]+")))
        val json = requireSuccess(transport.post(CatalogUrls.provider(provider) + "ajax/add_comment/",
            mapOf("comments" to text, "post_id" to filmId, "parent" to parent, "type" to "0", "g_recaptcha_response" to "", "has_adb" to "1")))
        return if (json.optString("message").contains("проверки администрац")) "Комментарий отправлен на модерацию" else "Комментарий отправлен"
    }
    suspend fun like(provider: String, comment: FilmComment) {
        check(!comment.self) { "Собственный комментарий оценить нельзя" }
        val doc = transport.get(CatalogUrls.provider(provider) + "engine/ajax/comments_like.php?id=${comment.id}")
        if (doc.body().text().trim().startsWith("{")) requireSuccess(doc)
    }
    suspend fun delete(provider: String, comment: FilmComment) {
        require(comment.deleteHash.isNotBlank())
        val doc = transport.get(CatalogUrls.provider(provider) + "engine/ajax/deletecomments.php?id=${comment.id}&dle_allow_hash=${encode(comment.deleteHash)}&type=0&area=ajax")
        if (doc.body().text().trim().startsWith("{")) requireSuccess(doc)
    }
}
