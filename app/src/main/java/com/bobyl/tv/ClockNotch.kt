package com.bobyl.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An overlay sibling of the content row: it never participates in screen layout or focus. */
@Composable
internal fun ClockNotch(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Date()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = Date()
                delay(1_000L)
            }
        }
    }
    val shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    Row(modifier.width(176.dp).background(CinemaStyle.Rail.copy(alpha = .35f), shape)
        .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        Text(timeFormat.format(now), color = CinemaStyle.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
        Text(dateFormat.format(now), color = CinemaStyle.Muted, fontSize = 12.sp,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
    }
}
