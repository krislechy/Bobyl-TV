package com.bobyl.tv.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

data class CustomSubtitle(val name: String, val uri: Uri)

class CustomSubtitles(private val context: Context) {
    companion object {
        const val RELATIVE_PATH = "Documents/Bobyl/Subtitles"
        fun supported(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("srt", "ass", "ssa", "vtt")
        fun validId(id: String) = id.matches(Regex("[0-9]+"))
    }
    @Suppress("DEPRECATION")
    val root: File get() = File(Environment.getExternalStorageDirectory(), RELATIVE_PATH)
    fun hasAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    suspend fun ensureDirectory(): File = runInterruptible(Dispatchers.IO) {
        check(hasAccess()) { "Разрешите доступ к файлам для дополнительных субтитров в настройках Бобыля" }
        val directory = File(root, "films")
        check(directory.isDirectory || directory.mkdirs()) { "Не удалось создать $RELATIVE_PATH/films" }
        directory
    }
    suspend fun forFilm(id: String): List<CustomSubtitle> {
        if (!validId(id)) return emptyList()
        val films = ensureDirectory()
        return runInterruptible(Dispatchers.IO) {
            val film = File(films, id)
            if (!film.exists()) return@runInterruptible emptyList()
            check(film.canonicalFile.parentFile == films.canonicalFile) { "Недопустимый путь субтитров" }
            val files = checkNotNull(film.listFiles()) { "Не удалось прочитать папку субтитров фильма" }
            files.filter { it.isFile && supported(it.name) && it.canRead() && it.canonicalFile.parentFile == film.canonicalFile }
                .sortedBy { it.name.lowercase() }.map {
                    CustomSubtitle(it.name, FileProvider.getUriForFile(context, "${context.packageName}.files", it))
                }
        }
    }
}
