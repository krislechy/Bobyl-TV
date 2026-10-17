package com.bobyl.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text

@Composable
internal fun HistoryCleanupSettings(provider: String) {
    val model: HistoryCleanupViewModel = viewModel(key = "history-cleanup-$provider")
    val state by model.state.collectAsState()
    var expanded by remember(provider) { mutableStateOf(false) }
    CinematicButton("Очистить список «Продолжить»", false) { if (!state.busy) expanded = true }
    if (state.busy) LoadingIndicator(if (state.total > 0) "Удалено ${state.removed} из ${state.total}" else "Подготавливаем очистку…")
    if (state.message.isNotBlank()) Text(state.message, color = CinemaStyle.Muted, fontSize = 14.sp)
    if (expanded) Dialog({ expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().clip(RoundedCornerShape(CinemaStyle.PanelCorner))
            .background(CinemaStyle.Background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Очистить «Продолжить»", color = CinemaStyle.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            CinematicButton("Очистить в аккаунте", false, Modifier.fillMaxWidth()) {
                expanded = false
                model.clear(provider, HistoryCleanupTarget.Account)
            }
            CinematicButton("Очистить на устройстве", false, Modifier.fillMaxWidth()) {
                expanded = false
                model.clear(provider, HistoryCleanupTarget.Device)
            }
            CinematicButton("Назад", false, Modifier.fillMaxWidth()) { expanded = false }
        }
    }
}
