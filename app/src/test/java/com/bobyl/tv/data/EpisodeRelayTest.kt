package com.bobyl.tv.data

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class EpisodeRelayTest {
    @Test fun adjacentSubtitlesAreEpisodeScopedLazyAndCachedAcrossProbes() {
        val calls = mutableListOf<String>()
        EpisodeRelay(listOf("1", "2", "3"), resolveSubtitle = {
            calls.add(it)
            when (it) { "1" -> "https://example.org/one.srt"; "2" -> "https://example.org/two.ass"; else -> null }
        }) { "https://example.org/$it.mp4" }.use { relay ->
            assertTrue(calls.isEmpty())
            val first = relay.subtitleUrl("1", "https://example.org/one.srt")
            assertEquals(307 to "https://example.org/one.srt", request(first))
            assertEquals(307, request(first, "HEAD").first)
            val second = relay.urls.getValue("2").removeSuffix("mp4")
            assertEquals(404, request(second + "srt").first)
            assertEquals(307 to "https://example.org/two.ass", request(second + "ass"))
            assertEquals(listOf("1", "2"), calls)
            val third = relay.urls.getValue("3").removeSuffix("mp4")
            assertEquals(404, request(third + "srt").first)
            assertEquals(404, request(third + "ass").first)
            assertEquals(listOf("1", "2", "3"), calls)
            assertNull(relay.episodeFor(first)) // subtitle prefetch isn't playback progress
        }
    }
    @Test fun noSubtitleSelectionNeverResolvesVideoForSubtitleProbes() {
        EpisodeRelay(listOf("1")) { error("Must not resolve video") }.use {
            assertEquals(404, request(it.urls.getValue("1").replace("mp4", "srt")).first)
        }
    }
    @Test fun unsafeSubtitleRedirectIsRejected() {
        EpisodeRelay(listOf("1"), resolveSubtitle = { "https://example.org/a.srt\r\nInjected: yes" }) {
            "https://example.org/a.mp4"
        }.use {
            assertEquals(502, request(it.urls.getValue("1").replace("mp4", "srt")).first)
        }
    }
    private fun request(url: String, method: String = "GET"): Pair<Int, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.connectTimeout = 2000; connection.readTimeout = 2000
        return try { connection.responseCode to connection.getHeaderField("Location") } finally { connection.disconnect() }
    }
    @Test fun resolvesOnlyRequestedEpisodeAndCoalescesHeadGet() {
        val calls = mutableListOf<String>()
        EpisodeRelay(listOf("1", "2", "3")) { calls.add(it); "https://example.org/$it.mp4" }.use { relay ->
            assertTrue(calls.isEmpty())
            assertEquals(307, request(relay.urls.getValue("2"), "HEAD").first)
            assertEquals(307 to "https://example.org/2.mp4", request(relay.urls.getValue("2")))
            assertEquals(listOf("2"), calls)
            assertEquals("2", relay.episodeFor("https://example.org/2.mp4"))
            assertEquals("1", relay.episodeFor(relay.urls.getValue("1")))
            assertNull(relay.episodeFor("https://unknown.example/"))
            assertEquals(404, request(relay.urls.getValue("1").replace("/1.mp4", "/999.mp4")).first)
            assertEquals(405, request(relay.urls.getValue("1"), "POST").first)
            assertEquals(listOf("2"), calls)
        }
    }
    @Test fun invalidDestinationNeverBecomesRedirect() {
        EpisodeRelay(listOf("1")) { "https://example.org/video\r\nInjected: yes" }.use {
            assertEquals(502, request(it.urls.getValue("1")).first)
        }
    }
    @Test fun resolverFailureReturnsErrorWithoutDetails() {
        EpisodeRelay(listOf("1")) { error("private session detail") }.use {
            assertEquals(502 to null, request(it.urls.getValue("1")))
        }
    }
}
