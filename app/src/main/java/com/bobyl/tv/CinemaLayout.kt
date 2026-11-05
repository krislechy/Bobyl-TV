package com.bobyl.tv

import androidx.compose.ui.unit.dp

/** One content origin for every destination, below the floating clock. */
internal val CinemaContentTop = 36.dp
internal val CinemaContentHorizontal = 20.dp

/** A Home shortcut overrides the saved filter once; ordinary navigation preserves it. */
internal fun historyTabForRequest(current: String, requested: String?): String =
    requested?.takeIf { it == "continue" || it == "watched" } ?: current
