package com.bobyl.tv.data

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseParserTest {
    @Test fun duplicateGenreUrlsKeepFirstLabelAndOrderWithinEachCategory() {
        val doc = Jsoup.parse("""
            <li class="b-topnav__item">
                <a class="b-topnav__item-link" href="/films/">Фильмы</a>
                <select class="select-category">
                    <option value="">Выберите жанр</option>
                    <option value="/films/best/thriller/">Триллеры</option>
                    <option value="/films/best/musical/">Мюзиклы</option>
                    <option value="https://hdrzk.org/films/best/musical/">Музыкальные</option>
                    <option value="/films/best/horror/">Ужасы</option>
                </select>
            </li>
            <li class="b-topnav__item">
                <a class="b-topnav__item-link" href="/series/">Сериалы</a>
                <select class="select-category">
                    <option value="/series/best/musical/">Мюзиклы</option>
                </select>
            </li>
        """.trimIndent(), "https://hdrzk.org/")

        val categories = BrowseParser.filters(doc).categories

        assertEquals(listOf(
            BrowseLink("Триллеры", "https://hdrzk.org/films/best/thriller/"),
            BrowseLink("Мюзиклы", "https://hdrzk.org/films/best/musical/"),
            BrowseLink("Ужасы", "https://hdrzk.org/films/best/horror/"),
        ), categories[0].genres)
        assertEquals(listOf(BrowseLink("Мюзиклы", "https://hdrzk.org/series/best/musical/")), categories[1].genres)
    }
}
