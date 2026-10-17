package com.bobyl.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.bobyl.tv.data.*
import org.jsoup.Jsoup

/** Default scenarios avoid account mutation; history-sync is an explicit opt-in live check. */
class ParityInstrumentation : Instrumentation() {
    private var uiLoadingOnly = false
    private var historySyncOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        uiLoadingOnly = arguments?.getString("scenario") == "ui-loading"
        historySyncOnly = arguments?.getString("scenario") == "history-sync"
        start()
    }
    override fun onStart() {
        if (historySyncOnly) { HistorySyncChecks(this).run(); return }
        if (uiLoadingOnly) { UiLoadingChecks(this).run(); return }
        val results = StringBuilder()
        var failures = 0
        fun test(name: String, run: () -> Unit) {
            try { run(); results.append("PASS $name\n") }
            catch (failure: Throwable) { failures++; results.append("FAIL $name: ${failure.javaClass.simpleName}\n") }
        }
        test("Android history persistence and removal") {
            val history = WatchHistory(targetContext, "https://bobyl-test.invalid")
            try {
                history.put(WatchRecord("987654321", "https://bobyl-test.invalid/film", "Fixture", "", "1", "2", "3", 123000))
                check(history.find("987654321")?.positionMs == 123000L)
                check(WatchHistory(targetContext, "https://bobyl-test.invalid").find("987654321")?.episode == "3")
            } finally { history.remove("987654321") }
            check(history.find("987654321") == null)
        }
        test("Android external player contract and subtitle URI grant") {
            val subtitle = Uri.parse("content://${targetContext.packageName}.files/subtitles/test.srt")
            val intent = externalVideoIntent(targetContext, MediaTrack("720p", "https://example.invalid/video.mp4"),
                MediaTrack("Русский", "https://example.invalid/sub.srt"), "Test", context.packageName, 123000, subtitle)
            check(intent.getIntExtra("position", -1) == 123000)
            check(intent.getBooleanExtra("return_result", false))
            check(intent.clipData?.getItemAt(0)?.uri == subtitle)
            check(intent.getParcelableArrayExtra("subs")?.size == 1)
            check(!intent.hasExtra("headers") && !intent.hasExtra("Cookie"))
        }
        test("Chooser is explicit, system default is separate") {
            val track = MediaTrack("720p", "https://example.invalid/video.mp4")
            check(externalVideoIntent(targetContext, track, null, "Test", "").action == Intent.ACTION_CHOOSER)
            check(externalVideoIntent(targetContext, track, null, "Test", "system").action == Intent.ACTION_VIEW)
        }
        test("Season playlist survives direct, system and chooser launches with and without subtitles") {
            for (subtitle in listOf(null, MediaTrack("Русский", "https://example.invalid/sub.srt"))) {
                com.bobyl.tv.data.EpisodeRelay(listOf("1", "2", "3")) { "https://example.invalid/$it.mp4" }.use { relay ->
                    fun target(pkg: String) = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse("https://example.invalid/2.mp4"), "video/*")
                        .setPackage(pkg).putExtra("position", 123000)
                    val mx = target("com.mxtech.videoplayer.ad")
                    val pro = target("com.mxtech.videoplayer.pro")
                    val other = target("org.example.player")
                    val chooser = Intent.createChooser(other, "Player")
                        .putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(mx, pro))
                    for (launch in listOf(target("com.mxtech.videoplayer.ad"), chooser)) {
                        applyEpisodePlaylist(launch, relay, "4", "2", "Series", subtitle)
                        val parcel = android.os.Parcel.obtain()
                        val restored = try {
                            launch.writeToParcel(parcel, 0)
                            parcel.setDataPosition(0)
                            Intent.CREATOR.createFromParcel(parcel)
                        } finally { parcel.recycle() }
                        val targets = mxPlayerTargets(restored)
                        check(targets.size == if (launch.action == Intent.ACTION_CHOOSER) 2 else 1)
                        targets.forEach { item ->
                            check(item.data.toString() == relay.urls.getValue("2"))
                            check(item.getParcelableArrayExtra("video_list")?.map { it.toString() } == relay.urls.values.toList())
                            check(item.getStringArrayExtra("video_list.name")?.size == 3)
                            check(item.getBooleanExtra("video_list_is_explicit", false))
                            check(item.getIntExtra("position", 0) == 123000)
                            check(item.getParcelableArrayExtra("subs")?.single()?.toString() ==
                                subtitle?.let { relay.subtitleUrl("2", it.url) })
                        }
                    }
                    check(other.data.toString() == "https://example.invalid/2.mp4")
                    check(!other.hasExtra("video_list_is_explicit"))
                }
            }
        }
        test("JSON containing HTML preserved on Android") {
            val doc = ResponseDocument.parse("""{"success":true,"comments":"<b>Hello</b>"}""", "https://example.invalid")
            check(org.json.JSONObject(doc.body().text()).getString("comments") == "<b>Hello</b>")
        }
        test("Auth challenge is not treated as expired session") {
            check(AccountParser.parse(Jsoup.parse("<h1>Checking browser</h1>")).authenticated == null)
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", results.toString()); putInt("failures", failures) })
    }
}

class PlayerContractFixture : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK, Intent("com.mxtech.intent.result.VIEW").putExtra("position", 123000).putExtra("end_by", "user"))
        finish()
    }
}
