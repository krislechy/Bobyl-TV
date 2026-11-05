package com.bobyl.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.bobyl.tv.data.WatchRecord

@Composable
internal fun HomeContinueRow(
    state: HomeContinueState,
    pendingFocus: String,
    focused: (String) -> Unit,
    restored: () -> Unit,
    resume: (WatchRecord) -> Unit,
    retry: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(pendingFocus, state.records) {
        val index = state.records.indexOfFirst { it.url == pendingFocus }
        if (index >= 0) listState.scrollToItem(index)
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Продолжить просмотр", color = CinemaStyle.Text, style = CinemaStyle.Heading)
        if (state.error.isNotBlank()) {
            Text(state.error, color = CinemaStyle.Error, style = CinemaStyle.Body)
            CinematicButton("Повторить", false, onClick = retry)
        }
        when {
            state.loading && state.records.isEmpty() -> SkeletonLoading(SkeletonLayout.Rows, rows = 1, actions = 0)
            state.records.isEmpty() && state.error.isBlank() -> Text("Здесь появятся фильмы и серии, которые вы начали смотреть.",
                color = CinemaStyle.Muted, style = CinemaStyle.Body)
            state.records.isNotEmpty() -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = ((maxWidth - 24.dp) / 3).coerceAtLeast(224.dp)
                LazyRow(Modifier.cinemaFilmViewport(), state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                    items(state.records, key = { it.url }) { record ->
                        ContinueCard(record, Modifier.width(width).focusProperties {
                            if (record.url == state.records.last().url) right = FocusRequester.Cancel
                        }, pendingFocus == record.url,
                            { focused(record.url) }, restored) { resume(record) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(record: WatchRecord, modifier: Modifier, restore: Boolean,
    onFocus: () -> Unit, onRestored: () -> Unit, resume: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val frameTarget = rememberCinemaFilmFocusTarget(focused)
    val requester = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val currentOnRestored by rememberUpdatedState(onRestored)
    LaunchedEffect(restore, windowFocused) {
        if (restore && windowFocused) {
            withFrameNanos { }
            if (requester.requestFocus()) currentOnRestored()
        }
    }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Row(modifier.focusRequester(requester).cinemaSurface(focused, pressed = pressed, focusFrameTarget = frameTarget)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .clickable(interactionSource = interactions, indication = null, onClick = resume)
        .padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp, 68.dp).clip(RoundedCornerShape(7.dp))) {
            if (record.poster.isNotBlank()) SkeletonAsyncImage(record.poster, null, Modifier.fillMaxSize(), ContentScale.Crop)
            else FieldIcon(FieldGlyph.Play, CinemaStyle.Muted, Modifier.align(Alignment.Center).size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(record.title, color = CinemaStyle.Text, style = CinemaStyle.Title,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (record.season.isNotBlank() && record.season != "0" && record.episode.isNotBlank() && record.episode != "0") {
                Text("Сезон ${record.season} · серия ${record.episode}", color = CinemaStyle.Muted,
                    style = CinemaStyle.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val seconds = record.positionMs.coerceAtLeast(0) / 1000
            Text("На ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                color = CinemaStyle.Accent, style = CinemaStyle.Caption)
        }
    }
}
