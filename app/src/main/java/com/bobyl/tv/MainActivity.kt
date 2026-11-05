package com.bobyl.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.bobyl.tv.data.database.StorageState

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            try { com.bobyl.tv.data.CustomSubtitles(this@MainActivity).ensureDirectory() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                android.widget.Toast.makeText(this@MainActivity,
                    "Папка субтитров недоступна. Проверьте доступ к файлам в настройках Бобыля.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val storage = (application as BobylApplication).storage
        setContent {
            SkeletonLoadingTheme {
                val state by storage.state.collectAsState()
                StorageStartupGate(state, storage::start)
            }
        }
    }
}

/** Nothing beneath BobylTvApp (including its ViewModels) exists before the local database is ready. */
@Composable
private fun StorageStartupGate(state: StorageState, retry: () -> Unit) {
    var minimumElapsed by rememberSaveable { mutableStateOf(false) }
    var startupFinished by rememberSaveable { mutableStateOf(false) }
    var contentPrepared by remember { mutableStateOf(false) }
    val opacity = remember { Animatable(if (startupFinished) 0f else 1f) }
    val motionEnabled = cinemaMotionEnabled()
    LaunchedEffect(state, minimumElapsed, contentPrepared) {
        if (state == StorageState.Ready && contentPrepared && minimumElapsed && !startupFinished) {
            if (motionEnabled) opacity.animateTo(0f, tween(250, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)))
            else opacity.snapTo(0f)
            startupFinished = true
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (state == StorageState.Ready) {
            BobylTvApp(startupFinished = startupFinished, onStartupPrepared = { contentPrepared = true })
        }
        if (state != StorageState.Ready || !startupFinished) {
            StartupLoadingScreen(onMinimumElapsed = { minimumElapsed = true },
                modifier = Modifier.graphicsLayer { alpha = if (state == StorageState.Ready) opacity.value else 1f },
                error = state == StorageState.Failed, retry = retry)
        }
    }
}
