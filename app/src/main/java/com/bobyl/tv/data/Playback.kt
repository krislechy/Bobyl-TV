package com.bobyl.tv.data

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import kotlin.io.encoding.Base64

data class MediaTrack(val label: String, val url: String)
data class StreamChoices(val streams: List<MediaTrack>, val subtitles: List<MediaTrack>)
data class VoiceOption(
    val id: String, val title: String, val ads: String = "", val camrip: String = "",
    val director: String = "", val premium: Boolean = false,
)
data class PlaybackInfo(
    val id: String, val isMovie: Boolean, val voices: List<VoiceOption>,
    val initial: StreamChoices? = null, val seasons: Map<String, List<String>> = emptyMap(),
)

object PlaybackParser {
    fun decode(value: String): String {
        if (!value.startsWith("#h")) return value
        var encoded = value.removePrefix("#h")
        repeat(20) {
            val index = encoded.indexOf("//_//")
            if (index >= 0) {
                require(index + 21 <= encoded.length) { "Некорректный ответ с адресом видео" }
                encoded = encoded.removeRange(index, index + 21)
            }
        }
        return try { Base64.decode(encoded.filterNot { it.isWhitespace() }).decodeToString() }
        catch (_: IllegalArgumentException) { error("Не удалось разобрать адрес видео") }
    }

    fun tracks(value: String): List<MediaTrack> {
        if (value.isBlank() || value == "false") return emptyList()
        return decode(value).split(Regex(",(?=\\s*\\[)")).mapNotNull { entry ->
            val text = entry.trim()
            val end = text.indexOf(']')
            if (!text.startsWith('[') || end < 2) return@mapNotNull null
            val label = Jsoup.parseBodyFragment(text.substring(1, end)).text()
            // Reference selects the second variant when the provider offers "url or url".
            val url = text.substring(end + 1).split(" or ").let { alternatives -> alternatives.getOrElse(1) { alternatives[0] } }.trim()
            if (!isMediaUrl(url)) return@mapNotNull null
            MediaTrack(label, url)
        }
    }

    fun isMediaUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    fun seasons(document: Document): Map<String, List<String>> = document
        .select("ul.b-simple_episodes__list").associate { season ->
            season.id().removePrefix("simple-episodes-list-") to
                season.select("li.b-simple_episode__item").map { it.attr("data-episode_id") }.filter { it.isNotBlank() }
        }.filter { it.key.isNotBlank() && it.value.isNotEmpty() }

    fun info(document: Document): PlaybackInfo? {
        val script = document.select("script").joinToString("\n") { it.data() }
        val init = Regex("initCDN(Movies|Series)Events\\s*\\(\\s*(\\d+)\\s*,\\s*['\"]?(\\d+)").find(script)
        val id = init?.groupValues?.get(2)
            ?: document.selectFirst("input#post_id")?.attr("value") ?: return null
        val isMovie = init?.groupValues?.get(1)?.let { it == "Movies" }
            ?: (document.select("meta[property=og:type]").attr("content") == "video.movie")
        val voices = document.select(".b-translator__item").mapNotNull { element ->
            val voiceId = element.attr("data-translator_id")
            if (voiceId.isBlank()) return@mapNotNull null
            val language = element.select("img").attr("alt")
            VoiceOption(voiceId, element.attr("title").ifBlank { element.text() }.ifBlank { "Озвучка $voiceId" } +
                if (language.isBlank()) "" else " ($language)", element.attr("data-ads"), element.attr("data-camrip"),
                element.attr("data-director"), element.hasClass("b-prem_translator"))
        }
        val initial = if (voices.isEmpty() && init != null) {
            jsonObjectAfter(script, init.range.last + 1)?.let { json ->
                StreamChoices(tracks(json.optString("streams")), tracks(json.optString("subtitle")))
            }
        } else null
        val fallbackId = init?.groupValues?.get(3)
        return PlaybackInfo(id, isMovie, voices.ifEmpty {
            if (fallbackId == null) emptyList() else listOf(VoiceOption(fallbackId, "Основная озвучка"))
        }, initial, seasons(document))
    }

    // Scan a balanced JSON object, respecting braces and escaped quotes inside strings.
    private fun jsonObjectAfter(script: String, from: Int): JSONObject? {
        val start = script.indexOf('{', from)
        if (start < 0) return null
        var depth = 0; var quoted = false; var escaped = false
        for (index in start until script.length) {
            val ch = script[index]
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { JSONObject(script.substring(start, index + 1)) }.getOrNull()
            }
        }
        return null
    }

    fun response(document: Document): JSONObject {
        val json = try { JSONObject(document.body().text()) }
        catch (_: Exception) { error("Сайт вернул неожиданный ответ вместо данных видео") }
        check(json.optBoolean("success")) {
            Jsoup.parseBodyFragment(json.optString("message")).text().take(300).ifBlank { "Сайт не предоставил видео для этой озвучки" }
        }
        return json
    }

    fun choices(json: JSONObject): StreamChoices {
        val streams = tracks(json.optString("url"))
        check(streams.isNotEmpty()) { "Для выбранной озвучки нет доступных адресов видео" }
        return StreamChoices(streams, tracks(json.optString("subtitle")))
    }
}

object PlaybackRequests {
    fun movie(info: PlaybackInfo, voice: VoiceOption) = linkedMapOf(
        "id" to info.id, "translator_id" to voice.id, "is_camrip" to voice.camrip,
        "is_ads" to voice.ads, "is_director" to voice.director, "action" to "get_movie",
    )
    fun seasons(info: PlaybackInfo, voice: VoiceOption) = mapOf("id" to info.id, "translator_id" to voice.id, "action" to "get_episodes")
    fun episode(info: PlaybackInfo, voice: VoiceOption, season: String, episode: String) = mapOf(
        "id" to info.id, "translator_id" to voice.id, "season" to season, "episode" to episode, "action" to "get_stream",
    )
}

class PlaybackRepository(private val transport: PageTransport = HtmlTransport()) {
    private suspend fun request(provider: String, form: Map<String, String>): JSONObject = PlaybackParser.response(
        transport.post(CatalogUrls.provider(provider) + "ajax/get_cdn_series/?t=${System.currentTimeMillis()}", form))

    suspend fun movie(provider: String, info: PlaybackInfo, voice: VoiceOption): StreamChoices =
        info.initial?.takeIf { it.streams.isNotEmpty() } ?: PlaybackParser.choices(request(provider, PlaybackRequests.movie(info, voice)))

    suspend fun seasons(provider: String, info: PlaybackInfo, voice: VoiceOption): Map<String, List<String>> =
        PlaybackParser.seasons(Jsoup.parse(request(provider, PlaybackRequests.seasons(info, voice)).optString("episodes")))
            .also { check(it.isNotEmpty()) { "Для этой озвучки пока нет доступных серий" } }

    suspend fun episode(provider: String, info: PlaybackInfo, voice: VoiceOption, season: String, episode: String) =
        PlaybackParser.choices(request(provider, PlaybackRequests.episode(info, voice, season, episode)))
}
