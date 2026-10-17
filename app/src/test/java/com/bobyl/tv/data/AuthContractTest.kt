package com.bobyl.tv.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class AuthContractTest {
    @Test fun loginFieldsMatchSmali() {
        assertEquals(mapOf("login_name" to "user", "login_password" to " p&=+ ", "login_not_save" to "0"), AuthContract.fields(" user ", " p&=+ "))
    }
    @Test fun acceptsConfirmedSession() {
        AuthContract.validateResponse("""{"success":true}""", mapOf("dle_user_id" to "42", "dle_password" to "test-token"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingSessionCookies() {
        AuthContract.validateResponse("""{"success":true}""", emptyMap())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsFailedLogin() {
        AuthContract.validateResponse("""{"success":false,"message":"Неверный пароль"}""", emptyMap())
    }
    @Test(expected = IllegalStateException::class) fun rejectsHtmlChallengeAsSuccess() {
        AuthContract.validateResponse("<html>Login</html>", emptyMap())
    }
    @Test fun newsUsesPostAndNoSecondPage() = runBlocking {
        var calls = 0
        val repository = CatalogRepository(object : PageTransport {
            override suspend fun get(url: String, details: Boolean): Document = error("Must use POST")
            override suspend fun post(url: String, form: Map<String, String>): Document {
                calls++
                assertEquals("https://hdrzk.org/engine/ajax/get_newest_slider_content.php", url)
                assertEquals(mapOf("id" to "0"), form)
                return Jsoup.parse("")
            }
        })
        repository.load("hdrzk.org", CatalogSection.Slider, 1, "")
        repository.load("hdrzk.org", CatalogSection.Slider, 2, "")
        assertEquals(1, calls)
    }
}
