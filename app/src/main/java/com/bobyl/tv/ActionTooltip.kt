package com.bobyl.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Focus hints live outside the clipped action row and never take D-pad focus. */
internal val LocalActionTooltipRight = staticCompositionLocalOf { false }

@Composable
internal fun ActionTooltip(label: String, focused: Boolean) {
    val toRight = LocalActionTooltipRight.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(focused, windowFocused, label) {
        visible = false
        if (focused && windowFocused) {
            delay(450)
            visible = true
        }
    }
    if (!visible || !focused || !windowFocused) return
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(gap, toRight) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                if (toRight) {
                    val right = anchorBounds.right + gap
                    val x = if (right + popupContentSize.width <= windowSize.width - gap) right
                        else (anchorBounds.left - popupContentSize.width - gap).coerceAtLeast(gap)
                    val y = (anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2)
                        .coerceIn(gap, (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap))
                    return IntOffset(x, y)
                }
                val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                    .coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap))
                val below = anchorBounds.bottom + gap
                val y = if (below + popupContentSize.height <= windowSize.height - gap) below
                    else (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(gap)
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false,
        dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Text(label, color = CinemaStyle.Text, fontSize = 14.sp,
            modifier = Modifier.widthIn(max = 280.dp).background(CinemaStyle.Raised, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp))
    }
}
