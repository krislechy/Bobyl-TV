package com.bobyl.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.bobyl.tv.data.WatchRecord

internal data class ContinueHistoryPresentation(
    val metadata: String,
    val progressLabel: String,
    val fraction: Float?,
    val showProgress: Boolean,
)

private val ContinueInfoSeparator = Regex("\\s+[•·]\\s+")
private val ContinueClock = Regex("^\\d{1,3}:[0-5]\\d(?::[0-5]\\d)?$")

/** Device records enable the track; the existing history winner still selects the displayed position. */
internal fun continueHistoryPresentation(entry: HistoryEntry): ContinueHistoryPresentation {
    val showProgress = entry.records.isNotEmpty()
    entry.position?.let { record ->
        return ContinueHistoryPresentation(continueRecordMetadata(record), continuePositionClock(record.positionMs), null, showProgress)
    }
    val server = entry.server.firstOrNull()
        ?: return ContinueHistoryPresentation(entry.records.firstOrNull()?.let(::continueRecordMetadata).orEmpty(), "", null, showProgress)
    val info = server.info.trim()
    // The parser joins the viewing date and actual progress with this delimiter. Do not parse the date's clock.
    val date = if (server.viewedAt.isNotBlank()) info.substringBefore(" • ") else ""
    val progress = if (server.viewedAt.isBlank()) info else info.substringAfter(" • ", "")
    val parts = progress.split(ContinueInfoSeparator).filter(String::isNotBlank)
    val clockPart = parts.firstOrNull { ContinueClock.matches(it) }
    val clock = server.save?.positionMs?.takeIf { it > 0 }?.let(::continuePositionClock) ?: clockPart.orEmpty()
    val metadata = (listOf(date) + parts.filterNot { it == clockPart })
        .filter(String::isNotBlank).joinToString(" · ")
        .ifBlank { continueEpisodeLabel(server.season, server.episode) }
    // Account history has no duration or percentage. A local record permits the track, not a guessed fraction.
    return ContinueHistoryPresentation(metadata, clock, null, showProgress)
}

private fun continueRecordMetadata(record: WatchRecord): String = listOf(
    continueEpisodeLabel(record.season, record.episode), record.quality, record.subtitle,
).filter(String::isNotBlank).joinToString(" · ")

private fun continueEpisodeLabel(season: String, episode: String): String = listOfNotNull(
    season.takeIf { it.isNotBlank() && it != "0" }?.let { "Сезон $it" },
    episode.takeIf { it.isNotBlank() && it != "0" }?.let { "Серия $it" },
).joinToString(" · ")

private fun continuePositionClock(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0L) / 1000
    val tail = "${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    return if (seconds >= 3600) "${seconds / 3600}:$tail" else tail
}

@Composable
internal fun ContinueHistoryContent(title: String, row: ContinueHistoryPresentation, sourceLabel: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = CinemaStyle.Text, fontSize = 18.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(row.metadata, Modifier.weight(1f), color = CinemaStyle.Muted, fontSize = 14.sp,
                lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sourceLabel.isNotBlank()) Text(sourceLabel, color = CinemaStyle.Muted, fontSize = 12.sp,
                lineHeight = 16.sp, maxLines = 1)
        }
        if (row.showProgress || row.progressLabel.isNotBlank()) Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (row.showProgress) Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(CinemaStyle.Outline)) {
                // Unknown duration stays neutral; an empty track does not claim 0% or ongoing loading.
                row.fraction?.let { fraction -> Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(CinemaStyle.Accent)) }
            }
            else Spacer(Modifier.weight(1f))
            if (row.progressLabel.isNotBlank()) Text(row.progressLabel, color = CinemaStyle.Accent, fontSize = 12.sp,
                lineHeight = 16.sp, maxLines = 1)
        }
    }
}
