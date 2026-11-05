package com.bobyl.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** Only parsed public metadata and account-scoped list state; never executable HTML or stream credentials. */
internal object CachePayloadCodec {
    const val Version = 1

    fun supported(operation: String): Boolean = kind(operation) != null

    private fun kind(operation: String): String? = when {
        operation.startsWith("catalog:") || operation == "browse-films" -> "films"
        operation == "favorites" -> "favorites"
        operation == "updates" -> "updates"
        operation == "filters" -> "filters"
        else -> null
    }

    fun encode(operation: String, value: Any): String? {
        val type = kind(operation) ?: return null
        val root = JSONObject().put("type", type)
        when (type) {
            "films" -> root.put("items", JSONArray((value as List<*>).map { film(it as CatalogFilm) }))
            "favorites" -> root.put("items", JSONArray((value as List<*>).map {
                val category = it as BookmarkCategory
                safeUrl(category.link)
                JSONObject().put("id", category.id).put("name", category.name).put("link", category.link)
                    .put("count", category.count).put("checked", category.checked)
            }))
            "updates" -> root.put("items", JSONArray((value as List<*>).map {
                val update = it as SeriesUpdate
                JSONObject().put("date", update.date).put("film", film(update.film))
                    .put("info", update.info).put("tracked", update.tracked)
            }))
            "filters" -> {
                val filters = value as BrowseFilters
                root.put("categories", JSONArray(filters.categories.map { category ->
                    safeUrl(category.url)
                    JSONObject().put("title", category.title).put("url", category.url)
                        .put("genres", JSONArray(category.genres.map { link ->
                            safeUrl(link.url)
                            JSONObject().put("title", link.title).put("url", link.url)
                        }))
                })).put("years", JSONArray(filters.years))
            }
        }
        return root.toString()
    }

    fun decode(operation: String, payload: String): Any? = runCatching {
        val expected = kind(operation) ?: return@runCatching null
        val root = JSONObject(payload)
        check(root.getString("type") == expected)
        when (expected) {
            "films" -> root.getJSONArray("items").objects().map(::readFilm)
            "favorites" -> root.getJSONArray("items").objects().map {
                val link = it.getString("link").also(::safeUrl)
                BookmarkCategory(it.getString("id"), it.getString("name"), link, it.getInt("count"), it.getBoolean("checked"))
            }
            "updates" -> root.getJSONArray("items").objects().map {
                SeriesUpdate(it.getString("date"), readFilm(it.getJSONObject("film")), it.getString("info"), it.getBoolean("tracked"))
            }
            "filters" -> BrowseFilters(root.getJSONArray("categories").objects().map { category ->
                BrowseCategory(category.getString("title"), category.getString("url").also(::safeUrl),
                    category.getJSONArray("genres").objects().map { link ->
                        BrowseLink(link.getString("title"), link.getString("url").also(::safeUrl))
                    })
            }, root.getJSONArray("years").let { years -> (0 until years.length()).map(years::getString) })
            else -> null
        }
    }.getOrNull()

    private fun film(value: CatalogFilm): JSONObject {
        safeUrl(value.url)
        safeUrl(value.poster)
        return JSONObject().put("id", value.id).put("url", value.url).put("title", value.title)
            .put("poster", value.poster).put("metadata", value.metadata).put("badge", value.badge)
            .put("kind", value.kind).put("rating", value.rating)
    }

    private fun readFilm(value: JSONObject): CatalogFilm = CatalogFilm(value.getString("id"),
        value.getString("url").also(::safeUrl), value.getString("title"), value.getString("poster").also(::safeUrl),
        value.getString("metadata"), value.getString("badge"), value.getString("kind"), value.getString("rating"))

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun safeUrl(value: String) = requirePersistablePublicUrl(value)
}
