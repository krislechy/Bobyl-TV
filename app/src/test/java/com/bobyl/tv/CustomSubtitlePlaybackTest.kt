package com.bobyl.tv

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.bobyl.tv.data.CustomSubtitle
import com.bobyl.tv.data.LocalSubtitleServer
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CustomSubtitlePlaybackTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun track(name: String, bytes: ByteArray): CustomSubtitle {
        val uri = Uri.parse("content://subtitle-fixture/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return CustomSubtitle(name, uri)
    }

    @Test fun everyLocalTrackBecomesReadableHttpAndPreservesSelectedServerTrack() = runBlocking {
        val formats = listOf("Русский.srt", "English.ASS", "another.ssa", "captions.vtt")
        val contents = formats.map { "Original UTF-8 $it\nПривет!".toByteArray() }
        val tracks = formats.mapIndexed { index, name -> track(name, contents[index]) }
        var owned: LocalSubtitleServer? = null
        try {
            val prepared = prepareCustomSubtitlePlayback(context, tracks) { owned = it }
            assertEquals(formats, prepared.map { it.name })
            assertEquals(tracks.size, prepared.size)
            assertTrue(prepared.all { it.uri.scheme == "http" && it.uri.host == "127.0.0.1" })
            prepared.forEachIndexed { index, subtitle ->
                val connection = URL(subtitle.uri.toString()).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000
                    assertEquals(200, connection.responseCode)
                    assertArrayEquals(contents[index], connection.inputStream.use { it.readBytes() })
                } finally { connection.disconnect() }
            }
            val selected = Uri.parse("https://server.example/original.srt")
            val intent = Intent().apply {
                putExtra("subs", arrayOf<android.os.Parcelable>(selected))
                putExtra("subs.enable", arrayOf<android.os.Parcelable>(selected))
                putExtra("subs.name", arrayOf("Original"))
                putExtra("subs.filename", arrayOf("original.srt"))
            }
            appendCustomSubtitles(intent, prepared)
            assertEquals(listOf(selected) + prepared.map { it.uri }, intent.getParcelableArrayExtra("subs")!!.toList())
            assertEquals(listOf(selected), intent.getParcelableArrayExtra("subs.enable")!!.toList())
            assertEquals(listOf("original.srt") + formats, intent.getStringArrayExtra("subs.filename")!!.toList())
            assertEquals("Original", intent.getStringArrayExtra("subs.name")!!.first())
        } finally { owned?.close() }
    }

    @Test fun noTracksDoesNotStartOrAdoptAServer() = runBlocking {
        assertTrue(prepareCustomSubtitlePlayback(context, emptyList()) { error("No server expected") }.isEmpty())
    }

    @Test fun tooManyTracksFailsBeforeAdoption() = runBlocking {
        val tracks = List(33) { CustomSubtitle("$it.srt", Uri.parse("content://subtitle-fixture/$it")) }
        try {
            prepareCustomSubtitlePlayback(context, tracks) { error("Must not adopt an invalid set") }
            fail("The entire oversized set must fail explicitly")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("32")) }
    }

    @Test fun tooLargeTrackFailsBeforeAdoption() = runBlocking {
        val large = track("large.srt", ByteArray(4 * 1024 * 1024 + 1))
        try {
            prepareCustomSubtitlePlayback(context, listOf(large)) { error("Must not adopt an invalid set") }
            fail("Oversized track must fail explicitly")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("большие")) }
    }

    @Test fun adoptionFailureClosesTheActualSubtitleServer() = runBlocking {
        var created: LocalSubtitleServer? = null
        try {
            prepareCustomSubtitlePlayback(context, listOf(track("test.srt", byteArrayOf(1)))) {
                created = it
                error("adoption failed")
            }
            fail("Adoption must fail")
        } catch (expected: IllegalStateException) { assertEquals("adoption failed", expected.message) }
        val url = checkNotNull(created).urls.single()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            try { connection.inputStream.close(); fail("Unadopted server must be closed") }
            catch (_: java.io.IOException) { }
        } finally { connection.disconnect(); created?.close() }
    }
}
