package com.bobyl.tv.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.bobyl.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class ProductionUpdate(val versionCode: Long, val versionName: String, val url: String,
    val sha256: String, val size: Long)

/** Public GitHub requests are isolated from the catalogue's cookies and account transport. */
internal class GitHubAppUpdates(private val context: Context) {
    companion object {
        const val REPOSITORY = "krislechy/Bobyl-TV"
        val enabled: Boolean get() = BuildConfig.APPLICATION_ID == "com.bobyl.tv" && !BuildConfig.DEBUG
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
    }

    private fun connect(url: String): HttpURLConnection {
        check(URL(url).protocol == "https")
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Bobyl-TV/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun readJson(url: String, missingAllowed: Boolean = false): JSONObject? {
        val connection = connect(url)
        try {
            if (connection.responseCode == 404 && missingAllowed) return null
            check(connection.responseCode == 200) { "GitHub недоступен (HTTP ${connection.responseCode}). Повторите позже." }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1024 * 1024) { "Слишком большой ответ GitHub" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    suspend fun latest(): ProductionUpdate? = runInterruptible(Dispatchers.IO) {
        check(enabled)
        val release = readJson("https://api.github.com/repos/$REPOSITORY/releases/latest", missingAllowed = true)
            ?: return@runInterruptible null
        check(!release.optBoolean("draft") && !release.optBoolean("prerelease"))
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == name } ?: error("В релизе нет файла $name")
        fun downloadUrl(asset: JSONObject): String = asset.getString("browser_download_url").also {
            check(it.startsWith("https://github.com/$REPOSITORY/releases/download/")) { "Неожиданный адрес обновления" }
        }
        val metadata = readJson(downloadUrl(asset("bobyl-tv-update.json"))) ?: error("Нет данных обновления")
        check(metadata.getString("packageName") == "com.bobyl.tv")
        val version = metadata.getLong("versionCode")
        if (version <= BuildConfig.VERSION_CODE) return@runInterruptible null
        val apk = asset(metadata.getString("apk"))
        val hash = metadata.getString("sha256").lowercase()
        check(hash.matches(Regex("[0-9a-f]{64}"))) { "Некорректная контрольная сумма обновления" }
        val size = apk.getLong("size")
        check(size in 1..MAX_APK_BYTES)
        ProductionUpdate(version, metadata.getString("versionName"), downloadUrl(apk), hash, size)
    }

    suspend fun download(update: ProductionUpdate, progress: (Int) -> Unit): File = runInterruptible(Dispatchers.IO) {
        check(enabled)
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "production.apk.part")
        val result = File(directory, "production.apk")
        val connection = connect(update.url)
        try {
            check(connection.responseCode == 200) { "Не удалось скачать обновление (HTTP ${connection.responseCode})" }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastProgress = -1
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    check(received <= update.size && received <= MAX_APK_BYTES) { "Некорректный размер APK" }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    val percent = (received * 100 / update.size).toInt()
                    if (percent != lastProgress) { lastProgress = percent; progress(percent) }
                }
            } }
            check(received == update.size && digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) {
                "Файл обновления повреждён. Скачайте его повторно."
            }
            verifyApk(partial, update.versionCode)
            check(!result.exists() || result.delete())
            check(partial.renameTo(result)) { "Не удалось сохранить APK" }
            result
        } finally { partial.delete(); connection.disconnect() }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, expectedVersion: Long) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Неверный APK")
        check(archive.packageName == "com.bobyl.tv" && archive.packageName == installed.packageName) { "APK другого приложения" }
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(version == expectedVersion && version > BuildConfig.VERSION_CODE) { "APK не является новой версией" }
        check((archive.applicationInfo?.flags ?: ApplicationInfo.FLAG_DEBUGGABLE) and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "Отладочная сборка не подходит для обновления"
        }
        val currentSigners = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val newSigners = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        check(!currentSigners.isNullOrEmpty() && !newSigners.isNullOrEmpty() && currentSigners.toSet() == newSigners.toSet()) {
            "Подпись APK отличается от установленного приложения"
        }
    }
}
