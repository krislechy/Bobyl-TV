package com.bobyl.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AuthContract {
    fun registrationFields(email: String, username: String, password: String) = mapOf("data" to
        listOf("email" to email.trim(), "prevent_autofill_name" to "", "name" to username.trim(), "prevent_autofill_password1" to "",
            "password1" to password, "rules" to "1", "submit_reg" to "submit_reg", "do" to "register")
            .joinToString("&") { (k, v) -> "$k=${encode(v)}" })
    val cookieNames = listOf("dle_user_id", "dle_password", "PHPSESSID")
    fun fields(name: String, password: String) = mapOf(
        "login_name" to name.trim(), "login_password" to password, "login_not_save" to "0",
    )
    fun validateResponse(body: String, cookies: Map<String, String>) {
        val json = try { JSONObject(body) } catch (_: Exception) {
            throw IllegalStateException("Сайт вернул неожиданный ответ при входе")
        }
        if (!json.optBoolean("success")) throw IllegalArgumentException(
            Jsoup.parse(json.optString("message", "Не удалось войти. Проверьте логин и пароль.")).text().take(400),
        )
        require(listOf("dle_user_id", "dle_password").all { !cookies[it].isNullOrBlank() && cookies[it] != "deleted" }) {
            "Сайт подтвердил вход, но не выдал cookies сессии"
        }
        require(cookies.filterKeys { it in cookieNames }.values.all { value -> value.none { it == ';' || it == '\r' || it == '\n' } }) {
            "Сайт выдал некорректную сессию"
        }
    }
}

/** Origin-scoped session tokens, encrypted with a non-exportable Android Keystore key.
 * No plaintext password, cookie values, or request body is logged or backed up.
 */
class AuthRepository(context: Context, private val transport: HtmlTransport = HtmlTransport()) {
    private val directory = context.applicationContext.noBackupFilesDir
    private val keyAlias = "bobyl.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun file(origin: String): File {
        val id = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "session-$id.bin")
    }
    private fun applyCookies(origin: String, values: JSONObject) {
        val manager = CookieManager.getInstance()
        AuthContract.cookieNames.forEach { name ->
            val value = values.optString(name)
            if (value.isNotBlank() && value.none { it == ';' || it == '\r' || it == '\n' }) {
                manager.setCookie(origin, "$name=$value; Path=/; Secure; HttpOnly")
            }
        }
        manager.flush()
    }
    suspend fun restore(provider: String): String? = runInterruptible(Dispatchers.IO) {
        val origin = CatalogUrls.provider(provider)
        val saved = file(origin)
        if (!saved.exists()) return@runInterruptible null
        val data = saved.readBytes()
        require(data.size > 28) { "Сохранённая сессия повреждена. Войдите заново." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(origin.toByteArray())
        val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        applyCookies(origin, json.getJSONObject("cookies"))
        json.getString("username")
    }
    suspend fun login(provider: String, username: String, password: String): String {
        require(username.isNotBlank() && password.isNotEmpty()) { "Введите логин и пароль" }
        val origin = CatalogUrls.provider(provider)
        val response = transport.postResponse(origin + "ajax/login/", AuthContract.fields(username, password), login = true)
        require(response.statusCode() in 200..299) { "Сайт перенаправил вход. Проверьте адрес зеркала." }
        AuthContract.validateResponse(response.body(), response.cookies())
        return saveSession(origin, username, response.cookies())
    }
    suspend fun register(provider: String, email: String, username: String, password: String): String {
        require(email.contains('@') && username.isNotBlank() && password.isNotBlank()) { "Заполните email, логин и пароль" }
        val origin = CatalogUrls.provider(provider)
        val response = transport.postResponse(origin + "engine/ajax/quick_register.php", AuthContract.registrationFields(email, username, password), login = true)
        val script = response.parse().select("script").joinToString("\n") { it.data() }
        val success = (script.contains("location") || script.isBlank()) && listOf("dle_user_id", "dle_password").all { !response.cookie(it).isNullOrBlank() }
        check(success) {
            val errors = Regex("\\.html\\('([\\s\\S]*?)'\\)").find(script)?.groupValues?.get(1).orEmpty()
            Jsoup.parse(errors).text().take(500).ifBlank { "Регистрация не подтверждена. Возможно, требуется проверка на сайте." }
        }
        AuthContract.validateResponse("{\"success\":true}", response.cookies())
        return saveSession(origin, username, response.cookies())
    }
    private suspend fun saveSession(origin: String, username: String, cookies: Map<String, String>): String = runInterruptible(Dispatchers.IO) {
            PageCache.clear()
            val values = JSONObject(cookies.filterKeys { it in AuthContract.cookieNames })
            val json = JSONObject().put("username", username.trim()).put("cookies", values)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(origin.toByteArray())
            val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val atomic = android.util.AtomicFile(file(origin))
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
            applyCookies(origin, values)
            username.trim()
    }
    suspend fun profile(provider: String): AccountProfile {
        val origin = CatalogUrls.provider(provider)
        val id = runInterruptible(Dispatchers.IO) {
            CookieManager.getInstance().getCookie(origin).orEmpty().split(';').map { it.trim().split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "dle_user_id" }?.get(1).orEmpty()
        }
        check(id.matches(Regex("[0-9]+"))) { "Войдите в учётную запись" }
        val doc = transport.get(origin + "user/$id/")
        return withContext(Dispatchers.Default) { AccountParser.parse(doc) }
    }
    suspend fun logout(provider: String) = runInterruptible(Dispatchers.IO) {
        PageCache.clear()
        val origin = CatalogUrls.provider(provider)
        val manager = CookieManager.getInstance()
        AuthContract.cookieNames.forEach { manager.setCookie(origin, "$it=; Max-Age=0; Path=/; Secure; HttpOnly") }
        manager.flush()
        val saved = file(origin)
        android.util.AtomicFile(saved).delete()
        check(!saved.exists()) { "Не удалось удалить сохранённую сессию" }
    }
}
