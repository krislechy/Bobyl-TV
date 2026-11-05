package com.bobyl.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

internal data class PremiumPeriod(val days: Int, val paidOn: Long)

/** Local calendar day encoded without requiring API 26 java.time on the minimum SDK. */
internal fun premiumCalendarDay(time: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = time }
    return GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis / 86_400_000L
}

internal fun premiumRemainingDays(info: String): Int? =
    Regex("(\\d+)\\s*(?:дней|дня|день)", RegexOption.IGNORE_CASE)
        .find(info)?.groupValues?.get(1)?.toIntOrNull()

internal fun premiumFraction(remaining: Int?, purchased: Int?): Float? =
    if (remaining == null || purchased == null || remaining < 0 || purchased <= 0) null
    else (remaining.toFloat() / purchased).coerceIn(0f, 1f)

/** Only the site's successful purchase rows are evidence of a purchased duration. */
internal object PremiumPeriodParser {
    fun parse(doc: Document): List<PremiumPeriod> {
        val dateFormat = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru")).apply { isLenient = false }
        val table = doc.selectFirst("table.b-payments_history_table") ?: return emptyList()
        val rows = table.select("tr")
        val header = rows.firstOrNull { it.select("th").isNotEmpty() } ?: return emptyList()
        val labels = header.select("th").map { it.text().trim() }
        val days = labels.indexOf("Дней")
        val status = labels.indexOf("Статус")
        val date = labels.indexOf("Дата платежа")
        if (minOf(days, status, date) < 0) return emptyList()
        return rows.mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size <= maxOf(days, status, date) || cells[status].text().trim() != "Успешный") return@mapNotNull null
            val duration = cells[days].text().trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val text = cells[date].text().trim()
            val position = ParsePosition(0)
            val paidOn = dateFormat.parse(text, position)?.takeIf { position.index == text.length } ?: return@mapNotNull null
            PremiumPeriod(duration, premiumCalendarDay(paidOn.time))
        }.distinct()
    }

    fun active(periods: List<PremiumPeriod>, remaining: Int, today: Long): PremiumPeriod? {
        val latest = periods.maxOfOrNull { it.paidOn } ?: return null
        return periods.filter { it.paidOn == latest }.singleOrNull()?.takeIf { matches(it, remaining, today) }
    }

    fun matches(period: PremiumPeriod, remaining: Int, today: Long): Boolean {
        val elapsed = today - period.paidOn
        return remaining > 0 && remaining <= period.days && elapsed >= 0 &&
            abs(period.days.toLong() - elapsed - remaining) <= 1L
    }
}

/** Minimal Room metadata, never a copy of the payment page or a user's credentials. */
internal class PremiumPeriodCache(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val fetch: suspend () -> Document,
    private val current: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(provider: String, owner: String, remaining: Int, profileTag: String = "", refresh: Boolean = false): Int? = withContext(Dispatchers.IO) {
        if (!current() || remaining <= 0) return@withContext null
        val key = key(provider, owner)
        val time = now()
        val today = premiumCalendarDay(time)
        val saved = read(key)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val period = saved?.let { value -> runCatching {
            PremiumPeriod(value.getInt("days"), value.getLong("paid"))
        }.getOrNull() }
        val previousRemaining = saved?.optInt("remaining", -1) ?: -1
        val increased = previousRemaining >= 0 && remaining > previousRemaining
        if (!refresh && !increased && period != null && PremiumPeriodParser.matches(period, remaining, today)) {
            if (current()) saved?.let { write(key, it.put("remaining", remaining).toString()) }
            return@withContext period.days.takeIf { current() }
        }
        val age = time - (saved?.optLong("checked", 0L) ?: 0L)
        if (!refresh && !increased && period == null && saved != null && saved.optString("profile") == profileTag &&
            remaining == previousRemaining && age in 0 until 6 * 60 * 60 * 1_000L) {
            return@withContext null
        }
        // Invalidate incompatible evidence before HTTP, so a failed renewal lookup cannot reuse it.
        val pending = JSONObject().put("remaining", remaining).put("checked", time).put("profile", profileTag)
        if (!current()) return@withContext null
        write(key, pending.toString())
        val active = PremiumPeriodParser.active(PremiumPeriodParser.parse(fetch()), remaining, today)
        currentCoroutineContext().ensureActive()
        if (!current()) return@withContext null
        if (active != null) {
            pending.put("days", active.days).put("paid", active.paidOn)
            write(key, pending.toString())
        }
        active?.days
    }

    companion object {
        internal fun key(provider: String, owner: String): String = "premium-period-v1:" + MessageDigest.getInstance("SHA-256")
            .digest("${CatalogUrls.provider(provider)}|$owner".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
