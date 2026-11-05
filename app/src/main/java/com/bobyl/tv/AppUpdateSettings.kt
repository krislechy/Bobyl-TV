package com.bobyl.tv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.bobyl.tv.data.GitHubAppUpdates

@Composable
internal fun AppUpdateSettings(first: Boolean = false) {
    if (!GitHubAppUpdates.enabled) return
    val context = LocalContext.current
    val model: AppUpdateViewModel = viewModel()
    val state by model.state.collectAsState()
    var installError by remember { mutableStateOf("") }
    SettingsSection("Обновление приложения", first = first) {
        val busy = state.checking || state.downloading
        // Keep the same focus targets alive while work runs; disabling/removing them sends TV focus to the rail.
        CinematicButton("Проверить обновления", false) {
            if (!busy) { installError = ""; model.check() }
        }
        if (state.checking) LoadingIndicator("Проверяем обновления…")
        if (state.update != null) {
            val apk = state.apk
            CinematicButton(if (apk == null) "Скачать ${state.update?.versionName}" else "Установить", true) {
                if (!busy && apk == null) model.download()
                else if (!busy && apk != null) {
                    installError = ""
                    try {
                        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                            installError = "Разрешите установку из этого источника, затем нажмите «Установить» ещё раз."
                        } else {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    } catch (_: Exception) { installError = "Не удалось открыть установщик Android. Проверьте разрешение установки приложений." }
                }
            }
        }
        if (state.downloading) LoadingIndicator("Загрузка: ${state.progress}%")
        if (state.message.isNotBlank()) Text(state.message, color = CinemaStyle.Muted, fontSize = 14.sp)
        if (state.error.isNotBlank() || installError.isNotBlank()) Text(listOf(state.error, installError).filter { it.isNotBlank() }.joinToString("\n"),
            color = CinemaStyle.Error, fontSize = 14.sp)
    }
}
