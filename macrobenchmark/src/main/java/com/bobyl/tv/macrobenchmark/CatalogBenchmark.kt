package com.bobyl.tv.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val poster = By.clazz("android.view.View")
        .desc(java.util.regex.Pattern.compile("(?!Бобыль$).+"))
    private fun MacrobenchmarkScope.posterFocused() =
        device.hasObject(By.focused(true).hasDescendant(poster)) ||
            device.hasObject(
                By.clazz("android.view.View")
                    .desc(java.util.regex.Pattern.compile("(?!Бобыль$).+"))
                    .focused(true),
            )

    private fun MacrobenchmarkScope.home() {
        killProcess()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text("Сейчас популярно")), 15_000))
        check(device.wait(Until.hasObject(poster), 45_000)) {
            "Catalog poster not found within 45 seconds after Home opened"
        }
        check(device.wait(Until.hasObject(By.focused(true).hasDescendant(By.text("Всё"))), 5_000)) {
            "Home initial filter did not receive focus"
        }
        repeat(3) {
            if (!posterFocused()) {
                device.pressDPadDown()
                device.wait(Until.hasObject(By.focused(true).hasDescendant(poster)), 2_000)
            }
        }
        check(posterFocused()) {
            "D-pad did not focus a poster"
        }
    }

    private fun MacrobenchmarkScope.details() {
        check(posterFocused()) { "Poster focus was lost between setup and Details action" }
        device.pressDPadCenter()
        check(device.wait(Until.hasObject(By.text("Комментарии")), 45_000)) {
            val directory = androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("additionalTestOutputDir")
            if (directory != null) device.dumpWindowHierarchy(java.io.File(directory, "details-failure.xml"))
            "Details content did not load; inspect details-failure.xml"
        }
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.search() {
        killProcess()
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text("Сейчас популярно")), 15_000))

        val rail = device.findObject(
            By.scrollable(true).hasDescendant(By.text("Главная")),
        )
        checkNotNull(rail).scroll(Direction.DOWN, 0.55f)
        device.waitForIdle()
        val searchLabel = device.wait(Until.findObject(By.text("Поиск")), 10_000)
        checkNotNull(searchLabel).parent.click()
        check(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000))

        device.findObject(By.clazz("android.widget.EditText")).apply {
            click()
            text = "matrix"
        }
        device.pressDPadRight()
        device.pressDPadCenter()
        check(device.wait(Until.hasObject(By.desc("Матрица")), 45_000)) {
            "Search results did not load"
        }
        device.waitForIdle()
    }

    private fun measure(setup: MacrobenchmarkScope.() -> Unit, action: MacrobenchmarkScope.() -> Unit) =
        benchmark.measureRepeated(
            packageName = "com.bobyl.tv.benchmark",
            metrics = listOf(FrameTimingMetric()),
            compilationMode = benchmarkCompilationMode(),
            iterations = 5,
            setupBlock = setup,
            measureBlock = action,
        )

    @Test fun homeScroll() = measure({ home() }) {
        repeat(12) { device.pressDPadDown(); SystemClock.sleep(120) }
        device.waitForIdle()
        check(posterFocused()) { "Catalog lost poster focus while scrolling down" }
        repeat(12) { device.pressDPadUp(); SystemClock.sleep(120) }
        device.waitForIdle()
    }

    @Test fun searchScroll() = measure({ search() }) {
        repeat(3) {
            device.swipe(1_200, 900, 1_200, 320, 20)
            SystemClock.sleep(180)
        }
        device.waitForIdle()
        repeat(3) {
            device.swipe(1_200, 320, 1_200, 900, 20)
            SystemClock.sleep(180)
        }
        device.waitForIdle()
    }

    @Test fun openDetails() = measure({ home() }) { details() }

    @Test fun detailsScroll() = measure({ home(); details() }) {
        repeat(12) { device.pressDPadDown(); SystemClock.sleep(120) }
        repeat(12) { device.pressDPadUp(); SystemClock.sleep(120) }
        device.waitForIdle()
    }

    @Test fun backFromDetails() = measure({ home(); details() }) {
        device.pressBack()
        check(device.wait(Until.gone(By.text("Комментарии")), 5_000))
        device.waitForIdle()
        check(posterFocused()) {
            "Back did not restore poster focus"
        }
    }
}
