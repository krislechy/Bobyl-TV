package com.bobyl.tv

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal val startupScreens = listOf("0" to "Главная", "1" to "Новинки", "6" to "Категории", "4" to "Поиск", "3" to "Избранное", "7" to "Продолжить", "8" to "Новые серии")

@Composable
internal fun PlaybackSettings(player: String, choosePlayer: (String) -> Unit, columns: Int, chooseColumns: (Int) -> Unit, provider: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val players = remember(revision) {
        installedPlayers(context, force = revision > 0).map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSection("Интерфейс") {
            SettingsChoice("Фильмов в строке", (1..12).map { it.toString() to it.toString() }, columns.toString()) { chooseColumns(it.toInt()) }
            var startup by remember { mutableStateOf(prefs.getInt("startupScreen", 0).toString()) }
            SettingsChoice("Стартовый раздел", startupScreens, startup) {
                startup = it
                prefs.edit().putInt("startupScreen", it.toInt()).apply()
            }
        }
        SettingsSection("Воспроизведение") {
            SettingsChoice("Плеер", listOf("" to "Спрашивать каждый раз", "system" to "Системный по умолчанию") + players, player, choose = choosePlayer)
            if (player.isNotBlank() && player != "system" && players.none { it.first == player }) {
                Text("Выбранный плеер не установлен", color = Color(0xFFFFB5B9))
            }
            CinematicButton("Обновить список плееров", false) { revision++ }
            var quality by remember { mutableStateOf(prefs.getString("defaultQuality", "ask") ?: "ask") }
            SettingsChoice("Качество", listOf("ask" to "Спрашивать", "max" to "Максимальное") + listOf("360p", "480p", "720p", "1080p", "1080p Ultra").map { it to it }, quality) {
                quality = it
                prefs.edit().putString("defaultQuality", it).apply()
            }
            var decoder by remember { mutableStateOf(prefs.getInt("mxDecoder", 0).toString()) }
            SettingsChoice("Декодер MX Player", listOf("0" to "Автоматически", "1" to "HW", "4" to "HW+", "2" to "SW"), decoder) {
                decoder = it
                prefs.edit().putInt("mxDecoder", it.toInt()).apply()
            }
        }
        SettingsSection("Сериалы") {
            PreferenceToggle(prefs, "episodeRelay", "Список серий в MX Player", true)
            PreferenceToggle(prefs, "autoNext", "Следующая серия после завершения", false)
        }
        SettingsSection("Субтитры") {
            PreferenceToggle(prefs, "selectSubtitles", "Предлагать субтитры", true)
            PreferenceToggle(prefs, "localSubtitles", "Скачивать перед запуском", true)
            CustomSubtitleSettings()
        }
        SettingsSection("Кэш данных") {
            var cacheMinutes by remember { mutableStateOf(prefs.getInt("cacheMinutes", 60).toString()) }
            SettingsChoice("Срок хранения", listOf("0" to "Отключён", "15" to "15 минут", "30" to "30 минут", "60" to "1 час", "180" to "3 часа", "360" to "6 часов", "1440" to "24 часа"), cacheMinutes) {
                cacheMinutes = it
                prefs.edit().putInt("cacheMinutes", it.toInt()).apply()
                com.bobyl.tv.data.PageCache.configure(it.toInt())
            }
            CinematicButton("Очистить кэш", false) {
                com.bobyl.tv.data.PageCache.clear()
                message = "Кэш очищен"
            }
        }
        SettingsSection("Загрузки") {
            PreferenceToggle(prefs, "externalDownload", "Внешний загрузчик видео", false)
            CinematicButton("Открыть загрузки Android", false) {
                try { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                catch (_: Exception) { message = "На устройстве нет экрана загрузок" }
            }
        }
        SettingsSection("О приложении") {
            CinematicButton("Поделиться отчётом об ошибке", false) {
                try {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Бобыль — сообщение об ошибке")
                        .putExtra(Intent.EXTRA_TEXT, "Опишите проблему:\n\nБобыль ${BuildConfig.VERSION_NAME}\nСборка: ${BuildConfig.BUILD_DATE} МСК\nAndroid ${android.os.Build.VERSION.RELEASE}\nУстройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"), "Выберите получателя"))
                } catch (_: Exception) { message = "На устройстве нет приложения для отправки отчёта" }
            }
            Text("Бобыль ${BuildConfig.VERSION_NAME}\nСборка от ${BuildConfig.BUILD_DATE} МСК", color = CinemaStyle.Muted, fontSize = 14.sp)
        }
        if (message.isNotBlank()) Text(message, color = CinemaStyle.Muted)
    }
}

@Composable
private fun PreferenceToggle(prefs: android.content.SharedPreferences, key: String, label: String, default: Boolean) {
    var checked by remember(prefs, key) { mutableStateOf(prefs.getBoolean(key, default)) }
    SettingsToggle(label, checked) {
        checked = !checked
        prefs.edit().putBoolean(key, checked).apply()
    }
}
