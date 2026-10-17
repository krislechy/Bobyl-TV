package com.bobyl.tv.data

import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun historyFilmKey(url: String): String {
    val path = runCatching { URI(url).path }.getOrNull().orEmpty().trimEnd('/')
    return Regex("^(\\d+)-").find(path.substringAfterLast('/'))?.groupValues?.get(1) ?: path
}

internal object ServerHistoryChanges {
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()
    fun changed() { PreparedDataCache.clear(); mutableRevision.value++ }
}
