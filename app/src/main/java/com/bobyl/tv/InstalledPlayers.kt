package com.bobyl.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.SystemClock

private object PlayerCache {
    var packageName = ""
    var loadedAt = 0L
    var players: List<ResolveInfo>? = null
}

internal fun installedPlayers(context: Context, force: Boolean = false): List<ResolveInfo> = synchronized(PlayerCache) {
    PlayerCache.players?.takeIf {
        !force && PlayerCache.packageName == context.packageName && SystemClock.elapsedRealtime() - PlayerCache.loadedAt < 30_000L
    }?.let { return@synchronized it }
    val players = queryInstalledPlayers(context)
    PlayerCache.packageName = context.packageName
    PlayerCache.loadedAt = SystemClock.elapsedRealtime()
    PlayerCache.players = players
    players
}

private fun queryInstalledPlayers(context: Context): List<ResolveInfo> = listOf(
    "content://${context.packageName}.files/video.mp4", "https://example.invalid/video.mp4"
).flatMap { url -> context.packageManager.queryIntentActivities(
    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"), PackageManager.MATCH_DEFAULT_ONLY)
}.filterNot { it.activityInfo.packageName in listOf(context.packageName, "com.android.tv.frameworkpackagestubs") }
    .distinctBy { it.activityInfo.packageName }
