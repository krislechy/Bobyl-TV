package com.bobyl.tv

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Text
import com.bobyl.tv.data.CustomSubtitles
import kotlinx.coroutines.launch

@Composable
internal fun CustomSubtitleSettings() {
    val context = LocalContext.current
    val storage = remember { CustomSubtitles(context) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var access by remember { mutableStateOf(storage.hasAccess()) }
    fun refresh() {
        access = storage.hasAccess()
        scope.launch {
            status = try { storage.ensureDirectory(); "" }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) { e.message ?: "Папка недоступна" }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    LaunchedEffect(Unit) { refresh() }
    Text("Дополнительные субтитры", color = CinemaStyle.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    Text(CustomSubtitles.RELATIVE_PATH, color = CinemaStyle.Muted)
    Text("films/ID/ · SRT, ASS, SSA, VTT", color = CinemaStyle.Muted, fontSize = 13.sp)
    if (status.isNotBlank()) Text(status, color = if (access) CinemaStyle.Muted else CinemaStyle.Error)
    if (!access) CinematicButton("Разрешить доступ к файлам", false) {
        if (Build.VERSION.SDK_INT >= 30) {
            try { settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) {
                try { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (_: Exception) { status = "Системный экран доступа недоступен. Разрешение потребуется выдать отдельно." }
            }
        } else permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    if (!access) Text("Для чтения папки субтитров Android запросит доступ ко всем файлам.", color = CinemaStyle.Muted, fontSize = 13.sp)
    CinematicButton("Проверить папку", false) { refresh() }
}
