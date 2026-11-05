package com.bobyl.tv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.GregorianCalendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PremiumPeriodTest {
    private val time = GregorianCalendar(2026, 10, 4).timeInMillis
    private val today = premiumCalendarDay(time)
    private fun history(rows: String) = Jsoup.parse("""
        <table class="b-payments_history_table"><tr><th>Дней</th><th>Статус</th><th>Дата платежа</th></tr>$rows</table>
    """)
    private fun row(days: Int, status: String = "Успешный", date: String = "25 августа 2026, 22:15") =
        "<tr><td>$days</td><td>$status</td><td>$date</td></tr>"

    @Test fun capturedHeadersAndSuccessfulStatusProvideTheActualPurchasedDuration() {
        val periods = PremiumPeriodParser.parse(history(row(180)))
        assertEquals(listOf(PremiumPeriod(180, premiumCalendarDay(GregorianCalendar(2026, 7, 25).timeInMillis))), periods)
        assertEquals(180, PremiumPeriodParser.active(periods, 109, today)?.days)
        assertEquals(109f / 180f, premiumFraction(109, periods.single().days)!!, .0001f)
    }

    @Test fun failedRowsMalformedDatesAndMissingHeadersNeverInventADuration() {
        assertTrue(PremiumPeriodParser.parse(history(row(180, "Неуспешный") + row(180, date = "31 февраля 2026, 22:15"))).isEmpty())
        assertTrue(PremiumPeriodParser.parse(Jsoup.parse("<table class=b-payments_history_table><tr><td>180</td><td>Успешный</td></tr></table>")).isEmpty())
        assertNull(premiumFraction(109, null))
        assertNull(premiumFraction(null, 180))
        assertEquals(1f, premiumFraction(200, 180)!!, 0f)
        assertEquals(0f, premiumFraction(0, 180)!!, 0f)
    }

    @Test fun reorderedHeadersIgnoreUnrelatedPaymentFields() {
        val doc = Jsoup.parse("""<table class=b-payments_history_table>
            <tr><th>ID</th><th>Дата платежа</th><th>Сумма</th><th>Статус</th><th>Дней</th></tr>
            <tr><td>ignored</td><td>25 августа 2026, 22:15</td><td>ignored</td><td>Успешный</td><td>180</td></tr></table>""")
        assertEquals(180, PremiumPeriodParser.parse(doc).single().days)
    }

    @Test fun latestRenewalMustMatchRemainingAndAmbiguousOrAccumulatedTermsStayUnknown() {
        val old = row(30, date = "1 января 2026, 10:00")
        assertEquals(180, PremiumPeriodParser.active(PremiumPeriodParser.parse(history(old + row(180))), 109, today)?.days)
        assertNull(PremiumPeriodParser.active(PremiumPeriodParser.parse(history(row(180) + row(30, date = "3 ноября 2026, 10:00"))), 109, today))
        assertNull(PremiumPeriodParser.active(PremiumPeriodParser.parse(history(row(180) + row(181))), 109, today))
        assertNull(PremiumPeriodParser.active(PremiumPeriodParser.parse(history(row(180))), 200, today))
        assertNull(PremiumPeriodParser.active(PremiumPeriodParser.parse(history(row(180))), 109, today - 365))
    }

    @Test fun persistedCacheIsScopedAndRenewalIncreaseRequiresFreshEvidence() = runTest {
        val storage = mutableMapOf<String, String>()
        var calls = 0
        var response = history(row(180))
        val cache = PremiumPeriodCache(storage::get, { k, v -> storage[k] = v }, { calls++; response }, { true }, { time })
        assertEquals(180, cache.load("one.example", "owner1", 109))
        assertEquals(180, cache.load("one.example", "owner1", 108))
        assertEquals(1, calls)
        response = history(row(180) + row(365, date = "4 ноября 2026, 10:00"))
        assertEquals(365, cache.load("one.example", "owner1", 365))
        assertEquals(2, calls)
        cache.load("one.example", "owner2", 365)
        cache.load("two.example", "owner1", 365)
        assertEquals(4, calls)
        assertEquals(3, storage.size)
        assertTrue(storage.keys.none { "owner" in it || "example" in it })
    }

    @Test fun failedOrUnknownLookupsAreBoundedButChangedProfileAndActivationBypassNegativeCache() = runTest {
        val storage = mutableMapOf<String, String>()
        var calls = 0
        var offline = true
        var clock = time
        val cache = PremiumPeriodCache(storage::get, { k, v -> storage[k] = v }, {
            calls++; if (offline) error("offline") else history("")
        }, { true }, { clock })
        try { cache.load("one.example", "owner", 109, "profile1"); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertNull(cache.load("one.example", "owner", 109, "profile1"))
        assertEquals(1, calls)
        offline = false
        assertNull(cache.load("one.example", "owner", 109, "profile2"))
        assertEquals(2, calls)
        assertNull(cache.load("one.example", "owner", 109, "profile2", refresh = true))
        assertEquals(3, calls)
        clock += 6 * 60 * 60 * 1_000L
        assertNull(cache.load("one.example", "owner", 109, "profile2"))
        assertEquals(4, calls)
    }

    @Test fun lateResponseCannotPublishOrCacheTheOldAccountsPeriod() = runTest {
        val storage = mutableMapOf<String, String>()
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<org.jsoup.nodes.Document>()
        var current = true
        val cache = PremiumPeriodCache(storage::get, { k, v -> storage[k] = v }, {
            started.complete(Unit); response.await()
        }, { current }, { time })
        val request = async { cache.load("one.example", "owner", 109) }
        started.await()
        current = false
        response.complete(history(row(180)))
        assertNull(request.await())
        assertFalse(storage.values.single().contains("\"days\""))
    }
}
