package com.bobyl.tv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.bobyl.tv.SkeletonAsyncImage as AsyncImage
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException

internal val contentTypes = listOf("0" to "Всё", "1" to "Фильмы", "2" to "Сериалы", "3" to "Мультфильмы", "4" to "Аниме")

@Composable
internal fun ChoiceRow(options: List<Pair<String, String>>, selected: String, choose: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(options, key = { it.first }) { (id, title) -> CinematicButton(title, id == selected) { choose(id) } }
    }
}

@Composable
internal fun CatalogTabs(options: List<Pair<String, String>>, selected: String, separateAll: Boolean = false, modifier: Modifier = Modifier, choose: (String) -> Unit) {
    val shape = RoundedCornerShape(CinemaStyle.Corner)
    Box(modifier.fillMaxWidth().clip(shape).background(CinemaStyle.Rail).border(1.dp, CinemaStyle.Outline, shape)) {
        if (!separateAll) ChoiceRow(options, selected, choose)
        else LazyRow(contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, (id, title) ->
                item(key = id) { CinematicButton(title, id == selected) { choose(id) } }
                if (index == 0) item(key = "all-divider") { Spacer(Modifier.width(1.dp).height(28.dp).background(CinemaStyle.Outline)) }
            }
        }
    }
}

@Composable
internal fun PagedFilms(title: String, token: String, columns: Int, open: (CatalogFilm) -> Unit,
    header: @Composable () -> Unit = {}, onReady: () -> Unit = {}, load: suspend (Int) -> List<CatalogFilm>) {
    var films by remember(token) { mutableStateOf<List<CatalogFilm>>(emptyList()) }
    var page by rememberSaveable(token) { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember(token) { mutableStateOf(true) }
    var more by remember(token) { mutableStateOf(true) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var pendingFocus by rememberSaveable(token) { mutableStateOf("") }
    var waitForWindowLoss by remember(token) { mutableStateOf(false) }
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        // Arm restoration only after the child dialog has taken the window focus.
        if (!windowFocused) waitForWindowLoss = false
    }
    val currentLoad by rememberUpdatedState(load)
    val currentOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(busy) {
        if (!busy) currentOnReady()
    }
    val rows = remember(films, columns) { films.chunked(columns) }
    val listState = key(token) { rememberLazyListState() }
    val rowCount by rememberUpdatedState(rows.size)
    LaunchedEffect(token, page, retry) {
        busy = true; error = null
        try {
            val next = if (films.isEmpty() && page > 1) buildList {
                for (number in 1..page) addAll(currentLoad(number))
            } else currentLoad(page)
            val combined = (if (page == 1) next else films + next).distinctBy { it.url }
            more = next.isNotEmpty() && (page == 1 || combined.size > films.size)
            films = combined
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = networkErrorMessage(failure) }
        finally { busy = false }
    }
    LaunchedEffect(token, listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            // Header is item 0; request the next page when the penultimate row is visible.
            !busy && error == null && more && rowCount > 0 &&
                lastVisible >= maxOf(1, rowCount - 1)
        }.collect { shouldLoad ->
            if (shouldLoad) {
                // Close the gate before scheduling composition, including manual clicks.
                busy = true
                page++
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        val width = ((maxWidth - 16.dp - 14.dp * (columns - 1)) / columns).coerceAtLeast(44.dp)
        // Keep the saved scroll position until all previously loaded pages are ready.
        // Measuring an empty list here would clamp it to the first item, even on Retry.
        if (page > 1 && films.isEmpty() && (busy || error != null)) {
            Column(Modifier.padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                header()
                if (busy) SkeletonLoading(SkeletonLayout.Posters, rows = 2, columns = columns)
                error?.let {
                    Text(it, color = Color(0xFFFFB5B9))
                    CinematicButton("Повторить", true) {
                        if (!busy) { busy = true; retry++ }
                    }
                }
            }
            return@BoxWithConstraints
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                header()
            }
            items(rows, key = { it.firstOrNull()?.url.orEmpty() }, contentType = { "poster-row" }) { row ->
                // The row is bounded by the configured column count. Keeping it non-lazy
                // avoids a nested lazy layout and its extra focus/measurement work.
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    row.forEach { film ->
                        key(film.url) {
                            PosterCard(
                                film, Modifier.width(width), {},
                                open = { pendingFocus = film.url; waitForWindowLoss = true; open(film) },
                                restore = !waitForWindowLoss && pendingFocus == film.url,
                                onRestored = { pendingFocus = "" },
                            )
                        }
                    }
                }
            }
            if (busy) item { SkeletonLoading(SkeletonLayout.Posters, rows = if (films.isEmpty()) 2 else 1, columns = columns) }
            error?.let { item { Text(it, color = Color(0xFFFFB5B9)); CinematicButton("Повторить", true) {
                if (!busy) { busy = true; retry++ }
            } } }
            if (!busy && error == null) item {
                if (films.isEmpty()) Text("Ничего не найдено", color = Color.LightGray)
                else if (more) CinematicButton("Загрузить ещё", false) {
                    if (!busy && error == null && more) { busy = true; page++ }
                }
            }
        }
    }
}

@Composable
internal fun CategoriesScreen(provider: String, columns: Int, onReady: () -> Unit = {}, open: (CatalogFilm) -> Unit) {
    val repository = remember { BrowseRepository() }
    var filters by remember(provider) { mutableStateOf<BrowseFilters?>(null) }
    var categoryUrl by rememberSaveable(provider) { mutableStateOf("") }
    var genre by rememberSaveable(provider) { mutableStateOf("") }
    var year by rememberSaveable(provider) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(provider, retry) {
        error = null
        try { filters = repository.filters(provider, force = retry > 0); if (categoryUrl.isBlank()) categoryUrl = filters?.categories?.firstOrNull()?.url.orEmpty() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = networkErrorMessage(failure) }
    }
    val category = filters?.categories?.firstOrNull { it.url == categoryUrl }
    val currentOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(category, filters, error) {
        if (category == null && (filters != null || error != null)) currentOnReady()
    }
    if (category == null) Column(Modifier.padding(28.dp)) {
        if (error == null && filters == null) {
            SkeletonLoading(SkeletonLayout.Filters)
            Spacer(Modifier.height(18.dp))
            SkeletonLoading(SkeletonLayout.Posters, rows = 2, columns = columns)
        }
        else { Text(error ?: "Сайт не предоставил категории", color = Color.White); CinematicButton("Повторить", true) { retry++ } }
    } else PagedFilms("Категории", "$provider|$categoryUrl|$genre|$year", columns, open, onReady = onReady, header = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CatalogTabs(filters!!.categories.map { it.url to it.title }, categoryUrl) { categoryUrl = it; genre = "" }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.weight(1f)) {
                    SettingsChoice("Жанр", listOf("" to "Все жанры") + category.genres.map { it.url to it.title }, genre, compactPopup = true) { genre = it }
                }
                Box(Modifier.weight(1f)) {
                    SettingsChoice("Год", listOf("" to "За всё время") + filters!!.years.map { it to it }, year, compactPopup = true) { year = it }
                }
            }
        }
    }) { page -> repository.films(category, genre, year, page) }
}

@Composable
internal fun SearchInput(value: String, suggestions: SearchSuggestionsState, change: (String) -> Unit, search: () -> Unit, open: (CatalogFilm) -> Unit) {
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var speechRequest by remember { mutableIntStateOf(0) }
    var permissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val latestChange by rememberUpdatedState(change)
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; message = "Говорите…" }
            override fun onBeginningOfSpeech() { listening = true }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false; message = "Распознаём…" }
            override fun onError(error: Int) { listening = false; message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не обнаружена"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
                else -> "Не удалось распознать речь"
            } }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { latestChange(it); message = "" }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { recognizer?.destroy() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (!granted) message = "Разрешите доступ к микрофону для голосового поиска"
    }
    LaunchedEffect(speechRequest, permissionGranted) {
        if (speechRequest == 0 || !permissionGranted) return@LaunchedEffect
        val service = recognizer ?: run { message = "На устройстве нет встроенной службы распознавания речи"; return@LaunchedEffect }
        message = "Подготовка микрофона…"
        service.startListening(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
    }
    LaunchedEffect(value) { message = "" }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Input(value, "Название фильма или сериала", change, searchField = true, onSubmit = search) {
            FieldAction(FieldGlyph.Search, "Найти", primary = true, onClick = search)
            FieldAction(FieldGlyph.Clear, "Очистить") { change("") }
            FieldAction(FieldGlyph.Microphone, if (listening) "Остановить запись" else "Голосовой поиск", primary = listening) {
                if (listening) { recognizer?.stopListening(); listening = false }
                else { speechRequest++; if (!permissionGranted) permission.launch(Manifest.permission.RECORD_AUDIO) }
            }
        }
        if (message.isNotBlank()) Text(message, color = Color.LightGray)
        if (suggestions.loading || suggestions.unavailable || suggestions.films.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(CinemaStyle.PanelCorner))
                .background(CinemaStyle.Rail).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Быстрый поиск", color = CinemaStyle.Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (suggestions.loading) SkeletonLoading(SkeletonLayout.Suggestions, rows = 2)
                if (suggestions.unavailable) Text("Быстрый поиск недоступен", color = CinemaStyle.Muted)
                val rows = remember(suggestions.films) { suggestions.films.chunked(2) }
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { film ->
                            key(film.url) { SearchSuggestionCard(film, Modifier.weight(1f)) { open(film) } }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSuggestionCard(film: CatalogFilm, modifier: Modifier = Modifier, open: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CinemaStyle.Corner)
    val year = remember(film.metadata) {
        Regex("(?<!\\d)(?:18|19|20|21)\\d{2}(?:\\s*[–—-]\\s*(?:18|19|20|21)\\d{2})?(?!\\d)")
            .find(film.metadata)?.value.orEmpty()
    }
    Row(modifier.clip(shape).background(if (focused) CinemaStyle.Selected else CinemaStyle.Surface)
        .border(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else Color.Transparent, shape)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = open).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(46.dp).height(69.dp).clip(RoundedCornerShape(4.dp)).background(CinemaStyle.Raised),
            contentAlignment = Alignment.Center) {
            if (film.poster.isNotBlank()) AsyncImage(film.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("Нет\nпостера", color = CinemaStyle.Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(film.title, color = if (focused) CinemaStyle.Accent else CinemaStyle.Text,
                fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(year.ifBlank { "Год не указан" }, color = CinemaStyle.Muted, fontSize = 15.sp)
        }
    }
}
