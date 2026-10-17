package com.bobyl.tv

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.bobyl.tv.SkeletonAsyncImage as AsyncImage
import com.bobyl.tv.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

private val FocusAccent = CinemaStyle.Accent
private val Secondary = CinemaStyle.Muted
private val HistoryOrange = Color(0xFFFFA64D)

@Composable
internal fun ConnectedCatalog(menu: Int, startup: StartupViewModel, onStartupReady: () -> Unit = {}, onUpdateCount: (Int) -> Unit = {}, refreshRevision: Int = 0) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE) }
    var provider by remember { mutableStateOf(prefs.getString("ownProvider", "hdrzk.org") ?: "hdrzk.org") }
    var providerDraft by rememberSaveable { mutableStateOf(provider) }
    var settingsMessage by remember { mutableStateOf("") }
    var sessionReady by remember(provider) { mutableStateOf(false) }
    var sessionNotice by remember(provider) { mutableStateOf("") }
    var sessionRevision by remember { mutableIntStateOf(0) }
    var columns by remember { mutableIntStateOf(prefs.getInt("catalogColumns", 6).coerceIn(1, 12)) }
    var playerPackage by remember { mutableStateOf(prefs.getString("playerPackage", "") ?: "") }
    val auth = remember { AuthRepository(context.applicationContext) }
    LaunchedEffect(provider, sessionRevision) {
        sessionReady = false
        try {
            // Restore encrypted cookies only. Profile validation belongs to AuthSettings;
            // running it here creates a competing network request during first catalog render.
            startup.prepareSession(provider, menu)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Login panel reports restoration failures; public catalog remains available. */ }
        sessionReady = true
    }
    val model: CatalogViewModel = viewModel(key = "catalog-$menu", factory = CatalogViewModel.Factory)
    val state by model.state.collectAsState()
    val selection by model.selection.collectAsState()
    val suggestions = if (menu == 4) {
        val suggestionsModel: SearchSuggestionsViewModel = viewModel(key = "search-suggestions")
        val suggestionsState by suggestionsModel.state.collectAsState()
        val suggestionQuery = selection.searchDraft.takeUnless { it.trim() == selection.query }.orEmpty()
        LaunchedEffect(provider, suggestionQuery) {
            suggestionsModel.update(provider, suggestionQuery)
        }
        DisposableEffect(suggestionsModel) {
            onDispose { suggestionsModel.stop() }
        }
        if (suggestionQuery.isBlank()) SearchSuggestionsState() else suggestionsState
    } else SearchSuggestionsState()
    val filmRows = remember(state.films, columns) { state.films.chunked(columns) }
    val section = when (menu) {
        0 -> CatalogSection.Popular
        1 -> selection.newsSection
        2 -> CatalogSection.Series
        else -> CatalogSection.Latest
    }
    val activeQuery = if (menu == 4) selection.query else ""
    var detail by remember { mutableStateOf<CatalogFilm?>(null) }
    var resumeRecord by remember { mutableStateOf<WatchRecord?>(null) }
    var lastFocused by rememberSaveable(provider, section, activeQuery, selection.kind) { mutableStateOf("") }
    var pendingFocus by remember(provider, section, activeQuery, selection.kind) { mutableStateOf(lastFocused) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    val kind = if (menu == 2) "2" else selection.kind
    val latestCatalogState by rememberUpdatedState(state)
    val catalogListState = rememberLazyListState()
    LaunchedEffect(menu, provider, activeQuery, section, sessionReady, sessionRevision, kind) {
        if (menu !in listOf(0, 1, 2, 4)) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val layout = catalogListState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to layout.totalItemsCount
        }.distinctUntilChanged().collect { (last, total) ->
            if (sessionReady && total > 0 && last >= total - 3 && latestCatalogState.hasMore && !latestCatalogState.loading &&
                latestCatalogState.request == CatalogRequest(provider, section, activeQuery, kind, sessionRevision)) {
                model.loadMore()
            }
        }
    }
    LaunchedEffect(menu, provider, activeQuery, section, sessionReady, sessionRevision, kind) {
        if (menu == 4 && activeQuery.isBlank()) model.clear()
        else if (sessionReady && menu in listOf(0, 1, 2, 4)) model.load(provider, section, activeQuery, kind = kind, sessionRevision = sessionRevision)
    }
    val currentOnStartupReady by rememberUpdatedState(onStartupReady)
    val startupReady = sessionReady && when (menu) {
        0, 1, 2, 4 -> if (menu == 4 && activeQuery.isBlank()) true else
            state.request == CatalogRequest(provider, section, activeQuery, kind, sessionRevision) &&
                !state.restoring && (state.page > 0 || state.error != null)
        5 -> true
        else -> false // These screens report their own initial request completion.
    }
    LaunchedEffect(startupReady) {
        if (startupReady) currentOnStartupReady()
    }
    if (detail != null) DetailsDialog(provider, playerPackage, detail!!, resumeRecord) { detail = null; resumeRecord = null; libraryRevision++ }
    // These screens start account requests when composed. Wait until cookies are restored.
    if (!sessionReady && menu in listOf(3, 6, 7, 8)) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            SkeletonLoading(if (menu == 7 || menu == 8) SkeletonLayout.Rows else SkeletonLayout.Posters, rows = 2, columns = columns)
        }
        return
    }
    if (menu == 3) { FavoritesScreen(provider, columns, libraryRevision, onReady = onStartupReady) { detail = it }; return }
    if (menu == 6) { CategoriesScreen(provider, columns, onReady = onStartupReady) { detail = it }; return }
    if (menu == 7) { HistoryScreen(provider, { record -> resumeRecord = record; detail = CatalogFilm(record.filmId, record.url, record.title, record.poster, "", "", "", "") }, onReady = onStartupReady, refreshRevision = refreshRevision) { detail = it }; return }
    if (menu == 8) { UpdatesScreen(provider, onReady = onStartupReady, onUpdateCount = onUpdateCount, refreshRevision = refreshRevision) { detail = it }; return }

    // Do not measure a short, partially restored list: it would clamp saved scroll state.
    if (state.restoring) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { SkeletonLoading(SkeletonLayout.Posters, rows = 2, columns = columns) }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()
        .then(if (menu == 5) Modifier.settingsScrollIndicator(catalogListState) else Modifier)
        .padding(start = 24.dp, end = 24.dp)) {
        val cardWidth = ((maxWidth - (columns - 1).dp * 14 - 16.dp) / columns).coerceAtLeast(44.dp)
        LazyColumn(
            state = catalogListState,
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (sessionNotice.isNotBlank()) item { Text(sessionNotice, color = Color(0xFFFFB5B9)) }
            if (menu == 5) {
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSection("Аккаунт") {
                        key(provider) { AuthSettings(provider, auth) { sessionRevision++ } }
                    }
                    AppUpdateSettings()
                    SettingsSection("Подключение") {
                    SettingsChoice("Зеркало сайта", listOf("hdrzk.org" to "По умолчанию", "hdrezka.ag" to "hdrezka.ag"), providerDraft) { providerDraft = it }
                    Input(providerDraft, "https://hdrzk.org", { providerDraft = it })
                    CinematicButton("Сохранить", true) {
                        try {
                            provider = CatalogUrls.provider(providerDraft)
                            prefs.edit().putString("ownProvider", java.net.URI(provider).rawAuthority).apply()
                            settingsMessage = "Адрес сохранён"
                        } catch (error: Exception) { settingsMessage = error.message ?: "Неверный адрес" }
                    }
                    if (settingsMessage.isNotBlank()) Text(settingsMessage, color = Secondary)
                    Text("После смены зеркала выполните вход заново.", color = Secondary, fontSize = 14.sp)
                    }
                    PlaybackSettings(playerPackage, { playerPackage = it; prefs.edit().putString("playerPackage", it).apply() }, columns,
                        { columns = it; prefs.edit().putInt("catalogColumns", it).apply() }, provider = provider)
                    }
                }
            } else {
                if (menu in listOf(0, 1)) item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CatalogTabs(contentTypes, selection.kind, separateAll = true, choose = model::selectKind)
                        if (menu == 1) {
                            SettingsChoice("Подборка", listOf(
                                CatalogSection.Slider to "Горячие новинки", CatalogSection.New to "Новинки",
                                CatalogSection.Latest to "Последние поступления", CatalogSection.Popular to "Популярные",
                                CatalogSection.Soon to "Анонсы", CatalogSection.Watching to "Сейчас смотрят",
                            ).map { it.first.name to it.second }, section.name, compactPopup = true) {
                                model.selectNewsSection(CatalogSection.valueOf(it))
                            }
                        }
                    }
                }
                if (menu == 4) item {
                    SearchInput(selection.searchDraft, suggestions, model::editSearch, model::submitSearch, { detail = it })
                }
                if (menu == 4 && activeQuery.isNotBlank()) item {
                    Text("Результаты поиска «$activeQuery»", color = CinemaStyle.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                items(filmRows, key = { it.first().url }, contentType = { "catalog-row" }) { row ->
                    // A catalog row is capped at the configured column count (normally six).
                    // Keeping it as a plain Row avoids nested LazyColumn/LazyRow focus
                    // remeasurement on every vertical D-pad move.
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { film ->
                            key(film.url) {
                                PosterCard(
                                    film, Modifier.width(cardWidth),
                                    focused = { lastFocused = film.url },
                                    open = { pendingFocus = film.url; detail = film },
                                    restore = detail == null && pendingFocus == film.url,
                                    onRestored = { pendingFocus = "" },
                                )
                            }
                        }
                    }
                }
                if (state.loading) item { SkeletonLoading(SkeletonLayout.Posters, rows = if (state.films.isEmpty()) 2 else 1, columns = columns) }
                state.error?.let { error -> item {
                    Text(error, color = Color(0xFFFFB5B9), fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    CinematicButton("Повторить", true) { model.retry() }
                } }
                if (!state.loading && state.error == null) item {
                    if (!sessionReady) SkeletonLoading(SkeletonLayout.Posters, rows = 2, columns = columns)
                    else if (state.films.isEmpty()) {
                        if (menu != 4 || activeQuery.isNotBlank()) Text("Ничего не найдено", color = Secondary, fontSize = 20.sp)
                    }
                    else if (state.hasMore) CinematicButton("Загрузить ещё", false) { model.loadMore() }
                }
            }
        }
    }
}



@Composable
internal fun PosterCard(film: CatalogFilm, modifier: Modifier, focused: () -> Unit, open: () -> Unit, restore: Boolean = false, onRestored: () -> Unit = {}) {
    val requester = remember { FocusRequester() }
    val latestOnRestored by rememberUpdatedState(onRestored)
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    if (restore) {
        LaunchedEffect(windowFocused) {
            if (windowFocused) {
                withFrameNanos { }
                if (requester.requestFocus()) latestOnRestored()
            }
        }
    }
    var isFocused by remember { mutableStateOf(false) }
    Box(modifier.focusRequester(requester)
        .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) focused() }
        .clickable(onClick = open)) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(CinemaStyle.Corner))
            .background(CinemaStyle.Surface).drawWithContent {
                drawContent()
                if (isFocused) {
                    drawRoundRect(
                        color = CinemaStyle.Accent,
                        cornerRadius = CornerRadius(CinemaStyle.Corner.toPx()),
                        style = Stroke(width = 5.dp.toPx()),
                    )
                }
            }) {
            AsyncImage(film.poster, film.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(8.dp))
        Text(film.title, color = if (isFocused) CinemaStyle.Accent else CinemaStyle.Text,
            fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (film.badge.isNotBlank()) Text(film.badge, color = CinemaStyle.Muted,
            fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    }
}

@Composable
internal fun LoadingIndicator(label: String) {
    val transition = rememberInfiniteTransition(label = "loading")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(950, easing = LinearEasing)), label = "rotation")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Canvas(Modifier.size(28.dp)) { drawArc(FocusAccent, angle, 260f, false, style = Stroke(3.dp.toPx())) }
        Text(label, color = Secondary, fontSize = 18.sp)
    }
}

@Composable
private fun DetailsDialog(provider: String, playerPackage: String, film: CatalogFilm, resumeRecord: WatchRecord? = null, dismiss: () -> Unit) {
    val context = LocalContext.current
    val historyModel: FilmHistoryViewModel = viewModel(key = "film-history:$provider:${film.url}")
    val historyState by historyModel.state.collectAsState()
    val historyRevision by ServerHistoryChanges.revision.collectAsState()
    var childFilm by remember(film.url) { mutableStateOf<CatalogFilm?>(null) }
    var linkedCatalog by remember(film.url) { mutableStateOf<BrowseLink?>(null) }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var person by remember(film.url) { mutableStateOf<Person?>(null) }
    var comments by remember { mutableStateOf(false) }
    var showRatingPrompt by remember(film.url) { mutableStateOf(false) }
    var details by remember(film.url) { mutableStateOf<FilmDetails?>(null) }
    var error by remember(film.url) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var showPlayback by remember { mutableStateOf(resumeRecord != null) }
    LaunchedEffect(provider, film.url, historyRevision, showPlayback) {
        if (!showPlayback) historyModel.load(provider, film.url)
    }
    var download by remember { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val catalogRepository = remember { CatalogRepository() }
    val browseRepository = remember { BrowseRepository() }
    val watchFocus = remember { FocusRequester() }
    val commentsFocus = remember { FocusRequester() }
    val moreFocus = remember { FocusRequester() }
    var moreMenuOpen by remember { mutableStateOf(false) }
    val favoriteFocus = remember { FocusRequester() }
    val watchedFocus = remember { FocusRequester() }
    val ratingFocus = remember { FocusRequester() }
    val detailsScroll = rememberScrollState()
    val scrollStep = with(LocalDensity.current) { 64.dp.toPx() }
    var viewportBottom by remember { mutableFloatStateOf(0f) }
    var readingBottom by remember { mutableFloatStateOf(0f) }
    var readingFocused by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    childFilm?.let { DetailsDialog(provider, playerPackage, it) { childFilm = null } }
    linkedCatalog?.let { link -> CinemaModal(link.title, { linkedCatalog = null }) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedFilms(link.title, link.url, 6, { linkedCatalog = null; childFilm = it }) { browseRepository.filmsAt(link.url, it) }
        }
    } }
    person?.let { PersonDialog(it, { childFilm = it }, { person = null }) }
    if (comments) details?.let { CommentsDialog(provider, it.id) { comments = false } }
    if (showPlayback) details?.playback?.let { playback ->
        PlaybackDialog(provider, playerPackage, details?.title ?: film.title, playback, film.url, film.poster, download, resumeRecord) {
            showPlayback = false
            ServerHistoryChanges.changed()
        }
    }
    LaunchedEffect(film.url, retry) {
        error = null
        try { details = catalogRepository.details(film.url) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = networkErrorMessage(failure) }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
        Box(Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f).clip(RoundedCornerShape(CinemaStyle.PanelCorner)).background(CinemaStyle.Background)) {
        Column(Modifier.fillMaxSize().padding(34.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (historyState.inContinue) WatchStatusIcon(historyState.watched)
                else if (historyState.loading) SkeletonShape(Modifier.size(28.dp))
                Text(details?.title ?: film.title, color = CinemaStyle.Text, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
            }
            if (details == null && error == null) SkeletonLoading(SkeletonLayout.Details,
                Modifier.weight(1f).focusProperties { canFocus = false }.verticalScroll(rememberScrollState()))
            error?.let {
                Text(it, color = Color(0xFFFFB5B9))
                CinematicButton("Повторить", true) { retry++ }
            }
            details?.let { info ->
                LaunchedEffect(info.playback, showPlayback, childFilm, linkedCatalog, person, comments, windowFocused, moreMenuOpen, showRatingPrompt) {
                    if (windowFocused && !showRatingPrompt && !moreMenuOpen && !showPlayback && childFilm == null && linkedCatalog == null && person == null && !comments) {
                        withFrameNanos { }
                        inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                        val target = returnFocus ?: if (info.playback != null) watchFocus else commentsFocus
                        if (target.requestFocus()) returnFocus = null
                    }
                }
                CompositionLocalProvider(LocalCompactFilmActions provides true) {
                LazyRow(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
                    if (info.playback != null) item { FieldAction(FieldGlyph.Play, "Смотреть", primary = true, modifier = Modifier.focusRequester(watchFocus), tooltip = true) { returnFocus = watchFocus; download = false; showPlayback = true } }
                    item { FieldAction(FieldGlyph.Comments, "Комментарии", modifier = Modifier.focusRequester(commentsFocus), tooltip = true) { returnFocus = commentsFocus; comments = true } }
                    item { FilmFavoriteButton(provider, info.id, film.url, info.bookmarks, favoriteFocus,
                        onOpen = { returnFocus = favoriteFocus }, onUpdated = { categories -> details = info.copy(bookmarks = categories) }) }
                    item {
                        FieldAction(FieldGlyph.Eye, if (historyState.watched) "Снять отметку просмотра" else "Отметить просмотренным",
                            enabled = !historyState.loading && !historyState.saving, checked = historyState.watched,
                            loading = historyState.saving, modifier = Modifier.focusRequester(watchedFocus), tooltip = true) {
                            returnFocus = watchedFocus
                            if (!historyState.watched) showRatingPrompt = true
                            historyModel.setWatched(provider, film.copy(id = info.id, title = info.title,
                                poster = info.poster.ifBlank { film.poster }), !historyState.watched)
                        }
                    }
                    item {
                        FilmMoreMenu(moreMenuOpen, onExpandedChange = {
                            moreMenuOpen = it
                            if (it) returnFocus = moreFocus
                        }, triggerFocus = moreFocus, canDownload = info.playback != null, busy = actionBusy,
                            onDownload = { download = true; showPlayback = true },
                            onTrailer = { if (!actionBusy) { actionBusy = true; scope.launch {
                                try { openWeb(context, ExtrasRepository().trailer(provider, info.id)) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { actionMessage = networkErrorMessage(failure) }
                                finally { actionBusy = false }
                            } } },
                            onShare = {
                                try { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "${info.title}\n${film.url}"), "Поделиться")) }
                                catch (_: Exception) { actionMessage = "Нет приложения для отправки ссылки" }
                            })
                    }
                    if (info.id.isNotBlank()) item {
                        PersonalRatingButton(provider, info.id, ratingFocus) {
                            returnFocus = ratingFocus
                            showRatingPrompt = true
                        }
                    }
                    item { FieldAction(FieldGlyph.Back, "Назад", tooltip = true, onClick = dismiss) }
                }
                }
                Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                    viewportBottom = it.positionInRoot().y + it.size.height
                }) {
                Column(Modifier.fillMaxSize().verticalScroll(detailsScroll).padding(end = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.fillMaxWidth()
                    .onGloballyPositioned { readingBottom = it.positionInRoot().y + it.size.height }
                    .onPreviewKeyEvent { event ->
                        if (!readingFocused) return@onPreviewKeyEvent false
                        val delta = when (event.key) {
                            Key.DirectionDown -> (readingBottom - viewportBottom).coerceIn(0f, scrollStep)
                            Key.DirectionUp -> -detailsScroll.value.toFloat().coerceAtMost(scrollStep)
                            else -> 0f
                        }
                        if (delta != 0f) {
                            if (event.type == KeyEventType.KeyDown) scope.launch { detailsScroll.scrollBy(delta) }
                            true
                        } else false
                    }.focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    FilmOverview(info, film.poster, readingFocused, historyState, onReadingFocus = { readingFocused = it })
                }
                if (info.links.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(8.dp)) {
                    items(info.links, key = { it.url }) { link ->
                        val linkFocus = remember { FocusRequester() }
                        CinematicButton(link.title, false, Modifier.focusRequester(linkFocus)) {
                            returnFocus = linkFocus
                            linkedCatalog = link
                        }
                    }
                }
                if (info.creators.isNotEmpty()) PersonRow("Создатели", info.creators) { selected, requester ->
                    returnFocus = requester
                    person = selected
                }
                if (info.actors.isNotEmpty()) PersonRow("В ролях", info.actors) { selected, requester ->
                    returnFocus = requester
                    person = selected
                }
                if (info.schedule.isNotEmpty()) {
                    EpisodeScheduleTable(info.schedule, !actionBusy) { row ->
                        if (!actionBusy) { actionBusy = true; scope.launch {
                            try { ExtrasRepository().watch(provider, row.id); retry++ }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { actionMessage = networkErrorMessage(failure) }
                            finally { actionBusy = false }
                        } }
                    }
                }
                listOf("Части и продолжения" to info.collection, "Похожие фильмы" to info.related).forEach { (label, films) ->
                    if (films.isNotEmpty()) {
                        Text(label, color = Color.White, fontSize = 22.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(films.filterNot { it.url == film.url }, key = { it.url }) { related ->
                                val relatedFocus = remember { FocusRequester() }
                                CinematicButton(related.title, false, Modifier.focusRequester(relatedFocus)) {
                                    returnFocus = relatedFocus
                                    childFilm = related
                                }
                            }
                        }
                    }
                }
                if (actionBusy) LoadingIndicator("Сохраняем…")
                if (actionMessage.isNotBlank()) Text(actionMessage, color = Secondary)
                if (info.playback == null) Text("Сайт пока не предоставил данные для воспроизведения.", color = Secondary, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                }
                if (detailsScroll.maxValue > 0) Canvas(Modifier.align(Alignment.CenterEnd).width(3.dp).fillMaxHeight()) {
                    val viewport = size.height
                    val total = viewport + detailsScroll.maxValue
                    val thumb = (viewport * viewport / total).coerceAtLeast(24.dp.toPx()).coerceAtMost(viewport)
                    drawRoundRect(CinemaStyle.Outline, cornerRadius = CornerRadius(size.width / 2))
                    drawRoundRect(CinemaStyle.Accent,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, (viewport - thumb) * detailsScroll.value / detailsScroll.maxValue),
                        size = androidx.compose.ui.geometry.Size(size.width, thumb), cornerRadius = CornerRadius(size.width / 2))
                }
                }
            }
        }
        }
    }
    if (showRatingPrompt) details?.let { info ->
        FilmRatingDialog(provider, film.copy(id = info.id, title = info.title), dismiss = { showRatingPrompt = false }) { result ->
            details = info.copy(siteRating = result.first, siteVotes = result.second)
        }
    }
}

@Composable
private fun FilmOverview(info: FilmDetails, fallbackPoster: String, readingFocused: Boolean, history: FilmHistoryState,
    onReadingFocus: (Boolean) -> Unit) {
    val ratingPattern = remember { Regex("(IMDb|Кинопоиск)\\s*:?\\s*(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE) }
    val ratings = remember(info.metadata) {
        info.metadata.flatMap { row -> ratingPattern.findAll(row).map { it.groupValues[1] to it.groupValues[2] }.toList() }
    }
    val metadata = remember(info.metadata) { info.metadata.filterNot { ratingPattern.containsMatchIn(it) } }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            AsyncImage(info.poster.ifBlank { fallbackPoster }, null,
                Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(CinemaStyle.Corner)), contentScale = ContentScale.Fit)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (info.originalTitle.isNotBlank()) Text(info.originalTitle, color = Secondary, fontSize = 17.sp, lineHeight = 24.sp)
                val isSeries = info.playback?.isMovie == false || info.schedule.isNotEmpty()
                if (history.watched || history.viewedAt.isNotBlank() || (isSeries && (history.season.isNotBlank() || history.episode.isNotBlank()))) {
                    val progress = if (isSeries) listOfNotNull(history.season.takeIf { it.isNotBlank() }?.let { "$it сезон" },
                        history.episode.takeIf { it.isNotBlank() }?.let { "$it серия" }).joinToString(" • ") else ""
                    val status = if (history.watched) "Просмотрено" else "Последний просмотр"
                    val statusColor = if (history.watched) Color(0xFF77DB94) else HistoryOrange
                    Row(Modifier.clip(RoundedCornerShape(CinemaStyle.Corner)).background(statusColor.copy(alpha = .12f))
                        .border(1.dp, statusColor.copy(alpha = .4f), RoundedCornerShape(CinemaStyle.Corner))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (progress.isBlank()) status else "$status: $progress", color = statusColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            if (history.viewedAt.isNotBlank()) Text(history.viewedAt, color = statusColor.copy(alpha = .8f), fontSize = 13.sp)
                        }
                    }
                }
                if (history.error.isNotBlank()) Text(history.error, color = Secondary, fontSize = 14.sp)
                if (ratings.isNotEmpty() || info.siteRating.isNotBlank()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (info.siteRating.isNotBlank()) FilmRating("HDRezka", info.siteRating, info.siteVotes)
                        ratings.forEach { (source, value) -> FilmRating(source, value) }
                    }
                }
                metadata.forEach { row ->
                    val divider = row.indexOf(':')
                    if (divider in 1..40) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(row.substring(0, divider), color = Secondary, fontSize = 16.sp, lineHeight = 25.sp, modifier = Modifier.width(120.dp))
                            Text(row.substring(divider + 1).trim(), color = CinemaStyle.Text, fontSize = 17.sp, lineHeight = 25.sp, modifier = Modifier.weight(1f))
                        }
                    } else Text(row, color = CinemaStyle.Text, fontSize = 17.sp, lineHeight = 25.sp)
                }
            }
        }
        if (info.description.isNotBlank()) {
            Column(Modifier.widthIn(max = 820.dp).onFocusChanged { onReadingFocus(it.isFocused) }.focusable(),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("О фильме", color = CinemaStyle.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("Прокрутка ↑ ↓", color = if (readingFocused) CinemaStyle.Accent else Secondary, fontSize = 14.sp)
                }
                Text(info.description.trim(), color = CinemaStyle.Text, fontSize = 18.sp, lineHeight = 29.sp)
            }
        }
    }
}

@Composable
private fun FilmRating(source: String, value: String, votes: String = "") {
    Column(Modifier.clip(RoundedCornerShape(CinemaStyle.Corner)).background(CinemaStyle.Surface)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            RatingSiteIcon(source)
            if (!source.equals("IMDb", ignoreCase = true)) Text(source, color = CinemaStyle.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(value, color = CinemaStyle.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (votes.isNotBlank()) Text("$votes голосов", color = Secondary, fontSize = 11.sp)
    }
}

@Composable
private fun RatingSiteIcon(source: String) {
    when {
        source.equals("IMDb", ignoreCase = true) -> Box(Modifier.height(22.dp).clip(RoundedCornerShape(3.dp))
            .background(Color(0xFFF5C518)).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
            Text("IMDb", color = Color(0xFF121212), fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        source.equals("Кинопоиск", ignoreCase = true) -> Canvas(Modifier.size(22.dp)) {
            val orange = Color(0xFFFF6600)
            val unit = size.minDimension / 22f
            val stroke = 2.4f * unit
            fun line(x: Float, y: Float, endX: Float, endY: Float) = drawLine(orange,
                androidx.compose.ui.geometry.Offset(x * unit, y * unit),
                androidx.compose.ui.geometry.Offset(endX * unit, endY * unit), stroke)
            line(4f, 3f, 4f, 19f)
            line(4f, 11f, 13f, 3f)
            line(4f, 11f, 13f, 19f)
            line(13f, 8f, 19f, 5f)
            line(14f, 11f, 21f, 11f)
            line(13f, 14f, 19f, 17f)
        }
        else -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(CinemaStyle.Accent), contentAlignment = Alignment.Center) {
            Text("R", color = CinemaStyle.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun EpisodeScheduleTable(entries: List<ScheduleEntry>, canMark: Boolean, mark: (ScheduleEntry) -> Unit) {
    val seasons = remember(entries) { entries.groupBy { it.group } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Сезоны и серии", color = CinemaStyle.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (entries.any { it.id.isNotBlank() }) {
            Text("Выберите серию и нажмите OK, чтобы изменить отметку просмотра", color = Secondary, fontSize = 14.sp)
        }
        seasons.forEach { (season, rows) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(season.ifBlank { "Список серий" }, color = CinemaStyle.Accent, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("Серий: ${rows.size}", color = Secondary, fontSize = 14.sp)
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(CinemaStyle.Corner)).background(CinemaStyle.Surface)) {
                    ScheduleCells("Серия", "Название", "Дата выхода", "Просмотр", Secondary,
                        Modifier.background(CinemaStyle.Raised), header = true)
                    rows.forEachIndexed { index, entry ->
                        key(season, entry.id, entry.episode, index) {
                            var focused by remember { mutableStateOf(false) }
                            val background = when {
                                focused -> CinemaStyle.Selected
                                index % 2 == 0 -> CinemaStyle.Surface
                                else -> CinemaStyle.Rail
                            }
                            val interactive = entry.id.isNotBlank()
                            val rowModifier = Modifier.fillMaxWidth().background(background)
                                .border(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else Color.Transparent)
                                .then(if (interactive) Modifier.onFocusChanged { focused = it.isFocused }
                                    .clickable { if (canMark) mark(entry) } else Modifier)
                            ScheduleCells(entry.episode.ifBlank { "—" },
                                entry.title.ifBlank { if (entry.episode.isBlank() && entry.releaseDate.isBlank()) entry.label else "—" },
                                entry.releaseDate.ifBlank { "—" },
                                if (entry.watched) "Просмотрено" else "Не отмечено",
                                if (entry.watched) CinemaStyle.Accent else Secondary, rowModifier)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCells(
    episode: String, title: String, date: String, status: String, statusColor: Color,
    modifier: Modifier = Modifier, header: Boolean = false,
) {
    val color = if (header) Secondary else CinemaStyle.Text
    val weight = if (header) FontWeight.Medium else FontWeight.Normal
    Row(modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(episode, color = color, fontSize = 16.sp, lineHeight = 23.sp,
            fontWeight = if (header) weight else FontWeight.Medium, modifier = Modifier.width(92.dp))
        Text(title, color = color, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = weight, modifier = Modifier.weight(1f))
        Text(date, color = color, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = weight, modifier = Modifier.width(160.dp))
        Text(status, color = statusColor, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = weight, modifier = Modifier.width(116.dp))
    }
}

@Composable
private fun PersonRow(title: String, people: List<Person>, open: (Person, FocusRequester) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontSize = 22.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
            items(people) { person ->
                val requester = remember { FocusRequester() }
                PersonCard(person, Modifier.focusRequester(requester)) { open(person, requester) }
            }
        }
    }
}

@Composable
private fun PersonCard(person: Person, modifier: Modifier = Modifier, open: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "personScale")
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.width(150.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(Color(0xFF202126))
            .border(if (focused) 3.dp else 0.dp, if (focused) CinemaStyle.Focus else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = person.url.isNotBlank(), role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = "Открыть информацию о персоне", onClick = open)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(125.dp).clip(RoundedCornerShape(8.dp))) {
            if (person.photo.isNotBlank()) AsyncImage(person.photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(person.name, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (person.job.isNotBlank()) Text(person.job, color = Secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
