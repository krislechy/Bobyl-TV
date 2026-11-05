package com.bobyl.tv.data.database

import android.content.Context
import com.bobyl.tv.data.BrowseLink
import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.CatalogUrls
import com.bobyl.tv.data.FilmDetails
import com.bobyl.tv.data.Person
import com.bobyl.tv.data.ScheduleEntry
import com.bobyl.tv.data.historyFilmKey
import com.bobyl.tv.data.requirePersistablePublicUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Public descriptive metadata only. Playback URLs and account-derived fields are never serialized. */
internal class FilmMetadataStore(private val database: BobylDatabase) {
    companion object {
        @Volatile private var installed: FilmMetadataStore? = null
        fun from(context: Context) = FilmMetadataStore(AppStorage.database(context))
        fun install(store: FilmMetadataStore) { installed = store }
        fun currentOrNull(): FilmMetadataStore? = installed
        internal fun removeIfInstalled(store: FilmMetadataStore) { if (installed === store) installed = null }
    }
    suspend fun rememberFilms(provider: String, films: List<CatalogFilm>) = withContext(Dispatchers.IO) {
        val origin = CatalogUrls.provider(provider)
        if (!publicUrls(origin)) return@withContext
        database.storage().putFilms(films.filter { publicUrls(it.url, it.poster) }.map { film ->
            FilmRow(origin, filmKey(film.url.ifBlank { film.id }), film.id, film.url, film.title, film.poster,
                film.metadata, film.badge, film.kind, film.rating, System.currentTimeMillis()) })
    }
    suspend fun readFilm(provider: String, reference: String): CatalogFilm? = withContext(Dispatchers.IO) {
        database.storage().film(CatalogUrls.provider(provider), filmKey(reference))?.let {
            CatalogFilm(it.id, it.url, it.title, it.poster, it.metadata, it.badge, it.kind, it.rating)
        }
    }
    suspend fun rememberDetails(provider: String, details: FilmDetails) = withContext(Dispatchers.IO) {
        if (details.id.isBlank()) return@withContext
        val origin = CatalogUrls.provider(provider)
        if (!publicUrls(origin)) return@withContext
        database.storage().putDetails(FilmDetailsRow(origin, filmKey(details.id),
            detailsJson(details).toString(), 1, System.currentTimeMillis()))
    }
    suspend fun readDetails(provider: String, reference: String): FilmDetails? = withContext(Dispatchers.IO) {
        database.storage().details(CatalogUrls.provider(provider), filmKey(reference))?.takeIf { it.codecVersion == 1 }
            ?.let { readDetailsJson(JSONObject(it.payload)) }
    }
    /** IO-only labels for old/incomplete history snapshots. Fresh membership is never inferred here. */
    internal fun historyLabels(provider: String, reference: String): Pair<String, String>? {
        val origin = CatalogUrls.provider(provider)
        val key = filmKey(reference)
        val film = database.storage().film(origin, key)
        if (film != null && film.title.isNotBlank() && film.poster.isNotBlank()) return film.title to film.poster
        val detail = database.storage().details(origin, key)?.takeIf { it.codecVersion == 1 }?.let { JSONObject(it.payload) }
        return if (film != null || detail != null) (film?.title?.takeIf { it.isNotBlank() } ?: detail?.optString("title").orEmpty()) to
            (film?.poster?.takeIf { it.isNotBlank() } ?: detail?.optString("poster").orEmpty()) else null
    }
}

private fun filmKey(reference: String) = reference.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: historyFilmKey(reference)
private fun strings(values: List<String>) = JSONArray().apply { values.forEach { put(it) } }
private fun publicUrls(vararg values: String): Boolean = try {
    values.forEach(::requirePersistablePublicUrl)
    true
} catch (_: Exception) { false }
private fun publicUrlOrBlank(value: String): String = value.takeIf { publicUrls(it) }.orEmpty()
private fun people(values: List<Person>) = JSONArray().apply { values.forEach { put(JSONObject()
    .put("name", it.name).put("photo", publicUrlOrBlank(it.photo)).put("job", it.job).put("url", publicUrlOrBlank(it.url))) } }
private fun films(values: List<CatalogFilm>) = JSONArray().apply { values.filter { publicUrls(it.url, it.poster) }.forEach { put(JSONObject()
    .put("id", it.id).put("url", it.url).put("title", it.title).put("poster", it.poster)
    .put("metadata", it.metadata).put("badge", it.badge).put("kind", it.kind).put("rating", it.rating)) } }
private fun detailsJson(value: FilmDetails) = JSONObject().put("title", value.title).put("originalTitle", value.originalTitle)
    .put("poster", publicUrlOrBlank(value.poster)).put("description", value.description).put("metadata", strings(value.metadata))
    .put("actors", people(value.actors)).put("creators", people(value.creators))
    .put("siteRating", value.siteRating).put("siteVotes", value.siteVotes)
    .put("collection", films(value.collection)).put("related", films(value.related))
    .put("fullPoster", publicUrlOrBlank(value.fullPoster)).put("id", value.id)
    .put("links", JSONArray().apply { value.links.filter { publicUrls(it.url) }.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } })
    .put("schedule", JSONArray().apply { value.schedule.forEach { put(JSONObject().put("id", it.id)
        .put("group", it.group).put("label", it.label).put("episode", it.episode)
        .put("title", it.title).put("releaseDate", it.releaseDate)) } })

private fun <T> JSONArray.values(transform: (JSONObject) -> T) = (0 until length()).map { transform(getJSONObject(it)) }
private fun JSONArray.stringValues() = (0 until length()).map { getString(it) }
private fun readPeople(array: JSONArray) = array.values { Person(it.getString("name"), it.getString("photo"), it.getString("job"), it.getString("url")) }
private fun readFilms(array: JSONArray) = array.values { CatalogFilm(it.getString("id"), it.getString("url"), it.getString("title"),
    it.getString("poster"), it.getString("metadata"), it.getString("badge"), it.getString("kind"), it.getString("rating")) }
private fun readDetailsJson(value: JSONObject) = FilmDetails(title = value.getString("title"), originalTitle = value.getString("originalTitle"),
    poster = value.getString("poster"), description = value.getString("description"), metadata = value.getJSONArray("metadata").stringValues(),
    actors = readPeople(value.getJSONArray("actors")), creators = readPeople(value.getJSONArray("creators")),
    siteRating = value.getString("siteRating"), siteVotes = value.getString("siteVotes"),
    collection = readFilms(value.getJSONArray("collection")), related = readFilms(value.getJSONArray("related")),
    fullPoster = value.getString("fullPoster"), id = value.getString("id"),
    links = value.getJSONArray("links").values { BrowseLink(it.getString("title"), it.getString("url")) },
    schedule = value.getJSONArray("schedule").values { ScheduleEntry(it.getString("group"), it.getString("label"),
        it.getString("id"), false, it.getString("episode"), it.getString("title"), it.getString("releaseDate")) })
