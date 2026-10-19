package com.bobyl.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.bobyl.tv.data.BookmarkCategory
import com.bobyl.tv.data.CatalogFilm

@Composable
internal fun WatchedFilmDialogs(provider: String, film: CatalogFilm, dismiss: () -> Unit,
    onBookmarksRemoved: (List<BookmarkCategory>) -> Unit = {}, onRated: (Pair<String, String>) -> Unit = {}) {
    var rating by rememberSaveable(provider, film.url) { mutableStateOf(true) }
    val model: BookmarkRemovalViewModel = viewModel(key = "remove-bookmarks:$provider:${film.url}")
    val state by model.state.collectAsState()
    val currentDismiss by rememberUpdatedState(dismiss)
    val currentOnRemoved by rememberUpdatedState(onBookmarksRemoved)
    LaunchedEffect(provider, film.url) { model.check(film.url) }
    if (rating) {
        FilmRatingDialog(provider, film, dismiss = { rating = false }, onRated = onRated)
        return
    }
    LaunchedEffect(state.loading, state.removed, state.categories, state.error) {
        if (state.removed) { currentOnRemoved(state.categories); currentDismiss() }
        else if (!state.loading && state.error.isBlank() && state.categories.none { it.checked }) currentDismiss()
    }
    if (!state.loading && state.error.isBlank() && state.categories.none { it.checked }) return
    val leaveFocus = remember { FocusRequester() }
    LaunchedEffect(state.loading) { if (!state.loading) { withFrameNanos { }; leaveFocus.requestFocus() } }
    Dialog({ if (!state.saving) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 344.dp).fillMaxWidth().clip(RoundedCornerShape(CinemaStyle.PanelCorner))
            .background(CinemaStyle.Background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Удалить из всех списков?", color = CinemaStyle.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(film.title, color = CinemaStyle.Muted, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.loading) SkeletonLoading(SkeletonLayout.Rows, rows = 1)
            else {
                if (state.error.isNotBlank()) Text(state.error, color = Color(0xFFFFB5B9), fontSize = 14.sp)
                if (state.saving) LoadingIndicator("Удаляем…")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CinematicButton("Оставить", false, Modifier.weight(1f).focusRequester(leaveFocus)) { if (!state.saving) dismiss() }
                    CinematicButton(if (state.error.isBlank()) "Удалить" else "Повторить", true, Modifier.weight(1f)) {
                        if (state.error.isNotBlank()) model.check(film.url) else model.remove(provider, film.url)
                    }
                }
            }
        }
    }
}
