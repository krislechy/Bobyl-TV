package com.bobyl.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Shared SVG-derived status mark, used by the title and the history action. */
@Composable
internal fun WatchStatusIcon(watched: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, action: Boolean = false) {
    val drawable = when {
        action && watched -> R.drawable.watch_status_unmark
        action -> R.drawable.watch_status_mark
        watched -> R.drawable.watch_status_checked
        else -> R.drawable.watch_status_active
    }
    Image(painterResource(drawable),
        contentDescription = null, modifier = modifier.size(32.dp), alpha = if (enabled) 1f else .55f)
}
