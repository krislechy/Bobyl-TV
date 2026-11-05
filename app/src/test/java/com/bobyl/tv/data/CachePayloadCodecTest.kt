package com.bobyl.tv.data

import org.junit.Assert.*
import org.junit.Test

class CachePayloadCodecTest {
    private val film = CatalogFilm("12", "https://site.test/films/12-film.html", "Фильм", "https://cdn.test/poster.jpg",
        "2026 • драма", "HD", "1", "8.5")

    @Test fun roundTripsAllWhitelistedDtosIncludingEmptyLists() {
        val examples: List<Pair<String, Any>> = listOf(
            "catalog:Latest:0" to listOf(film), "browse-films" to emptyList<CatalogFilm>(),
            "favorites" to listOf(BookmarkCategory("7", "Смотреть", "https://site.test/favorites/7/", 5, true)),
            "updates" to listOf(SeriesUpdate("Сегодня", film, "Серия 2", true)),
            "filters" to BrowseFilters(listOf(BrowseCategory("Фильмы", "https://site.test/films/",
                listOf(BrowseLink("Драма", "https://site.test/films/drama/")))), listOf("2026", "2025")),
        )
        examples.forEach { (operation, value) ->
            val encoded = checkNotNull(CachePayloadCodec.encode(operation, value))
            assertEquals(value, CachePayloadCodec.decode(operation, encoded))
        }
    }

    @Test fun rejectsUnknownAndMismatchedPayloadTypesWithoutInventingEmptyData() {
        assertNull(CachePayloadCodec.encode("root", "<html>account csrf</html>"))
        assertNull(CachePayloadCodec.decode("favorites", checkNotNull(CachePayloadCodec.encode("catalog:Latest:0", listOf(film)))))
        assertNull(CachePayloadCodec.decode("filters", "{broken"))
        assertNull(CachePayloadCodec.decode("catalog:Latest:0", "{\"type\":\"films\",\"items\":[{}]}"))
    }

    @Test fun refusesCredentialAndSignedUrlsInPersistedDtos() {
        listOf("https://user:password@site.test/12-film.html", "https://site.test/12-film.html?token=secret",
            "https://site.test/12-film.html?%74oken=secret", "https://cdn.test/file.jpg?Signature=secret").forEach { unsafe ->
            assertThrows(IllegalStateException::class.java) {
                CachePayloadCodec.encode("catalog:Latest:0", listOf(film.copy(url = unsafe)))
            }
        }
    }

    @Test fun preservesAllFilmAndAccountListFields() {
        val encoded = checkNotNull(CachePayloadCodec.encode("updates", listOf(SeriesUpdate("дата", film, "текст", false))))
        val restored = CachePayloadCodec.decode("updates", encoded) as List<*>
        assertEquals(SeriesUpdate("дата", film, "текст", false), restored.single())
    }
}
