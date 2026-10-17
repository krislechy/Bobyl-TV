package com.bobyl.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.bobyl.tv.data.EpisodeRelay
import com.bobyl.tv.data.MediaTrack

/** Emulator-only manual smoke test. No credentials, provider requests, or history writes. */
class RelaySmokeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) return
        EpisodeRelayService.closeSession()
        val relay = EpisodeRelay(listOf("1", "2")) {
            Log.i("BobylRelaySmoke", "RESOLVE episode=$it")
            "http://10.0.2.2:18765/episode$it.mp4"
        }
        EpisodeRelayService.relay = relay
        androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, EpisodeRelayService::class.java))
        if (intent.getBooleanExtra("noPlayer", false)) {
            Log.i("BobylRelaySmoke", "READY ${relay.urls.getValue("1")}")
            setContentView(android.widget.TextView(this).apply { text = "Relay service smoke test" })
            return
        }
        val player = externalVideoIntent(this, MediaTrack("test", relay.urls.getValue("1")), null,
            "Relay episode 1", "com.mxtech.videoplayer.ad", decoder = 2)
            .putExtra("video_list", relay.episodes.map { Uri.parse(relay.urls.getValue(it)) }.toTypedArray())
            .putExtra("video_list.name", arrayOf("Relay episode 1", "Relay episode 2"))
            .putExtra("video_list_is_explicit", true)
        startActivityForResult(player, 42)
    }
    @Deprecated("Test fixture")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        Log.i("BobylRelaySmoke", "RESULT code=$result episode=${EpisodeRelayService.relay?.episodeFor(data?.data?.toString())} position=${data?.getIntExtra("position", -1)} end=${data?.getStringExtra("end_by")}")
        stopService(Intent(this, EpisodeRelayService::class.java))
        EpisodeRelayService.closeSession()
        finish()
    }
}
