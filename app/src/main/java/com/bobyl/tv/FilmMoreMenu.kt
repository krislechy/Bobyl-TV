package com.bobyl.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun FilmMoreMenu(expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    triggerFocus: FocusRequester, canDownload: Boolean, busy: Boolean, sideMenu: Boolean = false,
    onDownload: () -> Unit, onTrailer: () -> Unit, onShare: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    val menuOffset = with(LocalDensity.current) { 56.dp.roundToPx() }
    Box {
        FieldAction(FieldGlyph.MoreVertical, "Дополнительные действия", enabled = !busy, loading = busy,
            modifier = Modifier.focusRequester(triggerFocus), tooltip = true) { onExpandedChange(true) }
        if (expanded) {
            LaunchedEffect(Unit) { withFrameNanos { }; firstFocus.requestFocus() }
            Popup(alignment = if (sideMenu) Alignment.TopStart else Alignment.TopEnd,
                offset = if (sideMenu) IntOffset(menuOffset, 0) else IntOffset(0, menuOffset),
                onDismissRequest = { onExpandedChange(false) }, properties = PopupProperties(focusable = true)) {
                val shape = RoundedCornerShape(CinemaStyle.Corner)
                Column(Modifier.width(224.dp).clip(shape).background(CinemaStyle.Surface)
                    .border(1.dp, CinemaStyle.Outline, shape).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canDownload) CinematicButton("Скачать", false, Modifier.fillMaxWidth().focusRequester(firstFocus), leadingIcon = FieldGlyph.Download) {
                        onExpandedChange(false)
                        onDownload()
                    }
                    CinematicButton("Трейлер", false, Modifier.fillMaxWidth()
                        .then(if (!canDownload) Modifier.focusRequester(firstFocus) else Modifier), leadingIcon = FieldGlyph.Trailer) {
                        onExpandedChange(false)
                        onTrailer()
                    }
                    CinematicButton("Поделиться", false, Modifier.fillMaxWidth(), leadingIcon = FieldGlyph.Share) {
                        onExpandedChange(false)
                        onShare()
                    }
                }
            }
        }
    }
}
