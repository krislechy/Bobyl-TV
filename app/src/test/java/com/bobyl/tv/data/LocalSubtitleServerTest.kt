package com.bobyl.tv.data

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class LocalSubtitleServerTest {
    @Test fun servesExactFilesAndHeadWithoutExposingOtherPaths() {
        val bytes = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nПривет\n".toByteArray()
        LocalSubtitleServer(listOf("Русский.vtt" to bytes, "English.srt" to "second".toByteArray())).use { server ->
            assertEquals("127.0.0.1", URL(server.urls[0]).host)
            fun request(url: String, method: String = "GET"): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = 2000; readTimeout = 2000
            }
            val get = request(server.urls[0])
            try { assertEquals(200, get.responseCode); assertArrayEquals(bytes, get.inputStream.readBytes()) } finally { get.disconnect() }
            val head = request(server.urls[0], "HEAD")
            try { assertEquals(200, head.responseCode); assertEquals(bytes.size, head.contentLength); assertEquals(0, head.inputStream.readBytes().size) } finally { head.disconnect() }
            val other = request(server.urls[1])
            try { assertEquals("second", other.inputStream.bufferedReader().readText()) } finally { other.disconnect() }
            val bad = request(server.urls[0].substringBeforeLast('/') + "/unknown.vtt")
            try { assertEquals(404, bad.responseCode) } finally { bad.disconnect() }
            val post = request(server.urls[0], "POST")
            try { assertEquals(405, post.responseCode) } finally { post.disconnect() }
        }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonSubtitleFiles() {
        LocalSubtitleServer(listOf("secret.txt" to byteArrayOf(1)))
    }
}
