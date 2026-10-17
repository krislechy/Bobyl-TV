package com.bobyl.tv

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import androidx.lifecycle.ViewModelStore
import com.bobyl.tv.data.AuthRepository
import com.bobyl.tv.data.CatalogUrls
import com.bobyl.tv.data.ExtrasRepository
import com.bobyl.tv.data.historyFilmKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Opt-in live account check. Restores both server status and local history; never prints session data. */
internal class HistorySyncChecks(private val instrumentation: Instrumentation) {
    fun run() {
        val report = mutableListOf<String>()
        var passed = false
        var stage = "emulator"
        try {
            check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
            val context = instrumentation.targetContext
            val application = context.applicationContext as Application
            val provider = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                .getString("ownProvider", "hdrzk.org") ?: "hdrzk.org"
            val local = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
            val localKey = CatalogUrls.provider(provider)
            val originalLocal = local.getString(localKey, null)
            val store = ViewModelStore()
            instrumentation.runOnMainSync { CookieManager.getInstance() }
            runBlocking {
                stage = "restore session"
                check(AuthRepository(context).restore(provider) != null) { "NO_SAVED_SESSION" }
                val repository = ExtrasRepository()
                stage = "load server history"
                val before = repository.history(provider, force = true)
                val candidates = before.groupBy { historyFilmKey(it.film.url) }.values.filter { it.size == 1 }.map { it.single() }
                val entry = candidates.firstOrNull { !it.watched } ?: candidates.firstOrNull()
                    ?: error("NO_SINGLE_HISTORY_ENTRY")
                var attempted = false
                try {
                    stage = "detail action"
                    lateinit var detail: FilmHistoryViewModel
                    instrumentation.runOnMainSync {
                        detail = FilmHistoryViewModel(application)
                        store.put("detail", detail)
                        detail.load(provider, entry.film.url)
                    }
                    check(withTimeout(60_000) { detail.state.first { !it.loading } }.error.isBlank())
                    attempted = true
                    instrumentation.runOnMainSync { detail.setWatched(provider, entry.film, !entry.watched) }
                    val detailResult = withTimeout(60_000) { detail.state.first { !it.saving } }
                    check(detailResult.error.isBlank()) { "DETAIL_SAVE_FAILED" }
                    check(repository.history(provider, force = true).single { it.id == entry.id }.watched == !entry.watched)
                    report += "PASS detail action persisted on server (fresh GET)"

                    stage = "continue action"
                    lateinit var list: HistoryViewModel
                    instrumentation.runOnMainSync {
                        list = HistoryViewModel(application)
                        store.put("list", list)
                        list.refresh(provider)
                    }
                    check(withTimeout(60_000) { list.state.first { !it.busy } }.error.isBlank())
                    val key = historyFilmKey(entry.film.url)
                    instrumentation.runOnMainSync { list.setWatched(provider, key, entry.watched) }
                    val listResult = withTimeout(60_000) { list.state.first { key !in it.changing } }
                    check(listResult.error.isBlank()) { "LIST_SAVE_FAILED" }
                    check(repository.history(provider, force = true).single { it.id == entry.id }.watched == entry.watched)
                    report += "PASS continue action persisted on server (fresh GET)"
                } finally {
                    try {
                        if (attempted) {
                            stage = "restore server status"
                            val current = repository.history(provider, force = true).single { it.id == entry.id }
                            repository.setHistoryWatched(provider, current, entry.watched)
                            check(repository.history(provider, force = true).single { it.id == entry.id }.watched == entry.watched)
                            report += "PASS original server status restored"
                        }
                    } finally {
                        instrumentation.runOnMainSync { store.clear() }
                        check(if (originalLocal == null) local.edit().remove(localKey).commit()
                            else local.edit().putString(localKey, originalLocal).commit())
                        report += "PASS original local history restored"
                    }
                }
            }
            passed = true
        } catch (failure: Exception) {
            // Deliberately omit exception messages: network errors may contain account information.
            report += "FAIL $stage: ${failure.javaClass.simpleName}"
        }
        instrumentation.finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.joinToString("\n", postfix = "\n")) })
    }
}
