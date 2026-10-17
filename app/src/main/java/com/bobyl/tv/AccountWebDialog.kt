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

/** Website-only account flows. No JS bridge, no file access, no forwarded credential headers. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AccountWebDialog(url: String, dismiss: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    CinemaModal("Учётная запись на сайте", dismiss) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
        if (loading) Box(Modifier.fillMaxSize().background(CinemaStyle.Background).padding(16.dp)) {
            SkeletonLoading(SkeletonLayout.Text, rows = 4)
        }
        }
        CinematicButton("Предыдущая страница", false) { web?.let { if (it.canGoBack()) it.goBack() } }
    }
    DisposableEffect(Unit) { onDispose { web?.stopLoading(); web?.destroy(); web = null } }
}
