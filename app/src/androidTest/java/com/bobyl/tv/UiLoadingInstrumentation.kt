package com.bobyl.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.bobyl.tv.data.CatalogFilm
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** Two UI checks with controlled catalog responses and no account changes. */
internal class UiLoadingChecks(private val instrumentation: Instrumentation) {
    fun run() = with(instrumentation) {
        val report = StringBuilder()
        var failures = 0
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun test(name: String, block: () -> Unit) {
            try { block(); report.append("PASS $name\n") }
            catch (failure: Throwable) {
                failures++
                report.append("FAIL $name: ${failure.message}\n")
            }
        }
        try {
            test("Failed next page retries the same page and keeps existing films") {
                val calls = CopyOnWriteArrayList<Int>()
                val failOnce = AtomicBoolean(true)
                runOnMainSync {
                    activity.setContent {
                        PagedFilms("Retry fixture", "retry-fixture", 6, {}) { page ->
                            calls.add(page)
                            when (page) {
                                1 -> listOf(film("First"))
                                2 -> {
                                    if (failOnce.getAndSet(false)) throw IOException("Fixture failure")
                                    listOf(film("Second"))
                                }
                                else -> emptyList()
                            }
                        }
                    }
                }
                await("Retry button", { "requests=$calls" }) { node("Повторить") != null }
                check(node("First") != null) { "Existing film disappeared after error" }
                check(calls.toList() == listOf(1, 2)) { "Requests before Retry: $calls" }
                var button = node("Повторить")!!
                while (!button.isClickable) button = checkNotNull(button.parent)
                check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Second page after Retry") { node("Second") != null }
                check(node("First") != null) { "Retry replaced the first page" }
                check(node("Повторить") == null) { "Error remains after successful Retry" }
                check(calls.take(3) == listOf(1, 2, 2)) { "Retry skipped a page: $calls" }
            }
            test("Filter change cancels pending pagination and ignores its old result") {
                val filter = mutableStateOf("A")
                val pending = CompletableDeferred<List<CatalogFilm>>()
                val started = AtomicBoolean(false)
                val cancelled = AtomicBoolean(false)
                val calls = CopyOnWriteArrayList<String>()
                runOnMainSync {
                    activity.setContent {
                        val selected = filter.value
                        PagedFilms("Filter fixture", selected, 6, {}) { page ->
                            calls.add("$selected:$page")
                            when {
                                page == 1 -> listOf(film("$selected first"))
                                selected == "A" && page == 2 -> {
                                    started.set(true)
                                    try { pending.await() }
                                    catch (failure: CancellationException) {
                                        cancelled.set(true)
                                        throw failure
                                    }
                                }
                                else -> emptyList()
                            }
                        }
                    }
                }
                await("Pending page A:2") { started.get() }
                runOnMainSync { filter.value = "B" }
                await("New filter and cancelled old request", { "requests=$calls cancelled=${cancelled.get()}" }) {
                    cancelled.get() && node("B first") != null && calls.contains("B:2")
                }
                pending.complete(listOf(film("Stale result")))
                waitForIdleSync()
                check(node("A first") == null && node("Stale result") == null) {
                    "Old filter leaked into new results"
                }
                check(calls.toList() == listOf("A:1", "A:2", "B:1", "B:2")) {
                    "Unexpected pagination after filter change: $calls"
                }
            }
        } finally {
            runOnMainSync { activity.finish() }
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", report.toString()); putInt("failures", failures) })
    }

    private fun film(title: String) = CatalogFilm(title, "https://fixture.invalid/$title",
        title, "", "", "", "", "")

    private fun node(text: String): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (current == null || current.text?.toString() == text) return current
            for (index in 0 until current.childCount) {
                find(current.getChild(index))?.let { return it }
            }
            return null
        }
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun await(description: String, diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fun labels(node: AccessibilityNodeInfo?): List<String> {
            if (node == null) return emptyList()
            return listOfNotNull(node.text?.toString()) +
                (0 until node.childCount).flatMap { labels(node.getChild(it)) }
        }
        error("Timed out: $description; ${diagnostic()}; visible=${labels(instrumentation.uiAutomation.rootInActiveWindow)}")
    }
}
