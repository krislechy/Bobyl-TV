package com.bobyl.tv

import android.app.*
import android.content.Intent
import android.os.*
import com.bobyl.tv.data.EpisodeRelay

/** Internal component, alive only during an external playback session. */
class EpisodeRelayService : Service() {
    companion object {
        var relay: EpisodeRelay? = null
        var subtitleServer: com.bobyl.tv.data.LocalSubtitleServer? = null
        fun closeSession() { relay?.close(); relay = null; subtitleServer?.close(); subtitleServer = null }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { stopSelf() }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || (relay == null && subtitleServer == null)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("episode-relay", "Плейлист MX", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, EpisodeRelayService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "episode-relay") else Notification.Builder(this)
        startForeground(82, builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Бобыль • локальный HTTP")
            .setContentText(if (subtitleServer != null) "Дополнительные субтитры доступны плееру" else "Следующая серия загружается по запросу плеера")
            .setOngoing(true).addAction(android.R.drawable.ic_media_pause, "Остановить", stop).build())
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, 6 * 60 * 60 * 1000L)
        return START_NOT_STICKY
    }
    override fun onDestroy() { handler.removeCallbacks(expire); closeSession(); super.onDestroy() }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }
}
