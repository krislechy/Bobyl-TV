package com.bobyl.tv

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.bobyl.tv.data.PlaybackParser
import kotlinx.coroutines.launch

/** Website-only account flows. No JS bridge, no file access, no forwarded credential headers. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AccountWebDialog(url: String, dismiss: () -> Unit) {
    val actions = LocalFilmActions.current
    val scope = rememberCoroutineScope()
    val origin = remember(url) { java.net.URI(url).let { "${it.scheme}://${it.rawAuthority}/" } }
    val holder = remember(url) { arrayOfNulls<AutoCloseable>(1) }
    var ready by remember(url) { mutableStateOf(false) }
    var closing by remember(url) { mutableStateOf(false) }
    var openingError by remember(url) { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    LaunchedEffect(url) {
        try {
            holder[0] = com.bobyl.tv.data.AccountSessionGuard.shared.openWebsite(origin) {
                com.bobyl.tv.data.AccountSessionGuard.credentials(android.webkit.CookieManager.getInstance().getCookie(origin).orEmpty())
            }
            ready = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { openingError = com.bobyl.tv.data.networkErrorMessage(failure) }
    }
    val closeDialog: () -> Unit = {
        if (!closing) {
            closing = true
            val view = web
            web = null
            ready = false
            view?.stopLoading()
            view?.destroy()
            val lease = holder[0]
            holder[0] = null
            val cleanup = actions.release(lease)
            scope.launch { cleanup?.join(); dismiss() }
        }
    }
    CinemaModal("Учётная запись на сайте", closeDialog) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (ready) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                    override fun onPageFinished(view: WebView, url: String?) { loading = false }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) loading = false
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) loading = false
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.scheme != "https" || !PlaybackParser.isMediaUrl(request.url.toString())
                }
                loadUrl(url); web = this
            }
        })
        }
        if (openingError.isNotBlank()) androidx.tv.material3.Text(openingError, color = CinemaStyle.Error)
        else if (!ready || loading) Box(Modifier.fillMaxSize().background(CinemaStyle.Background).padding(16.dp)) {
            SkeletonLoading(SkeletonLayout.Text, rows = 4)
        }
        }
        CinematicButton("Предыдущая страница", false) { web?.let { if (it.canGoBack()) it.goBack() } }
    }
    DisposableEffect(holder) { onDispose {
        web?.stopLoading(); web?.destroy(); web = null
        val lease = holder[0]; holder[0] = null
        actions.release(lease)
    } }
}
