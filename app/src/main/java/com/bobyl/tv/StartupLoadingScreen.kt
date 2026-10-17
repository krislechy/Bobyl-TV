package com.bobyl.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

private const val MinimumStartupDurationMillis = 2_000L

@Composable
internal fun StartupLoadingScreen(onMinimumElapsed: () -> Unit, modifier: Modifier = Modifier) {
    var imageLoaded by remember { mutableStateOf(false) }
    val currentOnMinimumElapsed by rememberUpdatedState(onMinimumElapsed)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    LaunchedEffect(imageLoaded) {
        if (imageLoaded) {
            // Count the minimum duration after the image reaches its first frame.
            withFrameNanos { }
            delay(MinimumStartupDurationMillis)
            currentOnMinimumElapsed()
        }
    }
    Box(modifier.fillMaxSize().background(Color(0xFF080F12))
        .focusRequester(focusRequester).focusable()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        }) {
        AsyncImage(
            model = R.drawable.bobyl_loading,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onSuccess = { imageLoaded = true },
            // A resource decoding failure must not trap the user at startup.
            onError = { imageLoaded = true },
        )
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)) {
            LoadingIndicator("Загружаем…")
        }
        Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_DATE} МСК",
            color = CinemaStyle.Muted, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp))
    }
}
