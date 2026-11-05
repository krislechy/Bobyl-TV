package com.bobyl.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FavoritesScreen(provider: String, columns: Int, revision: Int, onReady: () -> Unit = {}, sessionRevision: Int = 0,
    detailOpen: Boolean = false, open: (CatalogFilm) -> Unit) {
    val repository = remember { LibraryRepository() }
    val model: FavoritesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "favorites-screen")
    val snapshot by model.state.collectAsState()
    val session = AccountSessionGuard.shared.current(provider)
    val state = snapshot.takeIf { it.session == session } ?: FavoritesState(session = session)
    val categories = state.categories
    var selectedId by rememberSaveable(provider, sessionRevision) { mutableStateOf("") }
    var sort by rememberSaveable(provider, sessionRevision) { mutableStateOf("added") }
    var kind by rememberSaveable(provider, sessionRevision) { mutableStateOf("0") }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createBusy by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf("") }
    val busy = state.busy || createBusy
    val error = createError.ifBlank { state.error }
    val scope = rememberCoroutineScope()
    LaunchedEffect(provider, sessionRevision, revision, detailOpen, state.dirty, state.busy) {
        if (!detailOpen) model.open(provider, revision)
    }
    LaunchedEffect(categories) { if (categories.none { it.id == selectedId }) selectedId = categories.firstOrNull()?.id.orEmpty() }
    if (creating) Dialog({ creating = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CinemaFocusFrameHost {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()
            .cinemaSurface(focused = false, shape = RoundedCornerShape(CinemaStyle.PanelCorner)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Новый список", color = CinemaStyle.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Input(newName, "Название списка", { if (!busy) newName = it })
            if (busy) LoadingIndicator("Сохраняем…")
            if (error.isNotBlank()) Text(error, color = CinemaStyle.Error)
            CinemaButtonFocusGroup {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                CinematicButton("Отмена", false) { creating = false }
                CinematicButton("Создать", true) {
                    if (!busy && newName.isNotBlank()) { createBusy = true; createError = ""; scope.launch {
                        try { repository.create(session, newName); newName = ""; creating = false; model.open(provider, revision) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { createError = networkErrorMessage(failure) }
                        finally { createBusy = false }
                    } }
                }
            }
        }
        }
        }
    }
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (categories.isNotEmpty()) CatalogTabs(categories.map { it.id to "${it.name} (${it.count})" }, selectedId,
                separateAll = categories.firstOrNull()?.let(::isAllBookmarkCategory) == true) { selectedId = it }
            CatalogTabs(contentTypes, kind, separateAll = true) { kind = it }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SettingsChoice("Сортировка", listOf("added" to "По добавлению", "year" to "По году", "popular" to "По популярности"), sort, compactPopup = true) { sort = it }
                }
                Spacer(Modifier.width(1.dp).height(32.dp).background(CinemaStyle.Outline))
                CinemaButtonFocusGroup {
                    CinematicButton("Создать список", true) { if (!busy) creating = true }
                    CinematicButton("Обновить", false) { if (!busy) { createError = ""; model.retry(provider) } }
                }
            }
            if (busy && categories.isEmpty()) SkeletonLoading(SkeletonLayout.Tabs)
            if (error.isNotBlank()) Text(error, color = CinemaStyle.Error)
        }
    }
    val category = categories.firstOrNull { it.id == selectedId }
    val currentOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(busy, category) {
        if (!busy && category == null) currentOnReady()
    }
    if (category == null) Column(Modifier.padding(start = CinemaContentHorizontal, end = CinemaContentHorizontal, top = CinemaContentTop), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        header()
        if (busy) SkeletonLoading(SkeletonLayout.Posters, rows = 2, columns = columns)
        if (!busy && error.isBlank()) Text("Нет списков", color = CinemaStyle.Muted)
    } else PagedFilms("Избранное", "$provider|$sessionRevision|${category.id}|$sort|$kind", columns, open, header,
        onReady = onReady, refreshRevision = state.contentRevision, requestSession = session) { page, force ->
        repository.films(category, page, sort, kind, force)
    }
}

