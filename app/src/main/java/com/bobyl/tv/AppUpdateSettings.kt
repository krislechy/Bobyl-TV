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
internal fun AppUpdateSettings() {
    if (!GitHubAppUpdates.enabled) return
    val context = LocalContext.current
    val model: AppUpdateViewModel = viewModel()
    val state by model.state.collectAsState()
    var installError by remember { mutableStateOf("") }
    SettingsSection("Обновление приложения") {
        if (state.checking) LoadingIndicator("Проверяем обновления…")
        else if (state.downloading) LoadingIndicator("Загрузка: ${state.progress}%")
        else {
            CinematicButton("Проверить обновления", false) { installError = ""; model.check() }
            if (state.update != null && state.apk == null) CinematicButton("Скачать ${state.update?.versionName}", true) { model.download() }
            state.apk?.let { apk ->
                CinematicButton("Установить", true) {
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
        if (state.message.isNotBlank()) Text(state.message, color = CinemaStyle.Muted, fontSize = 14.sp)
        if (state.error.isNotBlank() || installError.isNotBlank()) Text(listOf(state.error, installError).filter { it.isNotBlank() }.joinToString("\n"),
            color = Color(0xFFFFB5B9), fontSize = 14.sp)
    }
}
