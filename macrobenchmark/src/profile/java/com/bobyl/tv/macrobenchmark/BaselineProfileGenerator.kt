package com.bobyl.tv.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun homeDetailsAndSearch() = profile.collect(
        packageName = "com.bobyl.tv.profile",
        includeInStartupProfile = false,
    ) {
        pressHome()
        startActivityAndWait()
        val poster = By.clazz("android.view.View")
            .desc(java.util.regex.Pattern.compile("(?!Бобыль$).+"))
        check(device.wait(Until.hasObject(poster), 45_000)) { "Home posters unavailable" }
        // Profile list rendering independently of the separate D-pad regression tests.
        repeat(6) {
            checkNotNull(device.findObject(By.scrollable(true).hasDescendant(poster))).scroll(Direction.DOWN, 0.7f)
        }
        repeat(6) {
            checkNotNull(device.findObject(By.scrollable(true).hasDescendant(poster))).scroll(Direction.UP, 0.7f)
        }

        // Requires a signed-in profile app. Stop with an error if live content is unavailable.
        device.clickReady(By.desc("Друзья"))
        check(device.wait(Until.hasObject(By.text("Комментарии")), 45_000)) { "Series details unavailable" }
        device.clickReady(By.text("Смотреть"))
        device.clickReady(By.text("СТС"))
        device.clickReady(By.text("Сезон 1"))
        check(device.wait(Until.hasObject(By.text("Серия 1")), 10_000)) { "Episodes unavailable" }
        device.pressDPadDown()
        device.pressDPadUp()
        device.pressBack()
        check(device.wait(Until.hasObject(By.text("Выберите сезон")), 5_000))
        device.pressBack()
        check(device.wait(Until.hasObject(By.text("Озвучка и язык")), 5_000))
        device.pressBack()
        check(device.wait(Until.hasObject(By.text("Комментарии")), 5_000))
        device.pressBack()
        check(device.wait(Until.hasObject(By.desc("Друзья")), 5_000))

        val rail = checkNotNull(device.findObject(By.scrollable(true).hasDescendant(By.text("Главная"))))
        rail.scroll(Direction.DOWN, 0.6f)
        device.clickReady(By.text("Поиск"))
        val input = checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000))
        // Set text without opening the TV keyboard, then submit through the UI.
        input.text = "matrix"
        checkNotNull(device.findObject(By.desc("Найти"))).click()
        check(device.wait(Until.hasObject(By.desc("Матрица")), 45_000)) { "Search results unavailable" }
        repeat(3) {
            checkNotNull(device.findObject(By.scrollable(true).hasDescendant(poster))).scroll(Direction.DOWN, 0.7f)
        }
        repeat(3) {
            checkNotNull(device.findObject(By.scrollable(true).hasDescendant(poster))).scroll(Direction.UP, 0.7f)
        }
    }

    private fun UiDevice.clickReady(selector: BySelector) {
        repeat(3) {
            waitForIdle()
            try {
                checkNotNull(wait(Until.findObject(selector), 45_000)) { "Missing UI target: $selector" }.click()
                return
            } catch (_: StaleObjectException) {
                // Re-query a node replaced by lazy layout; never reuse the stale instance.
            }
        }
        error("UI target kept changing: $selector")
    }
}
