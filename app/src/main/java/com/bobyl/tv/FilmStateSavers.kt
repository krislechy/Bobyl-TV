package com.bobyl.tv

import androidx.compose.runtime.saveable.Saver
import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.WatchRecord

// Only film metadata is saved; playback URLs and account credentials stay out of saved state.
internal val CatalogFilmStateSaver = Saver<CatalogFilm?, List<String>>(
    save = { film -> film?.let { listOf(it.id, it.url, it.title, it.poster, it.metadata, it.badge, it.kind, it.rating) } ?: emptyList() },
    restore = { values -> if (values.size != 8) null else
        CatalogFilm(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]) },
)

internal val WatchRecordStateSaver = Saver<WatchRecord?, List<String>>(
    save = { record -> record?.let { listOf(it.filmId, it.url, it.title, it.poster, it.voice, it.season, it.episode,
        it.positionMs.toString(), it.updated.toString(), it.quality, it.subtitle, it.playerPackage, it.decoder.toString(), it.watched.toString()) } ?: emptyList() },
    restore = { values -> if (values.size != 14) null else WatchRecord(values[0], values[1], values[2], values[3],
        values[4], values[5], values[6], values[7].toLong(), values[8].toLong(), values[9], values[10], values[11],
        values[12].toInt(), values[13].toBoolean()) },
)
