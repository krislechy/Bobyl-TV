package com.bobyl.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.bobyl.tv.data.BookmarkCategory

@Composable
internal fun FilmFavoriteButton(provider: String, filmId: String, url: String, categories: List<BookmarkCategory>,
    triggerFocus: FocusRequester, onOpen: () -> Unit, onUpdated: (List<BookmarkCategory>) -> Unit) {
    val model: FilmBookmarksViewModel = viewModel(key = "film-bookmarks:$provider:$filmId")
    val state by model.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val currentOnUpdated by rememberUpdatedState(onUpdated)
    LaunchedEffect(categories) { model.initialize(categories) }
    LaunchedEffect(state.revision) { if (state.revision > 0) currentOnUpdated(state.categories) }
    FieldAction(FieldGlyph.Bookmark, "Избранное", checked = state.categories.any { it.checked },
        modifier = Modifier.focusRequester(triggerFocus), tooltip = true) { onOpen(); expanded = true }
    if (expanded) Dialog({ expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val selectedIndex = state.categories.indexOfFirst { it.checked }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; firstFocus.requestFocus() }
        Column(Modifier.widthIn(max = 304.dp).fillMaxWidth().clip(RoundedCornerShape(CinemaStyle.PanelCorner))
            .background(CinemaStyle.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Избранное", color = CinemaStyle.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                if (state.saving) LoadingIndicator("")
            }
            if (state.categories.isEmpty()) Text("Нет доступных списков. Выполните вход в настройках.", color = CinemaStyle.Muted, fontSize = 15.sp)
            else LazyColumn(Modifier.height((state.categories.size * 48 + 8).coerceAtMost(232).dp)
                .fillMaxWidth().popupScrollIndicator(listState).padding(end = 10.dp), state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(4.dp)) {
                items(state.categories, key = { it.id }) { category ->
                    val itemModifier = if (category.id == state.categories.getOrNull(selectedIndex)?.id)
                        Modifier.focusRequester(firstFocus) else Modifier
                    Box(itemModifier.fillMaxWidth()) {
                        CinematicButton(category.name, category.checked, Modifier.fillMaxWidth()) {
                            model.toggle(provider, filmId, url, category)
                        }
                        if (category.checked) Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(16.dp)) {
                            drawLine(CinemaStyle.OnAccent, Offset(size.width * .15f, size.height * .5f), Offset(size.width * .4f, size.height * .75f), 2.dp.toPx(), StrokeCap.Round)
                            drawLine(CinemaStyle.OnAccent, Offset(size.width * .4f, size.height * .75f), Offset(size.width * .85f, size.height * .2f), 2.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
            }
            if (state.error.isNotBlank()) Text(state.error, color = Color(0xFFFFB5B9), fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CinematicButton("Назад", false, if (state.categories.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier) { expanded = false }
            }
        }
    }
}
