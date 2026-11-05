package com.bobyl.tv.data

import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class HistoryFilmChange(
    val session: AccountSession,
    val key: String,
    val records: List<WatchRecord>? = null,
    val server: List<ContinueItem>? = null,
    val watched: Boolean? = null,
    val sequence: Long = 0,
)

internal data class HistoryClearChange(val session: AccountSession, val device: Boolean, val sequence: Long,
    val completed: Boolean = true)

internal data class HistoryFilmChanges(val sequence: Long = 0, val films: List<HistoryFilmChange> = emptyList(),
    val clears: List<HistoryClearChange> = emptyList())

internal fun historyFilmKey(url: String): String {
    val path = runCatching { URI(url).path }.getOrNull().orEmpty().trimEnd('/')
    return Regex("^(\\d+)-").find(path.substringAfterLast('/'))?.groupValues?.get(1) ?: path
}

internal object ServerHistoryChanges {
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()
    private val mutableFilms = MutableStateFlow(HistoryFilmChanges())
    val films = mutableFilms.asStateFlow()

    /** Retain the latest patch per film so conflated collectors never lose another film's change. */
    @Synchronized fun filmChanged(session: AccountSession, url: String, records: List<WatchRecord>? = null,
        server: List<ContinueItem>? = null, watched: Boolean? = null) {
        val key = historyFilmKey(url)
        val previous = mutableFilms.value
        val old = previous.films.firstOrNull { it.session == session && it.key == key }
        val patch = HistoryFilmChange(session, key, records?.toList() ?: old?.records,
            server?.toList() ?: old?.server,
            watched ?: old?.watched?.takeIf { records == null && server == null }, previous.sequence + 1)
        mutableFilms.value = previous.copy(sequence = patch.sequence,
            films = (previous.films.filterNot { it.session == session && it.key == key } + patch).takeLast(256))
        changed()
    }

    /** Clearing one source must update retained screens without fetching every film again. */
    @Synchronized fun historyCleared(session: AccountSession, device: Boolean, completed: Boolean = true) {
        val previous = mutableFilms.value
        val sequence = previous.sequence + 1
        mutableFilms.value = previous.copy(sequence = sequence,
            films = if (!completed) previous.films else previous.films.map { patch ->
                if (patch.session != session) patch
                else if (device) patch.copy(records = emptyList()) else patch.copy(server = emptyList())
            },
            clears = (previous.clears.filterNot { it.session == session && it.device == device } +
                HistoryClearChange(session, device, sequence, completed)).takeLast(32))
        changed()
    }

    /** A confirmed full refresh supersedes only patches that predate its request. */
    @Synchronized fun acknowledge(session: AccountSession, through: Long) {
        val previous = mutableFilms.value
        val remaining = previous.films.filterNot { it.session == session && it.sequence <= through }
        val clears = previous.clears.filterNot { it.session == session && it.sequence <= through }
        if (remaining.size != previous.films.size || clears.size != previous.clears.size)
            mutableFilms.value = previous.copy(films = remaining, clears = clears)
    }
    @Synchronized fun changed() { PreparedDataCache.clear(); mutableRevision.value++ }
}
