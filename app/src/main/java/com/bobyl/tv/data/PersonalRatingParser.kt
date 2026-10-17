package com.bobyl.tv.data

import org.jsoup.nodes.Document

/** Read only an explicitly identified personal vote, never the public average. */
internal object PersonalRatingParser {
    private val personal = Regex("(?:ваш[а-я]*\\s+(?:оценк[а-я]*|голос)|вы\\s+(?:поставили|оценили)[^0-9]{0,40})\\s*[:—-]?\\s*(10|[1-9])(?![0-9.,])", RegexOption.IGNORE_CASE)

    fun parse(document: Document, fragment: Boolean = false): Int? {
        val blocks = if (fragment) listOf(document.body()) else document.select("[id^=rating-layer-]")
            .filter { it.id().matches(Regex("rating-layer-[0-9]+")) }
        for (block in blocks) {
            personal.find(block.text())?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            if (block.select(".b-post__rating_wrapper").isNotEmpty()) return 0
        }
        // Null means the server did not expose a personal number; it is not an unrated vote.
        return null
    }
}
