package com.bobyl.tv

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bobyl.tv.data.PageCache
import kotlinx.coroutines.delay

/** Re-check age on resume (including device sleep), without polling while the app is stopped. */
@Composable
internal fun rememberForegroundRefreshRevision(): Int {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle) {
        var refreshedAt = SystemClock.elapsedRealtime()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var resumed = true
            while (true) {
                val duration = PageCache.durationMillis()
                val now = SystemClock.elapsedRealtime()
                val remaining = duration - (now - refreshedAt)
                if ((duration > 0L && remaining <= 0L) || (duration == 0L && resumed)) {
                    PageCache.clear()
                    revision++
                    refreshedAt = now
                }
                resumed = false
                delay(if (duration == 0L) 60_000L else (duration - (SystemClock.elapsedRealtime() - refreshedAt)).coerceIn(1_000L, 60_000L))
            }
        }
    }
    return revision
}
