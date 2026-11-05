package com.bobyl.tv

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

internal val startupScreens = listOf("0" to "Главная", "1" to "Новинки", "6" to "Категории", "4" to "Поиск", "3" to "Избранное", "7" to "Продолжить", "8" to "Новые серии")

@Composable
internal fun PlaybackSettings(player: String, choosePlayer: (String) -> Unit, columns: Int, chooseColumns: (Int) -> Unit, provider: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var clearingCache by remember { mutableStateOf(false) }
    var players by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var playersLoading by remember { mutableStateOf(true) }
    var playersError by remember { mutableStateOf("") }
    LaunchedEffect(revision) {
        playersLoading = true
        playersError = ""
        try { players = installedPlayerChoices(context.applicationContext, force = revision > 0) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { playersError = "Не удалось получить список плееров" }
        finally { playersLoading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSection("Интерфейс") {
            var startup by remember { mutableStateOf(prefs.getInt("startupScreen", 0).toString()) }
            SettingsPair(first = {
                SettingsChoice("Фильмов в строке", (1..12).map { it.toString() to it.toString() }, columns.toString()) { chooseColumns(it.toInt()) }
            }, second = {
                SettingsChoice("Стартовый раздел", startupScreens, startup) {
                    startup = it
                    prefs.edit().putInt("startupScreen", it.toInt()).apply()
                }
            })
        }
        SettingsSection("Воспроизведение") {
            SettingsSingle {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        if (playersLoading && players.isEmpty()) SkeletonLoading(SkeletonLayout.Options, rows = 1, actions = 0)
                        else SettingsChoice("Плеер", listOf("" to "Спрашивать каждый раз", "system" to "Системный по умолчанию") + players, player, choose = choosePlayer)
                    }
                    FieldAction(FieldGlyph.Refresh, if (playersError.isBlank()) "Обновить список плееров" else "Повторить",
                        enabled = !playersLoading, loading = playersLoading, tooltip = true) { revision++ }
                }
            }
            if (!playersLoading && playersError.isBlank() && player.isNotBlank() && player != "system" && players.none { it.first == player }) {
                Text("Выбранный плеер не установлен", color = CinemaStyle.Error)
            }
            if (playersError.isNotBlank()) Text(playersError, color = CinemaStyle.Error)
            var quality by remember { mutableStateOf(prefs.getString("defaultQuality", "ask") ?: "ask") }
            var decoder by remember { mutableStateOf(prefs.getInt("mxDecoder", 0).toString()) }
            SettingsPair(first = {
                SettingsChoice("Качество", listOf("ask" to "Спрашивать", "max" to "Максимальное") + listOf("360p", "480p", "720p", "1080p", "1080p Ultra").map { it to it }, quality) {
                    quality = it
                    prefs.edit().putString("defaultQuality", it).apply()
                }
            }, second = {
                SettingsChoice("Декодер MX Player", listOf("0" to "Автоматически", "1" to "HW", "4" to "HW+", "2" to "SW"), decoder) {
                    decoder = it
                    prefs.edit().putInt("mxDecoder", it.toInt()).apply()
                }
            })
        }
        SettingsSection("Сериалы") {
            SettingsPair(first = {
                PreferenceToggle(prefs, "episodeRelay", "Список серий в MX Player", true)
            }, second = {
                PreferenceToggle(prefs, "autoNext", "Следующая серия после завершения", false)
            })
        }
        SettingsSection("Субтитры") {
            SettingsPair(first = {
                PreferenceToggle(prefs, "selectSubtitles", "Предлагать субтитры", true)
            }, second = {
                PreferenceToggle(prefs, "localSubtitles", "Скачивать перед запуском", true)
            })
            CustomSubtitleSettings()
        }
        SettingsSection("Кэш данных") {
            var cacheMinutes by remember { mutableStateOf(prefs.getInt("cacheMinutes", 60).toString()) }
            SettingsSingle {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        SettingsChoice("Срок хранения", listOf("0" to "Отключён", "15" to "15 минут", "30" to "30 минут", "60" to "1 час", "180" to "3 часа", "360" to "6 часов", "1440" to "24 часа"), cacheMinutes) {
                            cacheMinutes = it
                            prefs.edit().putInt("cacheMinutes", it.toInt()).apply()
                            com.bobyl.tv.data.PageCache.configure(it.toInt())
                        }
                    }
                    FieldAction(FieldGlyph.Delete, "Очистить кэш", enabled = !clearingCache, loading = clearingCache, tooltip = true) {
                        if (!clearingCache) {
                            clearingCache = true
                            message = ""
                            scope.launch {
                                try {
                                    com.bobyl.tv.data.PersistentCache.clearByUser()
                                    message = "Кэш очищен"
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { message = "Не удалось очистить кэш. Повторите попытку." }
                                finally { clearingCache = false }
                            }
                        }
                    }
                }
            }
        }
        SettingsSection("Загрузки") {
            SettingsSingle { PreferenceToggle(prefs, "externalDownload", "Внешний загрузчик видео", false) }
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
