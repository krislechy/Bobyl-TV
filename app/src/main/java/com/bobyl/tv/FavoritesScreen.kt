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
internal fun FavoritesScreen(provider: String, columns: Int, revision: Int, onReady: () -> Unit = {}, open: (CatalogFilm) -> Unit) {
    val repository = remember { LibraryRepository() }
    var categories by remember(provider) { mutableStateOf<List<BookmarkCategory>>(emptyList()) }
    var selectedId by rememberSaveable(provider) { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("added") }
    var kind by rememberSaveable { mutableStateOf("0") }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(provider, revision, retry) {
        busy = true; error = ""
        try {
            categories = repository.categories(provider, force = retry > 0 || revision > 0)
            if (categories.none { it.id == selectedId }) selectedId = categories.firstOrNull()?.id.orEmpty()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = networkErrorMessage(failure) }
        finally { busy = false }
    }
    if (creating) Dialog({ creating = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()
            .clip(RoundedCornerShape(CinemaStyle.PanelCorner)).background(CinemaStyle.Background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Новый список", color = CinemaStyle.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Input(newName, "Название списка", { if (!busy) newName = it })
            if (busy) LoadingIndicator("Сохраняем…")
            if (error.isNotBlank()) Text(error, color = Color(0xFFFFB5B9))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                CinematicButton("Отмена", false) { creating = false }
                CinematicButton("Создать", true) {
                    if (!busy && newName.isNotBlank()) { busy = true; scope.launch {
                        try { repository.create(provider, newName); newName = ""; creating = false; retry++ }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = networkErrorMessage(failure) }
                        finally { busy = false }
                    } }
                }
            }
        }
    }
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (categories.isNotEmpty()) CatalogTabs(categories.map { it.id to "${it.name} (${it.count})" }, selectedId) { selectedId = it }
            CatalogTabs(contentTypes, kind) { kind = it }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SettingsChoice("Сортировка", listOf("added" to "По добавлению", "year" to "По году", "popular" to "По популярности"), sort, compactPopup = true) { sort = it }
                }
                Spacer(Modifier.width(1.dp).height(32.dp).background(CinemaStyle.Outline))
                CinematicButton("Создать список", true) { if (!busy) creating = true }
                CinematicButton("Обновить", false) { if (!busy) retry++ }
            }
            if (busy && categories.isEmpty()) SkeletonLoading(SkeletonLayout.Tabs)
            if (error.isNotBlank()) Text(error, color = Color(0xFFFFB5B9))
        }
    }
    val category = categories.firstOrNull { it.id == selectedId }
    val currentOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(busy, category) {
        if (!busy && category == null) currentOnReady()
    }
    if (category == null) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        header()
        if (busy) SkeletonLoading(SkeletonLayout.Posters, rows = 2, columns = columns)
        if (!busy && error.isBlank()) Text("Нет доступных списков. Войдите в настройках или создайте список.", color = Color.LightGray)
    } else PagedFilms("Избранное", "$provider|${category.id}|$sort|$kind|$revision|$retry", columns, open, header, onReady = onReady) {
        repository.films(category, it, sort, kind)
    }
}

