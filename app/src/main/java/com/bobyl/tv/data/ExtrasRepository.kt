package com.bobyl.tv.data

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
internal fun responseJson(doc: Document): JSONObject = try { JSONObject(doc.body().text()) }
    catch (_: Exception) { error("Сайт вернул неожиданный ответ. Повторите запрос позже.") }
internal fun requireSuccess(doc: Document): JSONObject = responseJson(doc).also {
    check(it.optBoolean("success")) { Jsoup.parse(it.optString("message", "Сервер не подтвердил действие")).text().take(500) }
}

data class HistorySaveSnapshot(val filmId: String, val voice: String, val season: String, val episode: String, val positionMs: Long)
data class ContinueItem(val id: String, val film: CatalogFilm, val info: String, val season: String = "", val episode: String = "", val viewedAt: String = "", val watched: Boolean = false,
    val save: HistorySaveSnapshot? = null)
data class SeriesUpdate(val date: String, val film: CatalogFilm, val info: String, val tracked: Boolean)
data class ScheduleEntry(
    val group: String, val label: String, val id: String, val watched: Boolean,
    val episode: String = "", val title: String = "", val releaseDate: String = "",
)
data class PersonDetails(val description: String, val careers: Map<String, List<CatalogFilm>>)

object ExtrasParser {
    private fun progressNumber(text: String, label: String): String =
        Regex("(\\d+)\\s*$label", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            ?: Regex("$label\\s*[:№]?\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1).orEmpty()
    fun history(doc: Document) = doc.select("div.b-videosaves__list_item[id^=videosave-]").mapNotNull { row ->
        val a = row.selectFirst("div.title a[href]") ?: return@mapNotNull null
        val url = a.absUrl("href")
        val progress = row.select("div.info").text()
        ContinueItem(row.id().removePrefix("videosave-"), CatalogFilm(url, url, row.select("div.title").text(),
            row.selectFirst("a[data-cover_url]")?.absUrl("data-cover_url").orEmpty(), "", "", "", ""),
            listOf(row.select("div.date").text(), progress).filter { it.isNotBlank() }.joinToString(" • "),
            season = progressNumber(progress, "сезон(?:а|ов)?"), episode = progressNumber(progress, "сери(?:я|и|ю|й)"),
            viewedAt = HistoryDates.format(row.select("div.date").text()),
            watched = row.hasClass("watched-row") || row.select(".view.watched").isNotEmpty())
    }
    fun updates(doc: Document) = doc.select("div.b-seriesupdate__block").flatMap { group ->
        group.select("li.b-seriesupdate__block_list_item").mapNotNull { row ->
            val a = row.selectFirst("a.b-seriesupdate__block_list_link[href]") ?: return@mapNotNull null
            val url = a.absUrl("href")
            SeriesUpdate(group.select("div.b-seriesupdate__block_date").text().replace(" развернуть", ""),
                CatalogFilm(url, url, a.text(), row.selectFirst("[data-cover_url]")?.absUrl("data-cover_url")
                    .orEmpty().ifBlank { row.selectFirst("img[src]")?.absUrl("src").orEmpty() }, "", "", "", ""),
                listOf(row.select("span.season").text(), row.select("span.cell").text()).joinToString(" • "), row.hasClass("tracked"))
        }
    }
    fun schedule(doc: Document) = doc.select("div.b-post__schedule_block").flatMap { group ->
        group.select("div.b-post__schedule_list tr").filter { it.select("td").isNotEmpty() && it.select("td.load-more").isEmpty() }.map { row ->
            val watch = row.selectFirst("td.td-3 i")
            ScheduleEntry(group.select("div.b-post__schedule_block_title div.title").text(),
                listOf("td.td-1", "td.td-2 b", "td.td-4", "td.td-5").map { row.select(it).text() }.filter { it.isNotBlank() }.joinToString(" • "),
                watch?.attr("data-id").orEmpty(), watch?.hasClass("watched") == true,
                episode = row.select("td.td-1").text(),
                title = row.select("td.td-2 b").text(),
                releaseDate = listOf("td.td-4", "td.td-5").map { row.select(it).text() }
                    .filter { it.isNotBlank() }.joinToString("\n"))
        }
    }
    fun collection(doc: Document) = doc.select("div.b-post__partcontent_item").mapNotNull { row ->
        val a = row.selectFirst("div.title a[href]") ?: return@mapNotNull null
        val url = a.absUrl("href")
        CatalogFilm(url, url, a.text(), "", row.select("div.year").text(), "", "", row.select("div.rating i").text())
    }
    fun person(doc: Document): PersonDetails = PersonDetails(doc.select("table.b-post__info tr").joinToString("\n") { it.text() },
        doc.select("div.b-person__career").associate { it.select("h2").text() to CatalogParser.parse(Jsoup.parse(it.outerHtml(), doc.baseUri())) })
}

class ExtrasRepository(private val transport: PageTransport = HtmlTransport()) {
    suspend fun prepareHistoryRestore(provider: String, entry: ContinueItem, local: WatchRecord?): ContinueItem = withContext(Dispatchers.IO) {
        val path = java.net.URI(entry.film.url).rawPath
        val document = transport.get(CatalogUrls.provider(provider).trimEnd('/') + path)
        val script = document.select("script").joinToString("\n") { it.data() }
        val init = Regex("initCDN(Movies|Series)Events\\s*\\(\\s*(\\d+)\\s*,\\s*['\"]?(\\d+)(?:['\"]?\\s*,\\s*(\\d+)\\s*,\\s*(\\d+))?").find(script)
            ?: error("Не удалось сохранить параметры для восстановления. Запись не удалена.")
        val season = entry.season.ifBlank { if (init.groupValues[1] == "Movies") "0" else init.groupValues[4] }
        val episode = entry.episode.ifBlank { if (init.groupValues[1] == "Movies") "0" else init.groupValues[5] }
        val position = local?.takeIf { init.groupValues[1] == "Movies" || (it.season == season && it.episode == episode) }?.positionMs ?: 0L
        entry.copy(save = HistorySaveSnapshot(init.groupValues[2], init.groupValues[3], season, episode, position))
    }

    suspend fun restoreHistory(provider: String, entry: ContinueItem): ContinueItem = withContext(Dispatchers.IO) {
        val save = entry.save ?: error("Параметры удалённой записи недоступны")
        fun List<ContinueItem>.matching() = firstOrNull {
            historyFilmKey(it.film.url) == historyFilmKey(entry.film.url) && it.season == entry.season && it.episode == entry.episode
        }
        val existing = history(provider).matching()
        val restored = existing ?: run {
            saveProgress(provider, save.filmId, save.voice, save.season, save.episode, save.positionMs.coerceAtLeast(0) / 1000)
            history(provider).matching() ?: error("Сайт не подтвердил восстановление. Повторите попытку.")
        }
        val confirmed = setHistoryWatched(provider, restored, entry.watched)
        confirmed.copy(save = save)
    }

    suspend fun history(provider: String, force: Boolean = false): List<ContinueItem> = withContext(Dispatchers.IO) {
        val url = CatalogUrls.provider(provider) + "continue/"
        transport.prepared(url, "history", force = force) { ExtrasParser.history(transport.get(url)) }
    }
    /** The endpoint toggles a single save; do not retry it or reload the whole history. */
    suspend fun setHistoryWatched(provider: String, entry: ContinueItem, watched: Boolean): ContinueItem = withContext(Dispatchers.IO) {
        require(entry.id.matches(Regex("[0-9]+")))
        if (entry.watched != watched) {
            requireSuccess(transport.post(CatalogUrls.provider(provider) + "engine/ajax/cdn_saves_view.php", mapOf("id" to entry.id)))
            PreparedDataCache.clear()
        }
        entry.copy(watched = watched)
    }
    suspend fun removeHistory(provider: String, id: String, notifyChanges: Boolean = true) {
        require(id.matches(Regex("[0-9]+")))
        val doc = transport.post(CatalogUrls.provider(provider) + "engine/ajax/cdn_saves_remove.php", mapOf("id" to id))
        // Some legacy endpoints return an empty body on success; reject explicit failures.
        if (doc.body().text().trim().startsWith("{")) requireSuccess(doc)
        PreparedDataCache.clear()
        if (notifyChanges) ServerHistoryChanges.changed()
    }
    suspend fun trackedUpdates(provider: String, force: Boolean = false): List<SeriesUpdate> {
        // The site tracks individual updates/voices and excludes saves marked watched.
        // Mere presence of the film in continue/ is not an equivalent predicate.
        return updates(provider, force)
    }
    suspend fun removeTrackedFilm(provider: String, url: String, notifyChanges: Boolean = true) = withContext(Dispatchers.IO) {
        suspend fun accountHistory(): List<ContinueItem> {
            val doc = transport.get(CatalogUrls.provider(provider) + "continue/")
            check(AccountParser.parse(doc).authenticated == true) { "Войдите в аккаунт, чтобы удалить сериал из «Продолжить»." }
            return ExtrasParser.history(doc)
        }
        val key = historyFilmKey(url)
        val entries = accountHistory().filter { historyFilmKey(it.film.url) == key }.distinctBy { it.id }
        for (entry in entries) {
            removeHistory(provider, entry.id, notifyChanges = false)
            delay(250)
        }
        check(accountHistory().none { historyFilmKey(it.film.url) == key }) { "Сайт не подтвердил удаление из «Продолжить». Обновите список." }
        if (notifyChanges) ServerHistoryChanges.changed()
    }
    suspend fun clearHistory(provider: String, progress: (Int, Int) -> Unit = { _, _ -> }): Int = withContext(Dispatchers.IO) {
        var removed = 0
        val removedIds = mutableSetOf<String>()
        val knownIds = mutableSetOf<String>()
        suspend fun remaining(): List<ContinueItem> {
            val document = transport.get(CatalogUrls.provider(provider) + "continue/")
            check(AccountParser.parse(document).authenticated == true) { "Для очистки истории аккаунта необходимо войти на сайт." }
            return ExtrasParser.history(document).distinctBy { it.id }.also { entries ->
                knownIds += entries.map { it.id }
                progress(removed, knownIds.size)
            }
        }
        try {
            var entries = remaining()
            while (true) {
                if (entries.isEmpty()) break
                check(entries.none { it.id in removedIds }) { "Сайт не подтвердил удаление записей. Обновите список «Продолжить»." }
                val batch = entries.take(5)
                var failure: Exception? = null
                val attempted = mutableListOf<String>()
                for (entry in batch) {
                    attempted += entry.id
                    try { removeHistory(provider, entry.id, notifyChanges = false) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failure = error; break }
                    delay(250)
                }
                delay(1000)
                entries = remaining()
                val remainingIds = entries.map { it.id }.toSet()
                for (id in attempted.filter { it !in remainingIds }) {
                    removedIds += id
                    removed++
                }
                progress(removed, knownIds.size)
                failure?.let { throw it }
                check(attempted.none { it in remainingIds }) { "Сайт не подтвердил удаление записей. Повторите очистку позже." }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (removed == 0) throw failure
            throw IllegalStateException("Очистка не завершена. Удалено $removed из ${knownIds.size}. ${networkErrorMessage(failure)}", failure)
        } finally {
            if (removed > 0) ServerHistoryChanges.changed()
        }
        removed
    }

    suspend fun saveProgress(provider: String, filmId: String, voice: String, season: String, episode: String, seconds: Long) {
        require(filmId.matches(Regex("[0-9]+")) && seconds >= 0)
        requireSuccess(transport.post(CatalogUrls.provider(provider) + "ajax/send_save/?t=${System.currentTimeMillis()}",
            mapOf("post_id" to filmId, "translator_id" to voice, "season" to season.ifBlank { "0" },
                "episode" to episode.ifBlank { "0" }, "current_time" to seconds.toString())))
        PreparedDataCache.clear()
    }
    suspend fun updates(provider: String, force: Boolean = false): List<SeriesUpdate> = withContext(Dispatchers.IO) {
        val url = CatalogUrls.provider(provider)
        transport.prepared(url, "updates", force = force) { ExtrasParser.updates(transport.preparedRoot(url, force)) }
    }
    suspend fun updatePoster(url: String): String = withContext(Dispatchers.IO) {
        val document = transport.cachedGet(url)
        val image = document.selectFirst("div.b-sidecover a img")
        image?.absUrl("data-src").orEmpty().ifBlank { image?.absUrl("src").orEmpty() }
            .ifBlank { document.selectFirst("meta[property=og:image]")?.absUrl("content").orEmpty() }
    }
    suspend fun watch(provider: String, id: String) {
        require(id.matches(Regex("[0-9]+")))
        requireSuccess(transport.post(CatalogUrls.provider(provider) + "engine/ajax/schedule_watched.php", mapOf("id" to id)))
        PreparedDataCache.clear()
    }
    suspend fun person(url: String) = ExtrasParser.person(transport.cachedGet(url))
    suspend fun trailer(provider: String, id: String): String {
        require(id.matches(Regex("[0-9]+")))
        val json = requireSuccess(transport.post(CatalogUrls.provider(provider) + "engine/ajax/gettrailervideo.php", mapOf("id" to id)))
        val doc = Jsoup.parse(json.optString("code"), CatalogUrls.provider(provider))
        val url = doc.selectFirst("iframe[src]")?.absUrl("src").orEmpty()
            .ifBlank { doc.selectFirst("video[src], source[src]")?.absUrl("src").orEmpty() }
        check(PlaybackParser.isMediaUrl(url)) { "Сайт не предоставил адрес трейлера" }
        return url
    }
}
