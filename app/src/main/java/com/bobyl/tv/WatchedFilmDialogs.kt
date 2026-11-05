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
    if (LocalCardSession.current == null) {
        CardViewModelScope(provider, film.url) { WatchedFilmDialogs(provider, film, dismiss, onBookmarksRemoved, onRated) }
        return
    }
    var rating by rememberSaveable(provider, film.url) { mutableStateOf(true) }
    val actions = LocalFilmActions.current
    val actionKey = currentFilmActionKey(provider, film.url)
    val model: BookmarkRemovalViewModel = viewModel(key = "remove-bookmarks:${actionKey.film}") { BookmarkRemovalViewModel(actions, actionKey) }
    val state by model.state.collectAsState()
    val queryReady by model.queryReady.collectAsState()
    val currentDismiss by rememberUpdatedState(dismiss)
    val currentOnRemoved by rememberUpdatedState(onBookmarksRemoved)
    LaunchedEffect(provider, film.url) { model.check(film.url) }
    if (rating) {
        FilmRatingDialog(provider, film, dismiss = { rating = false }, onRated = onRated)
        return
    }
    LaunchedEffect(state.loading, state.removed, state.categories, queryReady) {
        if (state.removed) { currentOnRemoved(state.categories); currentDismiss() }
        else if (!state.loading && queryReady && !state.hasMembership) currentDismiss()
    }
    if (!state.loading && queryReady && !state.hasMembership) return
    val leaveFocus = remember { FocusRequester() }
    LaunchedEffect(state.loading) { if (!state.loading) { withFrameNanos { }; leaveFocus.requestFocus() } }
    Dialog({ if (!state.saving) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CinemaFocusFrameHost {
        Column(Modifier.widthIn(max = 344.dp).fillMaxWidth().clip(RoundedCornerShape(CinemaStyle.PanelCorner))
                    .cinemaSurface(focused = false, shape = RoundedCornerShape(CinemaStyle.PanelCorner)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (queryReady && state.showConfirmation) "Удалить из всех списков?" else "Избранное",
                color = CinemaStyle.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(film.title, color = CinemaStyle.Muted, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.loading) SkeletonLoading(SkeletonLayout.Rows, rows = 1)
            else {
                if (state.error.isNotBlank()) Text(state.error, color = CinemaStyle.Error, fontSize = 14.sp)
                if (state.saving) LoadingIndicator("Удаляем…")
                CinemaButtonFocusGroup {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CinematicButton("Оставить", false, Modifier.weight(1f).focusRequester(leaveFocus)) { if (!state.saving) dismiss() }
                    CinematicButton(if (queryReady) "Удалить" else "Повторить", true, Modifier.weight(1f)) {
                        if (queryReady) model.remove(provider, film.url) else model.check(film.url)
                    }
                }
            }
            }
        }
        }
    }
}
