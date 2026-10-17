package com.bobyl.tv.data

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Formats server calendar dates without inventing a time when none was supplied. */
internal object HistoryDates {
    private val locale = Locale.forLanguageTag("ru")
    private val zone = TimeZone.getTimeZone("Europe/Moscow")

    fun format(raw: String, now: Long = System.currentTimeMillis()): String {
        val text = raw.lowercase(locale).replace('\u00a0', ' ').trim()
        if (text.isEmpty()) return ""
        if ("сегодня" in text || "вчера" in text) return raw.trim()
        val calendar = Calendar.getInstance(zone, locale).apply { timeInMillis = now }
        val relativeDays = when {
            "позавчера" in text -> 2
            "вчера" in text -> 1
            "сегодня" in text -> 0
            else -> null
        }
        val date: Date = if (relativeDays != null) {
            calendar.add(Calendar.DAY_OF_MONTH, -relativeDays)
            calendar.time
        } else {
            val numeric = Regex("(?<!\\d)(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[./]\\d{1,2}[./]\\d{4})(?!\\d)").find(text)?.value
            val named = Regex("(?<!\\d)\\d{1,2}\\s+(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)(?:\\s+\\d{4})?").find(text)?.value
            val token = numeric ?: named ?: return raw.trim()
            val pattern = when {
                numeric?.matches(Regex("\\d{4}-.*")) == true -> "yyyy-MM-dd"
                numeric != null && '/' in numeric -> "d/M/yyyy"
                numeric != null -> "d.M.yyyy"
                else -> "d MMMM yyyy"
            }
            val complete = if (numeric == null && !Regex("\\d{4}$").containsMatchIn(token))
                "$token ${calendar.get(Calendar.YEAR)}" else token
            val parser = SimpleDateFormat(pattern, locale).apply { timeZone = zone; isLenient = false }
            val position = ParsePosition(0)
            parser.parse(complete, position)?.takeIf { position.index == complete.length } ?: return raw.trim()
        }
        val day = SimpleDateFormat("dd.MM.yyyy", locale).apply { timeZone = zone }.format(date)
        val time = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)").find(text)
        return if (time == null) day else "$day ${time.groupValues[1].padStart(2, '0')}:${time.groupValues[2]}"
    }
}
