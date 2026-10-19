package com.bobyl.tv

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.bobyl.tv.SkeletonAsyncImage as AsyncImage
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CinemaModal(title: String, dismiss: () -> Unit, backOnRight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.9f).fillMaxHeight(.9f).clip(RoundedCornerShape(CinemaStyle.PanelCorner))
            .background(CinemaStyle.Background).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, color = CinemaStyle.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            content()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (backOnRight) Arrangement.End else Arrangement.Start) {
                CinematicButton("Назад", false, onClick = dismiss)
            }
        }
    }
}

@Composable
internal fun ConfirmAction(text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().clip(RoundedCornerShape(CinemaStyle.PanelCorner))
            .background(CinemaStyle.Background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Подтверждение", color = CinemaStyle.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(text, color = Color.White)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                CinematicButton("Назад", false, onClick = dismiss)
                CinematicButton("Подтвердить", true) { dismiss(); confirm() }
            }
        }
    }
}

@Composable
internal fun HistoryScreen(provider: String, resume: (WatchRecord) -> Unit, onReady: () -> Unit = {}, refreshRevision: Int = 0, open: (CatalogFilm) -> Unit) {
    val model: HistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "history:$provider")
    val state by model.state.collectAsState()
    var tab by rememberSaveable(provider) { mutableStateOf("continue") }
    val showWatched = tab == "watched"
    val showDeleted = tab == "deleted"
    val hasDeleted = state.deleted.any { it.records.isNotEmpty() || it.server.isNotEmpty() }
    LaunchedEffect(hasDeleted) { if (!hasDeleted && tab == "deleted") tab = "continue" }
    val entries = remember(state.records, state.server, state.deleted, tab) {
        if (showDeleted) state.deleted.flatMap { historyEntries(it.records, it.server) }
        else historyEntries(state.records, state.server).filter { it.watched == showWatched }
    }
    var pendingResume by remember { mutableStateOf<HistoryEntry?>(null) }
    var ratingFilm by rememberSaveable(provider, stateSaver = CatalogFilmStateSaver) { mutableStateOf<CatalogFilm?>(null) }
    pendingResume?.let { entry -> entry.position?.let { record ->
        ResumeHistoryPrompt(record, dismiss = { pendingResume = null }, openCard = {
            pendingResume = null
            open(entry.film)
        }, resume = {
            pendingResume = null
            resume(record)
        })
    } }
    val busy = state.busy
    val currentOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(busy) {
        if (!busy) currentOnReady()
    }
    val error = state.error
    val listState = rememberLazyListState()
    val prefix = if (showDeleted) "deleted:" else ""
    val rowKeys = entries.map { "${prefix}film:${it.key}" }
    val controls = listOf("tabs") + rowKeys.flatMap {
        if (showDeleted) listOf("$it:open", "$it:delete") else listOf("$it:open", "$it:watch", "$it:delete")
    }
    val positions = buildMap {
        var index = 1 + (if (busy) 1 else 0) + (if (error.isNotBlank()) 1 else 0)
        entries.forEach { put("${prefix}film:${it.key}", index++) }
    }
    val (focus, focusRegion) = rememberActionListFocus(controls, positions, listState)
    LaunchedEffect(provider, refreshRevision) { model.refresh(provider) }
    LazyColumn(focusRegion.fillMaxSize().padding(24.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            val tabs = listOf("continue" to "Продолжить", "watched" to "Просмотренные") +
                if (hasDeleted) listOf("deleted" to "Недавно удалённые") else emptyList()
            CatalogTabs(tabs, tab,
                modifier = focus.target("tabs").focusGroup()) {
                tab = it
            }
        }
        if (busy) item { SkeletonLoading(SkeletonLayout.Rows, rows = if (entries.isEmpty()) 3 else 1, actions = if (showDeleted) 1 else 2) }
        if (error.isNotBlank()) item { Text(error, color = Color(0xFFFFB5B9)) }
        items(entries, key = { "${prefix}film:${it.key}" }) { entry ->
            HistoryCard(entry.film, entry.info, if (showDeleted) "Восстановить запись" else "Удалить из аккаунта и с устройства",
                focus, "${prefix}film:${entry.key}", canRemove = !busy, watched = entry.watched,
                watchBusy = entry.key in state.changing, deleteBusy = entry.key in state.removing, restore = showDeleted,
                toggleWatched = if (showDeleted) null else ({
                    if (!entry.watched) ratingFilm = entry.film
                    model.setWatched(provider, entry.key, !entry.watched)
                }),
                sourceLabel = listOfNotNull(if (entry.server.isNotEmpty()) "Аккаунт" else null,
                    if (entry.records.isNotEmpty()) "Устройство" else null).joinToString(" · "),
                open = {
                    val position = entry.position
                    if (!showDeleted && !entry.watched && position != null) pendingResume = entry else open(entry.film)
                }, remove = {
                    if (showDeleted) model.restoreFilm(provider, entry.key) else model.removeFilm(provider, entry.key)
                })
        }
        if (!busy && error.isBlank() && entries.isEmpty()) item {
            Text(if (showWatched) "Просмотренных записей пока нет" else "Список «Продолжить» пуст", color = CinemaStyle.Muted)
        }
    }
    ratingFilm?.let { WatchedFilmDialogs(provider, it, dismiss = { ratingFilm = null }) }
}

@Composable
private fun ResumeHistoryPrompt(record: WatchRecord, dismiss: () -> Unit, openCard: () -> Unit, resume: () -> Unit) {
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(CinemaStyle.PanelCorner))
            .background(CinemaStyle.Background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Продолжить с места остановки?", color = CinemaStyle.Text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text(record.title, color = CinemaStyle.Text, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Сохранено: ${historyPositionLabel(record)}", color = CinemaStyle.Muted, fontSize = 15.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val buttonSize = Modifier.fillMaxWidth().height(48.dp)
                CinematicButton("Продолжить", true, buttonSize, onClick = resume)
                CinematicButton("Открыть карточку", false, buttonSize, onClick = openCard)
                CinematicButton("Назад", false, buttonSize, onClick = dismiss)
            }
        }
    }
}

@Composable
private fun HistoryCard(film: CatalogFilm, info: String, removeLabel: String, focus: ActionListFocus, rowKey: String, canRemove: Boolean = true,
    watched: Boolean = false, watchBusy: Boolean = false, deleteBusy: Boolean = false, restore: Boolean = false, sourceLabel: String = "",
    toggleWatched: (() -> Unit)? = null, open: () -> Unit, remove: (() -> Unit)?) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CinemaStyle.Corner)
    val grayscale = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Row(Modifier.fillMaxWidth().background(if (watched) Color(0xFF242629) else CinemaStyle.Surface, shape).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(focus.target("$rowKey:open").weight(1f).clip(shape)
            .background(if (focused) CinemaStyle.Selected else Color.Transparent)
            .border(if (focused) 3.dp else 0.dp, if (focused) CinemaStyle.Focus else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClickLabel = "Открыть ${film.title}", onClick = open).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(58.dp, 86.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF252832)), contentAlignment = Alignment.Center) {
                if (film.poster.isNotBlank()) AsyncImage(film.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    colorFilter = if (watched) grayscale else null)
                else Text("▶", color = Color.Gray, fontSize = 22.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(film.title, color = if (watched) Color(0xFF8E949B) else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (info.isNotBlank()) Text(info, color = if (watched) Color(0xFF777E86) else CinemaStyle.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sourceLabel.isNotBlank()) Text(sourceLabel, color = CinemaStyle.Muted, fontSize = 12.sp)
            }
            Text("›", color = if (focused) Color.White else Color.Gray, fontSize = 28.sp)
        }
        toggleWatched?.let { toggle ->
            FieldAction(FieldGlyph.Eye, if (watched) "Снять отметку просмотра" else "Отметить как просмотренное",
                enabled = canRemove && !deleteBusy, checked = watched, loading = watchBusy, modifier = focus.target("$rowKey:watch"), onClick = toggle)
        }
        remove?.let { FieldAction(if (restore) FieldGlyph.Restore else FieldGlyph.Delete, removeLabel, enabled = canRemove && !watchBusy,
            loading = deleteBusy, modifier = focus.target("$rowKey:delete"), onClick = it) }
    }
}

@Composable
internal fun UpdatesScreen(provider: String, onReady: () -> Unit = {}, onUpdateCount: (Int) -> Unit = {}, refreshRevision: Int = 0, open: (CatalogFilm) -> Unit) {
    val model: UpdatesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "updates-$provider")
    val state by model.state.collectAsState()
    val rows = state.rows
    var onlyTracked by rememberSaveable(provider) { mutableStateOf(true) }
    val visibleRows = remember(rows, onlyTracked) { if (onlyTracked) rows.filter { it.tracked } else rows }
    val indexedRows = remember(rows, onlyTracked) { rows.withIndex().filter { !onlyTracked || it.value.tracked } }
    val error = state.error
    val busy = state.busy
    val listState = rememberLazyListState()
    val controls = listOf("tabs") + indexedRows.flatMap { row ->
        val key = updateRowKey(row.value)
        listOf("$key:open") + if (row.value.tracked) listOf("$key:delete") else emptyList()
    }
    val positions = buildMap {
        var index = 1 + (if (busy) 1 else 0) + (if (error.isNotBlank()) 1 else 0)
        indexedRows.groupBy { it.value.date }.values.forEach { group ->
            index++
            group.forEach { put(updateRowKey(it.value), index++) }
        }
    }
    val (focus, focusRegion) = rememberActionListFocus(controls, positions, listState)
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnUpdateCount by rememberUpdatedState(onUpdateCount)
    LaunchedEffect(rows, busy) { if (!busy && error.isBlank()) currentOnUpdateCount(state.rows.count { it.tracked }) }
    LaunchedEffect(busy) {
        if (!busy) currentOnReady()
    }
    val historyRevision by ServerHistoryChanges.revision.collectAsState()
    LaunchedEffect(provider, refreshRevision) { model.refresh(provider, historyRevision, force = true) }
    LaunchedEffect(provider, historyRevision) {
        model.refresh(provider, historyRevision)
    }
    LazyColumn(focusRegion.fillMaxSize().padding(24.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            CatalogTabs(listOf("tracked" to "Отслеживаемые", "all" to "Все"), if (onlyTracked) "tracked" else "all",
                modifier = focus.target("tabs").focusGroup()) {
                onlyTracked = it == "tracked"
            }
        }
        if (busy) item { SkeletonLoading(SkeletonLayout.Rows, rows = if (state.rows.isEmpty()) 3 else 1, actions = 1) }
        if (error.isNotBlank()) item { Text(error, color = Color(0xFFFFB5B9)) }
        indexedRows.groupBy { it.value.date }.forEach { (date, updates) ->
            item(key = "date:$date") { Text(date, color = Color.LightGray, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            items(updates, key = { updateRowKey(it.value) }) { indexed ->
                val row = indexed.value
                LaunchedEffect(row.film.url, row.film.poster, busy) {
                    if (!busy && row.film.poster.isBlank()) model.loadPoster(row.film.url)
                }
                UpdateCard(row, !busy, historyFilmKey(row.film.url) in state.removing, focus, updateRowKey(row),
                    posterLoading = row.film.poster.isBlank() && row.film.url !in state.posterFinished,
                    posterError = state.posterErrors[row.film.url].orEmpty(), open = open) { model.remove(provider, row.film.url) }
            }
        }
        if (!busy && error.isBlank() && visibleRows.isEmpty()) item {
            Text(if (onlyTracked) "Новых серий у отслеживаемых сериалов пока нет" else "Обновлений пока нет", color = CinemaStyle.Muted)
        }
    }
}

private fun updateRowKey(row: SeriesUpdate) = "update:${row.date}:${row.film.url}:${row.info}"

@Composable
private fun UpdateCard(update: SeriesUpdate, canRemove: Boolean, removing: Boolean, focus: ActionListFocus, rowKey: String,
    posterLoading: Boolean, posterError: String, open: (CatalogFilm) -> Unit, remove: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CinemaStyle.Corner)
    Row(Modifier.fillMaxWidth().background(CinemaStyle.Surface, shape).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(focus.target("$rowKey:open").weight(1f).clip(shape).background(if (focused) CinemaStyle.Selected else CinemaStyle.Surface)
        .border(if (focused) 3.dp else 0.dp, if (focused) CinemaStyle.Focus else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(role = Role.Button, onClickLabel = "Открыть ${update.film.title}") { open(update.film) }
        .padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(58.dp, 86.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF252832)), contentAlignment = Alignment.Center) {
            if (update.film.poster.isNotBlank()) AsyncImage(update.film.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else if (posterLoading) SkeletonShape(Modifier.fillMaxSize())
            else Text("▶", color = Color.Gray, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(update.film.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (update.info.isNotBlank()) Text(update.info, color = CinemaStyle.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (posterError.isNotBlank()) Text("Постер недоступен. Обновите раздел, чтобы повторить.", color = CinemaStyle.Muted, fontSize = 12.sp)
        }
        if (update.tracked) Text("★", color = CinemaStyle.Accent, fontSize = 22.sp)
        Text("›", color = if (focused) Color.White else Color.Gray, fontSize = 28.sp)
    }
        if (update.tracked) FieldAction(FieldGlyph.Delete, "Удалить из серверного «Продолжить»", enabled = canRemove, loading = removing, modifier = focus.target("$rowKey:delete"), onClick = remove)
    }
}

@Composable
internal fun PersonDialog(person: Person, open: (CatalogFilm) -> Unit, dismiss: () -> Unit) {
    var info by remember(person.url) { mutableStateOf<PersonDetails?>(null) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(person.url, retry) {
        error = ""
        try { info = ExtrasRepository().person(person.url) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = networkErrorMessage(failure) }
    }
    CinemaModal(person.name, dismiss) {
        val listState = rememberLazyListState()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().popupScrollIndicator(listState).padding(end = 10.dp), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { AsyncImage(person.photo, person.name, Modifier.height(150.dp), contentScale = ContentScale.Fit) }
            if (info == null && error.isEmpty()) item {
                SkeletonLoading(SkeletonLayout.Text, rows = 2)
                Spacer(Modifier.height(18.dp))
                SkeletonLoading(SkeletonLayout.Posters, rows = 1, columns = 5)
            }
            if (error.isNotEmpty()) item { Text(error, color = Color.LightGray); CinematicButton("Повторить", true) { retry++ } }
            info?.let { data ->
                readableChunks(data.description).forEachIndexed { index, line ->
                    item(key = "person-info:$index") { ReadableLine(line) }
                }
                data.careers.forEach { (career, films) ->
                    item(key = "career:$career") { ReadableLine(career, heading = true) }
                    item(key = "filmography:$career") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            items(films, key = { it.url }) { film ->
                                PosterCard(film, Modifier.width(140.dp), focused = {}, open = { dismiss(); open(film) })
                            }
                        }
                    }
                }
                if (data.careers.isEmpty()) item { Text("Сайт не предоставил фильмографию", color = Color.LightGray) }
            }
        }
    }
}

@Composable
private fun ReadableLine(text: String, heading: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Text(text, color = Color.White, fontSize = if (heading) 22.sp else 16.sp,
        fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().clip(shape).background(if (focused) CinemaStyle.Selected else Color.Transparent)
            .border(if (focused) 3.dp else 0.dp, if (focused) CinemaStyle.Focus else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }.focusable().padding(horizontal = 10.dp, vertical = 8.dp))
}

/** Keeps long TV copy reachable by D-pad: every chunk is an independent focus/scroll anchor. */
internal fun readableChunks(text: String, targetLength: Int = 220): List<String> {
    if (text.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    text.lines().flatMap { line ->
        line.trim().split(Regex("(?<=[.!?…])\\s+")).filter(String::isNotBlank)
    }.forEach { sentence ->
        if (sentence.length > targetLength) {
            sentence.chunked(targetLength).forEach { result += it.trim() }
        } else if (result.isNotEmpty() && result.last().length + sentence.length + 1 <= targetLength) {
            result[result.lastIndex] = result.last() + " " + sentence
        } else {
            result += sentence
        }
    }
    return result.filter(String::isNotBlank)
}

internal fun openWeb(context: android.content.Context, url: String) {
    require(PlaybackParser.isMediaUrl(url))
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
