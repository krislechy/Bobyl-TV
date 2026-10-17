package com.bobyl.tv.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MediaDownloads(private val context: Context) {
    fun enqueue(track: MediaTrack, title: String, subtitle: Boolean = false): Long {
        require(PlaybackParser.isMediaUrl(track.url))
        require(subtitle || !Uri.parse(track.url).path.orEmpty().endsWith(".m3u8", true)) {
            "Это HLS-поток. Выберите внешний загрузчик с поддержкой HLS."
        }
        if (android.os.Build.VERSION.SDK_INT < 29) check(androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Разрешите доступ к хранилищу в настройках Android или выберите внешний загрузчик"
        }
        val extension = Uri.parse(track.url).lastPathSegment?.substringAfterLast('.', "").orEmpty()
            .takeIf { it.matches(Regex("[a-zA-Z0-9]{2,5}")) } ?: if (subtitle) "srt" else "mp4"
        val name = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(90) + "-${System.currentTimeMillis()}.$extension"
        val request = DownloadManager.Request(Uri.parse(track.url)).setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Bobyl/$name")
        return (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
    fun external(track: MediaTrack) {
        require(PlaybackParser.isMediaUrl(track.url))
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(track.url))
            .setDataAndType(Uri.parse(track.url), "application/octet-stream"), "Выберите загрузчик"))
    }
    /** Limited-size download, no account headers, app-private file exposed by a scoped URI grant. */
    suspend fun subtitle(track: MediaTrack): Uri = runInterruptible(Dispatchers.IO) {
        require(PlaybackParser.isMediaUrl(track.url))
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7 * 86_400_000L }?.forEach { it.delete() }
        val id = MessageDigest.getInstance("SHA-256").digest(track.url.toByteArray()).joinToString("") { "%02x".format(it) }
        val extension = Uri.parse(track.url).path.orEmpty().substringAfterLast('.', "srt").takeIf { it in listOf("srt", "vtt", "ass", "ssa") } ?: "srt"
        val file = File(dir, "$id.$extension")
        if (!file.exists()) {
            val connection = URL(track.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000; connection.readTimeout = 20_000
            try {
                check(connection.responseCode in 200..299) { "Не удалось скачать субтитры" }
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 4 * 1024 * 1024) { "Файл субтитров слишком большой" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                check(bytes.size <= 4 * 1024 * 1024) { "Файл субтитров слишком большой" }
                file.writeBytes(bytes)
            } finally { connection.disconnect() }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
