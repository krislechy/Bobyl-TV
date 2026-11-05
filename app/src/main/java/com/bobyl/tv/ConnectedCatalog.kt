package com.bobyl.tv

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.zIndex
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
private val HistoryOrange = CinemaStyle.Accent

@Composable
internal fun ConnectedCatalog(menu: Int, startup: StartupViewModel, onStartupReady: () -> Unit = {}, onUpdateCount: (Int) -> Unit = {}, refreshRevision: Int = 0, sessionRevision: Int = 0, onSessionChanged: () -> Unit = {},
    accountOpen: Boolean = false, onAccountDismiss: () -> Unit = {}, onAccountOpen: () -> Unit = {}, onNavigate: (Int) -> Unit = {},
    historyTabRequest: String? = null, onHistoryTabHandled: () -> Unit = {}, onHistoryNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE) }
    var provider by remember { mutableStateOf(prefs.getString("ownProvider", "hdrzk.org") ?: "hdrzk.org") }
    var providerDraft by rememberSaveable { mutableStateOf(provider) }
    var settingsMessage by remember { mutableStateOf("") }
    var sessionReady by remember(provider) { mutableStateOf(false) }
    var sessionNotice by remember(provider) { mutableStateOf("") }
    var columns by remember { mutableIntStateOf(prefs.getInt("catalogColumns", 6).coerceIn(1, 12)) }
    var playerPackage by remember { mutableStateOf(prefs.getString("playerPackage", "") ?: "") }
    val auth = remember { AuthRepository(context.applicationContext) }
    val dashboard: HomeDashboardViewModel = viewModel(key = "home-dashboard")
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
    LaunchedEffect(provider, sessionRevision, sessionReady, refreshRevision) {
        if (sessionReady) dashboard.loadAccount(provider, sessionRevision, refreshRevision)
    }
    if (accountOpen) key(provider, sessionRevision) { AccountDialog(provider, auth, changed = onSessionChanged, dismiss = onAccountDismiss) }
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
    val section = when (menu) {
        0 -> CatalogSection.Popular
        1 -> selection.newsSection
        2 -> CatalogSection.Series
        else -> CatalogSection.Latest
    }
    val activeQuery = if (menu == 4) selection.query else ""
    var detail by rememberSaveable(provider, stateSaver = CatalogFilmStateSaver) { mutableStateOf<CatalogFilm?>(null) }
    var resumeRecord by rememberSaveable(provider, stateSaver = WatchRecordStateSaver) { mutableStateOf<WatchRecord?>(null) }
    var startPlayback by rememberSaveable(provider) { mutableStateOf(false) }
    var lastFocused by rememberSaveable(provider, section, activeQuery, selection.kind) { mutableStateOf("") }
    var pendingFocus by remember(provider, section, activeQuery, selection.kind) { mutableStateOf(lastFocused) }
    var appliedRefreshRevision by remember { mutableIntStateOf(refreshRevision) }
    var catalogRefreshRevision by remember { mutableIntStateOf(refreshRevision) }
    LaunchedEffect(refreshRevision, detail) {
        if (detail == null) appliedRefreshRevision = refreshRevision
    }
    val kind = if (menu == 2) "2" else selection.kind
    val latestCatalogState by rememberUpdatedState(state)
    val catalogListState = rememberLazyListState()
    val gridLayout = remember(menu, sessionNotice, activeQuery, state.films, state.loading, state.error, columns) {
        CatalogGridLayout(columns, buildList {
            if (sessionNotice.isNotBlank()) add("catalog-notice")
            if (menu == 0) add("home-filters")
            if (menu == 1) add("news-filters")
            if (menu == 4) {
                add("search-input")
                if (activeQuery.isNotBlank()) add("search-results")
            }
        }, state.films.map { it.url }, buildList {
            if (state.loading) add("catalog-loading")
            if (state.error != null) add("catalog-error")
            if (!state.loading && state.error == null) add("catalog-footer")
        })
    }
    val latestGridLayout by rememberUpdatedState(gridLayout)
    val catalogGridState = rememberCatalogGridState(gridLayout, catalogListState,
        ready = !state.restoring && sessionReady && ((menu == 4 && activeQuery.isBlank()) ||
            state.request == CatalogRequest(provider, section, activeQuery, kind, sessionRevision) &&
            (state.page > 0 || state.error != null)))
    LaunchedEffect(menu, pendingFocus, state.request, state.loading, state.restoring, state.films) {
        if (menu == 0 && pendingFocus.startsWith("continue:") && !state.loading && !state.restoring &&
            state.request == CatalogRequest(provider, section, activeQuery, kind, sessionRevision)) {
            val fallback = state.films.firstOrNull()?.url.orEmpty()
            catalogGridState.scrollToItem(0)
            lastFocused = fallback
            pendingFocus = fallback
        }
    }
    LaunchedEffect(menu, provider, activeQuery, section, sessionReady, sessionRevision, kind) {
        if (menu !in listOf(1, 2, 4)) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            val layout = catalogGridState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            last to layout.totalItemsCount
        }.distinctUntilChanged().collect { (last, total) ->
            if (sessionReady && total > 0 && latestGridLayout.nearEnd(last) && latestCatalogState.hasMore && !latestCatalogState.loading &&
                latestCatalogState.request == CatalogRequest(provider, section, activeQuery, kind, sessionRevision)) {
                model.loadMore()
            }
        }
    }
    LaunchedEffect(menu, provider, activeQuery, section, sessionReady, sessionRevision, kind, appliedRefreshRevision, detail != null) {
        if (menu == 4 && activeQuery.isBlank()) model.clear()
        else if (sessionReady && detail == null && menu in listOf(1, 2, 4)) {
            model.load(provider, section, activeQuery, kind = kind, sessionRevision = sessionRevision)
            if (catalogRefreshRevision != appliedRefreshRevision) model.refreshIfStale(force = true)
            catalogRefreshRevision = appliedRefreshRevision
        }
    }
    val currentOnStartupReady by rememberUpdatedState(onStartupReady)
    val startupReady = sessionReady && when (menu) {
        1, 2, 4 -> if (menu == 4 && activeQuery.isBlank()) true else
            state.request == CatalogRequest(provider, section, activeQuery, kind, sessionRevision) &&
                !state.restoring && (state.page > 0 || state.error != null)
        5 -> true
        else -> false // These screens report their own initial request completion.
    }
    LaunchedEffect(startupReady) {
        if (startupReady) currentOnStartupReady()
    }
    if (sessionReady && detail != null) key(provider, sessionRevision) {
        DetailsDialog(provider, playerPackage, detail!!, resumeRecord, startPlayback) {
            detail = null; resumeRecord = null; startPlayback = false
        }
    }
    // These screens start account requests when composed. Wait until cookies are restored.
    if (!sessionReady && menu in listOf(3, 6, 7, 8)) {
        Box(Modifier.fillMaxSize().padding(start = CinemaContentHorizontal, end = CinemaContentHorizontal, top = CinemaContentTop)) {
            SkeletonLoading(if (menu == 7 || menu == 8) SkeletonLayout.Rows else SkeletonLayout.Posters, rows = 2, columns = columns)
        }
        return
    }
    if (menu == 3) { FavoritesScreen(provider, columns, appliedRefreshRevision, onReady = onStartupReady,
        sessionRevision = sessionRevision, detailOpen = detail != null) { detail = it }; return }
    if (menu == 6) { CategoriesScreen(provider, columns, onReady = onStartupReady, refreshRevision = appliedRefreshRevision, sessionRevision = sessionRevision) { detail = it }; return }
    if (menu == 7) {
        key(provider, sessionRevision) {
            HistoryScreen(provider, { record -> resumeRecord = record; detail = CatalogFilm(record.filmId, record.url, record.title, record.poster, "", "", "", "") },
                onReady = onStartupReady, refreshRevision = appliedRefreshRevision, sessionRevision = sessionRevision,
                requestedTab = historyTabRequest, onRequestedTabHandled = onHistoryTabHandled) { detail = it }
        }
        return
    }
    if (menu == 8) {
        key(provider, sessionRevision) {
            UpdatesScreen(provider, onReady = onStartupReady, onUpdateCount = onUpdateCount,
                refreshRevision = appliedRefreshRevision, sessionRevision = sessionRevision) { detail = it }
        }
        return
    }
    if (menu == 0) {
        if (!sessionReady) Box(Modifier.fillMaxSize().padding(horizontal = CinemaContentHorizontal, vertical = CinemaContentTop)) {
            SkeletonLoading(SkeletonLayout.Details)
        } else HomeDashboard(provider, sessionRevision, appliedRefreshRevision, dashboard,
            onReady = onStartupReady, onUpdateCount = onUpdateCount, onAccount = onAccountOpen, detailOpen = detail != null,
            historyNavigate = onHistoryNavigate,
            navigate = onNavigate, open = { film, record, play ->
                resumeRecord = record; startPlayback = play; detail = film
            })
        return
    }

    // Do not measure a short, partially restored list: it would clamp saved scroll state.
    if (state.restoring) {
        Box(Modifier.fillMaxSize().padding(start = CinemaContentHorizontal, end = CinemaContentHorizontal, top = CinemaContentTop)) { SkeletonLoading(SkeletonLayout.Posters, rows = 2, columns = columns) }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()
        .then(if (menu == 5) Modifier.settingsScrollIndicator(catalogListState) else Modifier)
        .padding(horizontal = CinemaContentHorizontal)) {
        val viewportWidth = maxWidth
        val cardWidth = catalogCardWidth(viewportWidth, columns)
        val contentPadding = PaddingValues(top = CinemaContentTop, bottom = 32.dp)
        if (menu == 5) LazyColumn(
            modifier = Modifier.cinemaFilmViewport(),
            state = catalogListState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (sessionNotice.isNotBlank()) item { Text(sessionNotice, color = Color(0xFFFFB5B9)) }
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppUpdateSettings(first = true)
                    SettingsSection("Подключение", first = !GitHubAppUpdates.enabled) {
                    SettingsSingle {
                        SettingsChoice("Зеркало сайта", listOf("hdrzk.org" to "По умолчанию", "hdrezka.ag" to "hdrezka.ag"), providerDraft) { providerDraft = it }
                    }
                    SettingsSingle {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) { Input(providerDraft, "https://hdrzk.org", { providerDraft = it }) }
                            FieldAction(FieldGlyph.Save, "Сохранить", primary = true, tooltip = true) {
                                try {
                                    provider = CatalogUrls.provider(providerDraft)
                                    prefs.edit().putString("ownProvider", java.net.URI(provider).rawAuthority).apply()
                                    settingsMessage = ""
                                    onSessionChanged()
                                } catch (error: Exception) { settingsMessage = error.message ?: "Неверный адрес" }
                            }
                        }
                    }
                    if (settingsMessage.isNotBlank()) Text(settingsMessage, color = Secondary)
                    Text("После смены зеркала выполните вход заново.", color = Secondary, fontSize = 14.sp)
                    }
                    PlaybackSettings(playerPackage, { playerPackage = it; prefs.edit().putString("playerPackage", it).apply() }, columns,
                        { columns = it; prefs.edit().putInt("catalogColumns", it).apply() }, provider = provider)
                    }
                }
        } else LazyVerticalGrid(
            columns = remember(columns, cardWidth) { CatalogGridCells(columns, cardWidth) },
            // The viewport is the original full width, outside the grid's card-only gutters.
            modifier = Modifier.cinemaFilmViewport(),
            state = catalogGridState,
            contentPadding = PaddingValues(start = CatalogCardGutter, end = CatalogCardGutter,
                top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(CatalogCardSpacing),
            verticalArrangement = Arrangement.spacedBy(if (menu == 0) 12.dp else 16.dp),
        ) {
                if (sessionNotice.isNotBlank()) item(key = "catalog-notice", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogGridFullSpan(viewportWidth) { Text(sessionNotice, color = Color(0xFFFFB5B9)) }
                }
                if (menu == 0) item(key = "home-filters", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogGridFullSpan(viewportWidth) {
                        CatalogTabs(contentTypes, selection.kind, separateAll = true, choose = model::selectKind)
                    }
                }
                if (menu == 1) item(key = "news-filters", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogGridFullSpan(viewportWidth) {
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
                }
                if (menu == 4) item(key = "search-input", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogGridFullSpan(viewportWidth) {
                        SearchInput(selection.searchDraft, suggestions, model::editSearch, model::submitSearch, { detail = it })
                    }
                }
                if (menu == 4 && activeQuery.isNotBlank()) item(key = "search-results", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogGridFullSpan(viewportWidth) {
                        Text("Результаты поиска «$activeQuery»", color = CinemaStyle.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                gridItems(state.films, key = { it.url }, contentType = { "catalog-film" }) { film ->
                    PosterCard(
                        film, Modifier.width(cardWidth),
                        focused = { lastFocused = film.url },
                        open = { pendingFocus = film.url; detail = film },
                        restore = detail == null && pendingFocus == film.url,
                        onRestored = { pendingFocus = "" },
                    )
                }
                if (state.loading) item(key = "catalog-loading", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogGridFullSpan(viewportWidth) { SkeletonLoading(SkeletonLayout.Posters, rows = if (state.films.isEmpty()) 2 else 1, columns = columns) }
                }
                state.error?.let { error -> item(key = "catalog-error", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogGridFullSpan(viewportWidth) {
                        Text(error, color = Color(0xFFFFB5B9), fontSize = 17.sp)
                        Spacer(Modifier.height(10.dp))
                        CinematicButton("Повторить", true) { model.retry() }
                    }
                } }
                if (!state.loading && state.error == null) item(key = "catalog-footer", span = { GridItemSpan(maxLineSpan) }) {
                    CatalogGridFullSpan(viewportWidth) {
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
    val focusState = remember { mutableStateOf(false) }
    var isFocused by focusState
    val focusZIndex = remember(focusState) {
        Modifier.layout { measurable, constraints ->
            measureCinemaFocusZIndex(measurable, constraints, focusState)
        }
    }
    val frameTarget = rememberCinemaFilmFocusTarget(isFocused)
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Box(modifier.then(focusZIndex).focusRequester(requester)
        .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) focused() }
        .clickable(interactionSource = interactions, indication = null, onClick = open)) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)
            .cinemaSurface(isFocused, pressed = pressed, scaleOnFocus = true, focusFrameTarget = frameTarget)) {
            AsyncImage(film.poster, film.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(8.dp))
        Text(film.title, color = if (isFocused) CinemaStyle.Accent else CinemaStyle.Text,
            fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val metadata = film.badge.ifBlank { film.metadata }
        if (metadata.isNotBlank()) Text(metadata, color = CinemaStyle.Muted,
            fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    }
}

@Composable
internal fun LoadingIndicator(label: String) {
    val angle = if (cinemaMotionEnabled()) {
        val transition = rememberInfiniteTransition(label = "loading")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(950, easing = LinearEasing)), label = "rotation")
    } else remember { mutableFloatStateOf(0f) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Canvas(Modifier.size(28.dp)) { drawArc(FocusAccent, angle.value, 260f, false, style = Stroke(3.dp.toPx())) }
        Text(label, color = Secondary, fontSize = 18.sp)
    }
}

@Composable
private fun DetailsDialog(provider: String, playerPackage: String, film: CatalogFilm, resumeRecord: WatchRecord? = null,
    startPlayback: Boolean = false, dismiss: () -> Unit) {
    CardViewModelScope(provider, film.url) { DetailsDialogContent(provider, playerPackage, film, resumeRecord, startPlayback, dismiss) }
}

@Composable
private fun DetailsDialogContent(provider: String, playerPackage: String, film: CatalogFilm, resumeRecord: WatchRecord?, startPlayback: Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    val actions = LocalFilmActions.current
    val actionKey = currentFilmActionKey(provider, film.url)
    val application = context.applicationContext as android.app.Application
    val historyModel: FilmHistoryViewModel = viewModel(key = "film-history:${actionKey.film}") {
        FilmHistoryViewModel(application, actions, actionKey)
    }
    val historyState by historyModel.state.collectAsState()
    val historyRevision by ServerHistoryChanges.revision.collectAsState()
    var childFilm by rememberSaveable(film.url, stateSaver = CatalogFilmStateSaver) { mutableStateOf<CatalogFilm?>(null) }
    var linkedCatalog by remember(film.url) { mutableStateOf<BrowseLink?>(null) }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var person by remember(film.url) { mutableStateOf<Person?>(null) }
    var comments by remember { mutableStateOf(false) }
    var showRatingPrompt by rememberSaveable(film.url) { mutableStateOf(false) }
    var showWatchedDialogs by rememberSaveable(film.url) { mutableStateOf(false) }
    var details by remember(film.url) { mutableStateOf<FilmDetails?>(null) }
    var error by remember(film.url) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var showPlayback by rememberSaveable(provider, film.url) { mutableStateOf(startPlayback || resumeRecord != null) }
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
    val detailsScroll = rememberLazyListState()
    val scrollStep = with(LocalDensity.current) { 64.dp.toPx() }
    var viewportBottom by remember { mutableFloatStateOf(0f) }
    var readingBottom by remember { mutableFloatStateOf(0f) }
    var readingFocused by remember { mutableStateOf(false) }
    var returnFocus by remember { mutableStateOf<FocusRequester?>(null) }
    childFilm?.let { DetailsDialog(provider, playerPackage, it) { childFilm = null } }
    linkedCatalog?.let { link -> CinemaModal(link.title, { linkedCatalog = null }) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PagedFilms(link.title, link.url, 6, { linkedCatalog = null; childFilm = it }) { page, force -> browseRepository.filmsAt(link.url, page, force) }
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
        try {
            actions.awaitSettled(actionKey)
            actionMessage = actions.latestError(actionKey)
            details = catalogRepository.details(film.url)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = networkErrorMessage(failure) }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CinemaFocusFrameHost {
        val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
        Box(Modifier.fillMaxSize().padding(12.dp)) {
        Box(Modifier.fillMaxSize()
            .shadow(24.dp, RoundedCornerShape(CinemaStyle.PanelCorner), clip = false)
            .clip(RoundedCornerShape(CinemaStyle.PanelCorner)).cinemaBackdrop()
            .border(1.dp, CinemaStyle.Outline.copy(alpha = .6f), RoundedCornerShape(CinemaStyle.PanelCorner))) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (historyState.inContinue) WatchStatusIcon(historyState.watched)
                else if (historyState.loading) SkeletonShape(Modifier.size(28.dp))
                Text(details?.title ?: film.title, color = CinemaStyle.Text, style = CinemaStyle.Display,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (details == null && error == null) SkeletonLoading(SkeletonLayout.Details,
                Modifier.weight(1f).focusProperties { canFocus = false }.verticalScroll(rememberScrollState()))
            error?.let {
                Text(it, color = CinemaStyle.Error)
                CinematicButton("Повторить", true) { retry++ }
            }
            details?.let { info ->
                LaunchedEffect(info.playback, showPlayback, childFilm, linkedCatalog, person, comments, windowFocused, moreMenuOpen, showRatingPrompt, showWatchedDialogs) {
                    if (windowFocused && !showRatingPrompt && !showWatchedDialogs && !moreMenuOpen && !showPlayback && childFilm == null && linkedCatalog == null && person == null && !comments) {
                        withFrameNanos { }
                        inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                        val target = returnFocus ?: if (info.playback != null) watchFocus else commentsFocus
                        if (target.requestFocus()) returnFocus = null
                    }
                }
                CompositionLocalProvider(LocalCompactFilmActions provides true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CinemaButtonFocusGroup {
                LazyRow(Modifier.weight(1f).cinemaFilmViewport(), verticalAlignment = Alignment.CenterVertically,
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
                            if (!historyState.watched) showWatchedDialogs = true
                            historyModel.setWatched(provider, film.copy(id = info.id, title = info.title,
                                poster = info.poster.ifBlank { film.poster }), !historyState.watched)
                        }
                    }
                    if (info.id.isNotBlank()) item {
                        PersonalRatingButton(provider, info.id, ratingFocus) {
                            returnFocus = ratingFocus
                            showRatingPrompt = true
                        }
                    }
                }
                }
                CinemaButtonFocusGroup {
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
                FieldAction(FieldGlyph.Back, "Назад", tooltip = true, onClick = dismiss)
                }
                }
                }
                Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                    viewportBottom = it.positionInRoot().y + it.size.height
                }) {
                LazyColumn(state = detailsScroll,
                    modifier = Modifier.fillMaxSize().popupScrollIndicator(detailsScroll).padding(end = 18.dp).cinemaFilmViewport(),
                    contentPadding = PaddingValues(bottom = 8.dp)) {
                item(key = "overview", contentType = "overview") {
                Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)
                    .onGloballyPositioned { readingBottom = it.positionInRoot().y + it.size.height }
                    .onPreviewKeyEvent { event ->
                        if (!readingFocused) return@onPreviewKeyEvent false
                        val delta = when (event.key) {
                            Key.DirectionDown -> (readingBottom - viewportBottom).coerceIn(0f, scrollStep)
                            Key.DirectionUp -> if (detailsScroll.canScrollBackward) -scrollStep else 0f
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
                }
                if (info.links.isNotEmpty()) item(key = "links", contentType = "links") {
                CinemaButtonFocusGroup {
                LazyRow(Modifier.padding(bottom = 20.dp).cinemaFilmViewport(), horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(8.dp)) {
                    items(info.links, key = { it.url }) { link ->
                        val linkFocus = remember { FocusRequester() }
                        CinematicButton(link.title, false, Modifier.focusRequester(linkFocus)) {
                            returnFocus = linkFocus
                            linkedCatalog = link
                        }
                    }
                }
                }
                }
                if (info.creators.isNotEmpty()) item(key = "creators", contentType = "people") {
                    Box(Modifier.padding(bottom = 20.dp)) {
                        PersonRow("Создатели", info.creators) { selected, requester ->
                            returnFocus = requester
                            person = selected
                        }
                    }
                }
                if (info.actors.isNotEmpty()) item(key = "actors", contentType = "people") {
                    Box(Modifier.padding(bottom = 20.dp)) {
                        PersonRow("В ролях", info.actors) { selected, requester ->
                            returnFocus = requester
                            person = selected
                        }
                    }
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
                        item(key = "related:$label", contentType = "related") {
                        Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(label, color = Color.White, fontSize = 22.sp)
                        LazyRow(Modifier.cinemaFilmViewport(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(films.filterNot { it.url == film.url }, key = { it.url }) { related ->
                                val relatedFocus = remember { FocusRequester() }
                                CinematicButton(related.title, false, Modifier.focusRequester(relatedFocus), filmFocusFrame = true) {
                                    returnFocus = relatedFocus
                                    childFilm = related
                                }
                            }
                        }
                        }
                        }
                    }
                }
                if (actionBusy) item(key = "action-busy", contentType = "notice") {
                    Box(Modifier.padding(bottom = 20.dp)) { LoadingIndicator("Сохраняем…") }
                }
                if (actionMessage.isNotBlank()) item(key = "action-message", contentType = "notice") {
                    Text(actionMessage, color = Secondary, modifier = Modifier.padding(bottom = 20.dp))
                }
                if (info.playback == null) item(key = "video-unavailable", contentType = "notice") {
                    Text("Видео пока недоступно", color = Secondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 20.dp))
                }
                }
                }
            }
        }
        }
    }
    }
    }
    if (showWatchedDialogs) details?.let { info ->
        WatchedFilmDialogs(provider, film.copy(id = info.id, title = info.title), dismiss = { showWatchedDialogs = false },
            onBookmarksRemoved = { categories -> details = details?.copy(bookmarks = categories) },
            onRated = { result -> details = details?.copy(siteRating = result.first, siteVotes = result.second) })
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
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            AsyncImage(info.poster.ifBlank { fallbackPoster }, null,
                Modifier.width(164.dp).height(246.dp)
                    .shadow(12.dp, RoundedCornerShape(CinemaStyle.Corner), clip = false)
                    .clip(RoundedCornerShape(CinemaStyle.Corner)), contentScale = ContentScale.Fit)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (info.originalTitle.isNotBlank()) Text(info.originalTitle, color = Secondary, fontSize = 17.sp, lineHeight = 24.sp)
                val isSeries = info.playback?.isMovie == false || info.schedule.isNotEmpty()
                if (history.watched || history.viewedAt.isNotBlank() || (isSeries && (history.season.isNotBlank() || history.episode.isNotBlank()))) {
                    val progress = if (isSeries) listOfNotNull(history.season.takeIf { it.isNotBlank() }?.let { "$it сезон" },
                        history.episode.takeIf { it.isNotBlank() }?.let { "$it серия" }).joinToString(" • ") else ""
                    val status = if (history.watched) "Просмотрено" else "Последний просмотр"
                    val statusColor = if (history.watched) CinemaStyle.Success else HistoryOrange
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
                Text(info.description.trim(), color = CinemaStyle.Text, fontSize = 17.sp, lineHeight = 28.sp)
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

private fun LazyListScope.EpisodeScheduleTable(entries: List<ScheduleEntry>, canMark: Boolean, mark: (ScheduleEntry) -> Unit) {
    val seasons = entries.groupBy { it.group }
    seasons.entries.forEachIndexed { seasonIndex, (season, rows) ->
        rows.forEachIndexed { index, entry ->
            item(key = "schedule:$season:row:${entry.id}:${entry.episode}:$index",
                contentType = if (index == 0) "schedule-start" else "schedule-row") {
                // A lazy focus search must meet an episode in the very next item.
                // Separate non-focusable header items exhaust its beyond-bounds budget
                // after the large cast row, trapping D-pad Down on the last actor.
                Column(Modifier.fillMaxWidth().padding(bottom = if (index == rows.lastIndex) 18.dp else 0.dp)) {
                if (index == 0) {
                    if (seasonIndex == 0) Text("Сезоны и серии", color = CinemaStyle.Text,
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 18.dp))
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(season.ifBlank { "Список серий" }, color = CinemaStyle.Accent, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text("Серий: ${rows.size}", color = Secondary, fontSize = 14.sp)
                    }
                    ScheduleCells("Серия", "Название", "Дата выхода", "Просмотр", Secondary,
                        Modifier.clip(RoundedCornerShape(topStart = CinemaStyle.Corner, topEnd = CinemaStyle.Corner))
                            .background(CinemaStyle.Raised), header = true)
                }
                var focused by remember { mutableStateOf(false) }
                val background = when {
                    focused -> CinemaStyle.Selected
                    index % 2 == 0 -> CinemaStyle.Surface
                    else -> CinemaStyle.Rail
                }
                val interactive = entry.id.isNotBlank()
                val shape = if (index == rows.lastIndex)
                    RoundedCornerShape(bottomStart = CinemaStyle.Corner, bottomEnd = CinemaStyle.Corner)
                    else RoundedCornerShape(0.dp)
                val rowModifier = Modifier.fillMaxWidth().clip(shape).background(background)
                    .border(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }
                    .then(if (interactive) Modifier.clickable { if (canMark) mark(entry) } else Modifier.focusable())
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
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Column(
        modifier.width(150.dp).zIndex(if (focused) 1f else 0f)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = person.url.isNotBlank(), role = androidx.compose.ui.semantics.Role.Button,
                interactionSource = interactions, indication = null,
                onClickLabel = "Открыть информацию о персоне", onClick = open),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp).cinemaSurface(focused, pressed = pressed,
            enabled = person.url.isNotBlank(), scaleOnFocus = true)) {
            if (person.photo.isNotBlank()) AsyncImage(person.photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(person.name, color = CinemaStyle.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (person.job.isNotBlank()) Text(person.job, color = Secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
