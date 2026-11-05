package com.bobyl.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.bobyl.tv.data.CatalogFilm
import com.bobyl.tv.data.SeriesUpdate
import com.bobyl.tv.data.WatchRecord
import com.bobyl.tv.data.AccountSessionGuard
import com.bobyl.tv.data.LibraryRepository
import kotlinx.coroutines.delay
import java.time.LocalTime

@Composable
internal fun HomeDashboard(provider: String, sessionRevision: Int, refreshRevision: Int,
    dashboard: HomeDashboardViewModel, onReady: () -> Unit, onUpdateCount: (Int) -> Unit,
    onAccount: () -> Unit, detailOpen: Boolean, historyNavigate: (String) -> Unit,
    navigate: (Int) -> Unit, open: (CatalogFilm, WatchRecord?, Boolean) -> Unit) {
    val state by dashboard.state.collectAsState()
    val libraryChanges by LibraryRepository.changes.collectAsState()
    val favoritesRevision = libraryChanges[AccountSessionGuard.shared.current(provider)] ?: 0
    val historyModel: HistoryViewModel = viewModel(key = "history:$provider:$sessionRevision")
    val history by historyModel.state.collectAsState()
    val updatesModel: UpdatesViewModel = viewModel(key = "updates-$provider:$sessionRevision")
    val updates by updatesModel.state.collectAsState()
    val entries = remember(history.records, history.server) { historyEntries(history.records, history.server) }
    val continuing = remember(entries) { entries.filterNot { it.watched } }
    val watched = remember(entries) { entries.count { it.watched } }
    val featured = remember(updates.rows) { updates.rows.filter { it.tracked }.distinctBy { it.film.url }.take(5) }
    var selectedFeature by rememberSaveable(provider) { mutableIntStateOf(0) }
    val currentFeature = featured.getOrNull(selectedFeature.coerceAtMost((featured.size - 1).coerceAtLeast(0)))
    var pendingFocus by rememberSaveable(provider) { mutableStateOf("") }
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); hour = LocalTime.now().hour } }
    LaunchedEffect(provider, sessionRevision, refreshRevision) {
        dashboard.loadContent(provider, sessionRevision, refreshRevision)
        historyModel.load(provider, refreshRevision)
    }
    LaunchedEffect(provider, sessionRevision, refreshRevision, favoritesRevision, detailOpen) {
        dashboard.loadFavorites(provider, sessionRevision, refreshRevision, favoritesRevision, visible = !detailOpen)
    }
    LaunchedEffect(provider, sessionRevision, refreshRevision) { updatesModel.preview(provider, refreshRevision) }
    LaunchedEffect(provider, sessionRevision, currentFeature?.film?.url) {
        val url = currentFeature?.film?.url.orEmpty()
        dashboard.loadFeatured(url)
    }
    LaunchedEffect(updates.rows, updates.busy) {
        if (!updates.busy) onUpdateCount(updates.rows.count { it.tracked })
    }
    val currentOnReady by rememberUpdatedState(onReady)
    val prepared = homeContentPrepared(state, history.busy, updates.busy, currentFeature?.film?.url.orEmpty())
    LaunchedEffect(prepared) { if (prepared) currentOnReady() }
    val scroll = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = CinemaContentHorizontal).cinemaFilmViewport(),
        state = scroll, contentPadding = PaddingValues(top = CinemaContentTop, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "greeting") {
            Text(homeGreeting(hour) + state.nickname.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty(),
                color = CinemaStyle.Text, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item(key = "statistics") {
            CinemaButtonFocusGroup {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeStatistic("Premium", when {
                        state.accountLoading -> null
                        state.profile?.premium == true -> homePremiumLabel(state.profile?.premiumInfo.orEmpty())
                        state.profile != null -> "Не активен"
                        else -> "—"
                    }, FieldGlyph.Star, Modifier.weight(1f), highlighted = state.profile?.premium == true,
                        progress = state.premiumFraction, onClick = onAccount)
                    HomeStatistic("Просмотрено", if (history.busy && entries.isEmpty()) null else if (history.error.isNotBlank() && entries.isEmpty()) "—" else watched.toString(),
                        FieldGlyph.Eye, Modifier.weight(1f), onClick = { historyNavigate("watched") })
                    HomeStatistic("В процессе", if (history.busy && entries.isEmpty()) null else if (history.error.isNotBlank() && entries.isEmpty()) "—" else continuing.size.toString(),
                        FieldGlyph.Play, Modifier.weight(1f), onClick = { historyNavigate("continue") })
                    HomeStatistic("Новые серии", if (updates.busy && updates.rows.isEmpty()) null else if (updates.error.isNotBlank() && updates.rows.isEmpty()) "—" else updates.rows.count { it.tracked }.toString(),
                        FieldGlyph.Notifications, Modifier.weight(1f), onClick = { navigate(8) })
                    HomeStatistic("В избранном", if (state.favoritesLoading && state.favorites == null) null
                        else state.favorites?.let { (if (state.favoritesLowerBound) "≥ " else "") + it } ?: "—", FieldGlyph.Bookmark,
                        Modifier.weight(1f), onClick = { navigate(3) })
                }
            }
        }
        if (currentFeature != null) item(key = "featured") {
            HomeFeatured(currentFeature, state, featured.size, selectedFeature,
                choose = { selectedFeature = it },
                watch = { pendingFocus = "feature:watch"; open(currentFeature.film, null, true) },
                more = { pendingFocus = "feature:card"; open(currentFeature.film, null, false) },
                pendingFocus = if (detailOpen) "" else pendingFocus, restored = { pendingFocus = "" })
        }
        if (currentFeature == null && updates.error.isNotBlank()) item(key = "updates-error") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(updates.error, Modifier.weight(1f), color = CinemaStyle.Error, style = CinemaStyle.Body)
                CinematicButton("Повторить", false) { updatesModel.retry(provider) }
            }
        }
        item(key = "continue") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeRowHeading("Продолжить просмотр") { historyNavigate("continue") }
                when {
                    history.busy && continuing.isEmpty() -> SkeletonLoading(SkeletonLayout.Rows, rows = 1, actions = 0)
                    history.error.isNotBlank() && continuing.isEmpty() -> {
                        Text(history.error, color = CinemaStyle.Error, style = CinemaStyle.Body)
                        CinematicButton("Повторить", false) { historyModel.refresh(provider) }
                    }
                    continuing.isEmpty() -> Text("Список пуст", color = CinemaStyle.Muted, style = CinemaStyle.Body)
                    else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cardWidth = (maxWidth - 24.dp) / 3
                        LazyRow(Modifier.cinemaFilmViewport(), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                            items(continuing.take(8), key = { it.key }) { entry ->
                                HomeContinueLandscape(entry, Modifier.width(cardWidth), restore = !detailOpen && pendingFocus == "continue:${entry.key}",
                                    restored = { pendingFocus = "" }) {
                                    pendingFocus = "continue:${entry.key}"
                                    open(entry.film, entry.position, entry.position != null)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "new-releases") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeRowHeading("Горячие новинки") { navigate(1) }
                when {
                    state.loading && state.films.isEmpty() -> SkeletonLoading(SkeletonLayout.Posters, rows = 1, columns = 6)
                    state.error.isNotBlank() && state.films.isEmpty() -> {
                        Text(state.error, color = CinemaStyle.Error, style = CinemaStyle.Body)
                        CinematicButton("Повторить", false) { dashboard.loadContent(provider, sessionRevision, refreshRevision, force = true) }
                    }
                    state.films.isEmpty() -> Text("Нет новинок", color = CinemaStyle.Muted, style = CinemaStyle.Body)
                    else -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cardWidth = (maxWidth - 60.dp) / 6
                        LazyRow(Modifier.cinemaFilmViewport(), horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                            items(state.films, key = { it.url }) { film ->
                                PosterCard(film, Modifier.width(cardWidth), {}, {
                                    pendingFocus = "poster:${film.url}"
                                    open(film, null, false)
                                }, restore = !detailOpen && pendingFocus == "poster:${film.url}", onRestored = { pendingFocus = "" })
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun homeContentPrepared(state: HomeDashboardState, historyBusy: Boolean, updatesBusy: Boolean,
    featureUrl: String): Boolean = !state.loading && !state.accountLoading && !state.favoritesLoading &&
    !historyBusy && !updatesBusy && state.featuredRequestUrl == featureUrl && !state.featuredLoading

@Composable
private fun HomeRowHeading(title: String, all: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = CinemaStyle.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        CinematicButton("Смотреть всё", false, leadingIcon = FieldGlyph.More, onClick = all)
    }
}

@Composable
private fun HomeStatistic(label: String, value: String?, icon: FieldGlyph, modifier: Modifier,
    highlighted: Boolean = false, progress: Float? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val target = rememberCinemaButtonFocusTarget(focused)
    Row(modifier.height(62.dp).cinemaSurface(focused, selected = highlighted, pressed = pressed, focusFrameTarget = target)
        .drawWithContent {
            progress?.let { drawRect(CinemaStyle.Accent.copy(alpha = .16f),
                size = size.copy(width = size.width * it.coerceIn(0f, 1f))) }
            drawContent()
        }
        .onFocusChanged { focused = it.isFocused }.clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (icon == FieldGlyph.Eye) WatchStatusIcon(true, Modifier.size(25.dp))
        else FieldIcon(icon, CinemaStyle.Accent, Modifier.size(25.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = CinemaStyle.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value == null) SkeletonShape(Modifier.fillMaxWidth(.8f).height(17.dp))
            else Text(value, color = CinemaStyle.Text, fontSize = if (value.length > 12) 13.sp else 18.sp,
                lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = if (value.length > 12) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HomeFeatured(update: SeriesUpdate, state: HomeDashboardState, count: Int, selected: Int,
    choose: (Int) -> Unit, watch: () -> Unit, more: () -> Unit, pendingFocus: String, restored: () -> Unit) {
    val info = state.featured.takeIf { state.featuredRequestUrl == update.film.url }
    val candidates = remember(info?.fullPoster, info?.poster, update.film.poster) {
        homeArtworkCandidates(info?.fullPoster.orEmpty(), info?.poster.orEmpty(), update.film.poster)
    }
    var failedImages by remember(update.film.url) { mutableStateOf(emptySet<String>()) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(CinemaStyle.PanelCorner))
        .background(CinemaStyle.Surface)) {
        val poster = candidates.firstOrNull { it !in failedImages }
        if (poster != null) SkeletonAsyncImage(poster, null,
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.48f), contentScale = ContentScale.Crop,
            onError = { failedImages = failedImages + poster })
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(CinemaStyle.Surface,
            CinemaStyle.Surface.copy(alpha = .96f), CinemaStyle.Surface.copy(alpha = .15f)), endX = constraints.maxWidth.toFloat())))
        Column(Modifier.fillMaxHeight().fillMaxWidth(.62f).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(info?.title ?: update.film.title, color = CinemaStyle.Text, fontSize = 28.sp, lineHeight = 32.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (update.info.isNotBlank()) Text(update.info, color = CinemaStyle.Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val description = info?.description.orEmpty()
            if (description.isNotBlank()) Text(description, color = CinemaStyle.Muted, fontSize = 13.sp,
                lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            CinemaButtonFocusGroup {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeHeroAction("Смотреть", FieldGlyph.Play, "feature:watch", pendingFocus, restored, true, watch)
                    HomeHeroAction("Карточка", FieldGlyph.More, "feature:card", pendingFocus, restored, false, more)
                }
            }
        }
        if (count > 1) CinemaButtonFocusGroup {
            Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(count) { index ->
                    var focused by remember { mutableStateOf(false) }
                    val target = rememberCinemaButtonFocusTarget(focused)
                    Box(Modifier.size(26.dp).cinemaFilmTarget(target).onFocusChanged { focused = it.isFocused }
                        .clickable(onClickLabel = "Сериал ${index + 1}") { choose(index) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(7.dp).background(if (index == selected) CinemaStyle.Accent else CinemaStyle.Muted.copy(alpha = .55f), CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeroAction(label: String, icon: FieldGlyph, key: String, pending: String,
    restored: () -> Unit, primary: Boolean, action: () -> Unit) {
    val focus = remember { FocusRequester() }
    RestoreHomeFocus(pending == key, focus, restored)
    CinematicButton(label, primary, Modifier.focusRequester(focus), leadingIcon = icon, onClick = action)
}

@Composable
private fun HomeContinueLandscape(entry: HistoryEntry, modifier: Modifier, restore: Boolean, restored: () -> Unit, open: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val target = rememberCinemaFilmFocusTarget(focused)
    val focus = remember { FocusRequester() }
    RestoreHomeFocus(restore, focus, restored)
    Box(modifier.height(118.dp).focusRequester(focus)
        .cinemaSurface(focused, pressed = pressed, focusFrameTarget = target)
        .onFocusChanged { focused = it.isFocused }.clickable(interactionSource = interaction, indication = null, onClick = open)) {
        if (entry.film.poster.isNotBlank()) SkeletonAsyncImage(entry.film.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, CinemaStyle.Background.copy(alpha = .95f)))))
        Box(Modifier.align(Alignment.Center).size(30.dp).background(CinemaStyle.Background.copy(alpha = .6f), CircleShape), contentAlignment = Alignment.Center) {
            FieldIcon(FieldGlyph.Play, CinemaStyle.Text, Modifier.size(20.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.film.title, color = CinemaStyle.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.info, color = CinemaStyle.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val source = listOfNotNull(if (entry.server.isNotEmpty()) "Аккаунт" else null,
                if (entry.records.isNotEmpty()) "Устройство" else null).joinToString(" · ")
            if (source.isNotBlank()) Text(source, color = CinemaStyle.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun RestoreHomeFocus(restore: Boolean, focus: FocusRequester, restored: () -> Unit) {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val currentRestored by rememberUpdatedState(restored)
    LaunchedEffect(restore, windowFocused) {
        if (restore && windowFocused) { withFrameNanos { }; if (focus.requestFocus()) currentRestored() }
    }
}
