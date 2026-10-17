package com.bobyl.tv

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo

/** Keep focus inside an action list when its focused row/control disappears. */
internal class ActionListFocus {
    var focused by mutableStateOf<String?>(null)
    val targets = mutableMapOf<String, FocusRequester>()

    @Composable
    fun target(key: String): Modifier {
        val requester = remember(key) { FocusRequester() }
        DisposableEffect(key, requester) {
            targets[key] = requester
            onDispose { if (targets[key] === requester) targets.remove(key) }
        }
        return Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) focused = key }
    }
}

@Composable
internal fun rememberActionListFocus(
    controls: List<String>, positions: Map<String, Int>, list: LazyListState,
): Pair<ActionListFocus, Modifier> {
    val focus = remember { ActionListFocus() }
    var previous by remember { mutableStateOf(controls) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val latestControls by rememberUpdatedState(controls)
    val latestWindowFocused by rememberUpdatedState(windowFocused)
    LaunchedEffect(controls, windowFocused) {
        val old = previous
        previous = controls
        val removed = focus.focused ?: return@LaunchedEffect
        if (!windowFocused) return@LaunchedEffect
        val missing = removed !in controls
        if (!missing) return@LaunchedEffect
        val row = removed.substringBeforeLast(':')
        val oldIndex = old.indexOf(removed)
        val target = controls.firstOrNull { it == "$row:open" }
            ?: old.drop((oldIndex + 1).coerceAtLeast(0)).firstOrNull { it in controls }
            ?: old.take(oldIndex.coerceAtLeast(0)).lastOrNull { it in controls }
            ?: "tabs"
        val position = positions[target.substringBeforeLast(':')] ?: 1
        if (target !in focus.targets) list.scrollToItem(position)
        withFrameNanos { }
        // Respect navigation elsewhere while the list was being measured/scrolled.
        if (focus.focused != removed) return@LaunchedEffect
        val restored = focus.targets[target]?.requestFocus() == true
        if (restored) focus.focused = target
        else {
            list.scrollToItem(0)
            withFrameNanos { }
            if (focus.focused == removed && focus.targets["tabs"]?.requestFocus() == true) focus.focused = "tabs"
        }
    }
    val region = Modifier.onFocusChanged {
        // Losing a removed target is recoverable. Explicitly leaving a live target is not.
        if (!it.hasFocus && latestWindowFocused && focus.focused in latestControls) focus.focused = null
    }
    return focus to region
}
