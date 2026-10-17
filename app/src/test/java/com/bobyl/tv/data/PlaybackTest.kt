package com.bobyl.tv.data

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import kotlin.io.encoding.Base64

class PlaybackTest {
    private val voice = VoiceOption("7", "Studio", "1", "0", "0")
    private val film = PlaybackInfo("123", true, listOf(voice))

    @Test fun movieRequestPreservesReferenceFields() {
        assertEquals(mapOf("id" to "123", "translator_id" to "7", "is_ads" to "1", "is_camrip" to "0",
            "is_director" to "0", "action" to "get_movie"), PlaybackRequests.movie(film, voice))
    }
    @Test fun seriesRequestsDoNotInheritMovieFlags() {
        assertEquals(mapOf("id" to "123", "translator_id" to "7", "action" to "get_episodes"), PlaybackRequests.seasons(film, voice))
        assertEquals(mapOf("id" to "123", "translator_id" to "7", "action" to "get_stream", "season" to "2", "episode" to "9"), PlaybackRequests.episode(film, voice, "2", "9"))
    }
    @Test fun parsesQualityAndChoosesSecondAlternativeLikeOriginal() {
        assertEquals(listOf(MediaTrack("720p", "https://cdn.example/720.mp4"), MediaTrack("1080p", "https://cdn.example/b.mp4")),
            PlaybackParser.tracks("[720p]https://cdn.example/720.mp4,[1080p]https://cdn.example/a.m3u8 or https://cdn.example/b.mp4"))
    }
    @Test fun decodesReferenceMarkerAndBase64() {
        val raw = "[720p]https://cdn.example/movie.mp4"
        val base = Base64.encode(raw.encodeToByteArray())
        assertEquals(raw, PlaybackParser.decode("#h" + base.take(8) + "//_//0123456789abcdef" + base.drop(8)))
    }
    @Test fun filtersInvalidSchemesAndEmptySubtitles() {
        assertTrue(PlaybackParser.tracks("false").isEmpty())
        assertTrue(PlaybackParser.tracks("[720p]intent://bad").isEmpty())
        assertTrue(PlaybackParser.tracks("[720p]https://name:password@example.com/video").isEmpty())
        assertEquals("Русский", PlaybackParser.tracks("[Русский]https://cdn.example/sub.vtt").single().label)
    }
    @Test fun extractsVoiceFlagsAndMovieId() {
        val doc = Jsoup.parse("""<meta property="og:type" content="video.movie">
            <li class="b-translator__item b-prem_translator" data-translator_id="7" title="Studio" data-ads="1" data-camrip="0" data-director="0"></li>
            <script>sof.tv.initCDNMoviesEvents(123, 7, {"id":123});</script>""")
        val info = PlaybackParser.info(doc)!!
        assertEquals("123", info.id)
        assertTrue(info.isMovie)
        assertEquals(voice.copy(premium = true), info.voices.single())
    }
    @Test fun extractsInlineStreamsWithoutTranslatorList() {
        val doc = Jsoup.parse("""<script>sof.tv.initCDNMoviesEvents(123, 7, {"id":123,"streams":"[720p]https://cdn.example/a.mp4","subtitle":false});</script>""")
        val info = PlaybackParser.info(doc)!!
        assertEquals("7", info.voices.single().id)
        assertEquals("720p", info.initial!!.streams.single().label)
    }
    @Test fun parsesSeasonsInProviderOrder() {
        val doc = Jsoup.parse("""<ul class="b-simple_episodes__list" id="simple-episodes-list-2"><li class="b-simple_episode__item" data-episode_id="3"></li><li class="b-simple_episode__item" data-episode_id="4"></li></ul>""")
        assertEquals(mapOf("2" to listOf("3", "4")), PlaybackParser.seasons(doc))
    }
    @Test fun serverErrorIsNotTreatedAsEmptySuccess() {
        val result = runCatching { PlaybackParser.response(Jsoup.parse("""{"success":false,"message":"<b>Недоступно</b>"}""")) }
        assertTrue(result.isFailure)
        assertEquals("Недоступно", result.exceptionOrNull()?.message)
    }
    @Test fun seasonAndEpisodeRequestsFollowSelectedVoiceWithOrWithoutSubtitles() = runBlocking {
        val dubbed = VoiceOption("7", "Studio")
        val original = VoiceOption("8", "Original")
        val series = PlaybackInfo("123", false, listOf(dubbed, original))
        val transport = object : PageTransport {
            override suspend fun get(url: String, details: Boolean): Document = error("Unexpected GET")
            override suspend fun post(url: String, form: Map<String, String>): Document {
                val translator = form.getValue("translator_id")
                val lastEpisode = if (translator == dubbed.id) "2" else "3"
                return if (form["action"] == "get_episodes") {
                    ResponseDocument.parse(org.json.JSONObject().put("success", true).put("episodes",
                        """<ul class="b-simple_episodes__list" id="simple-episodes-list-1"><li class="b-simple_episode__item" data-episode_id="1"></li><li class="b-simple_episode__item" data-episode_id="$lastEpisode"></li></ul>""").toString(), url)
                } else {
                    assertEquals("get_stream", form["action"])
                    assertEquals("1", form["season"])
                    assertEquals(lastEpisode, form["episode"])
                    Jsoup.parse(org.json.JSONObject().put("success", true)
                        .put("url", "[720p]https://cdn.example/$translator/$lastEpisode.mp4")
                        .put("subtitle", if (translator == original.id) "[Русский]https://cdn.example/$lastEpisode.srt" else false).toString())
                }
            }
        }
        val repository = PlaybackRepository(transport)
        for (selected in listOf(dubbed, original, dubbed)) {
            val episodes = repository.seasons("example.org", series, selected).getValue("1")
            assertEquals(listOf("1", if (selected == dubbed) "2" else "3"), episodes)
            val choices = repository.episode("example.org", series, selected, "1", episodes.last())
            assertEquals("https://cdn.example/${selected.id}/${episodes.last()}.mp4", choices.streams.single().url)
            assertEquals(selected == original, choices.subtitles.isNotEmpty())
        }
    }
    @Test fun repositoryUsesReferenceEndpointAndMovieForm() = runBlocking {
        var called = false
        val transport = object : PageTransport {
            override suspend fun get(url: String, details: Boolean): Document = error("Unexpected GET")
            override suspend fun post(url: String, form: Map<String, String>): Document {
                called = true
                assertTrue(url.startsWith("https://example.org/ajax/get_cdn_series/?t="))
                assertEquals(PlaybackRequests.movie(film, voice), form)
                return Jsoup.parse("""{"success":true,"url":"[720p]https://cdn.example/a.mp4","subtitle":false}""")
            }
        }
        assertEquals("720p", PlaybackRepository(transport).movie("example.org", film, voice).streams.single().label)
        assertTrue(called)
    }
}
