package com.bobyl.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

private val Background = CinemaStyle.Background
private val Surface = CinemaStyle.Surface
private val Accent = CinemaStyle.Accent
private val Muted = CinemaStyle.Muted

private val menu = listOf(
    RailDestination(0, R.drawable.nav_home, "Главная"),
    RailDestination(1, R.drawable.nav_news, "Новинки"),
    RailDestination(6, R.drawable.nav_categories, "Категории"),
    RailDestination(7, R.drawable.nav_history, "Продолжить"),
    RailDestination(8, R.drawable.nav_updates, "Новые серии"),
    RailDestination(3, R.drawable.nav_favorite, "Избранное"),
    RailDestination(4, R.drawable.nav_search, "Поиск"),
    RailDestination(5, R.drawable.nav_settings, "Настройки"),
)

@Composable
fun BobylTvApp(startupFinished: Boolean, onStartupPrepared: () -> Unit) {
    val context = LocalContext.current
    val startup: StartupViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val filmActions: FilmActionsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val dashboard: HomeDashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "home-dashboard")
    val accountState by dashboard.state.collectAsState()
    var accountOpen by remember { mutableStateOf(false) }
    val updateCount by startup.updateCount.collectAsState()
    var storedMenu by rememberSaveable {
        val startup = context.getSharedPreferences("${context.packageName}_preferences", android.content.Context.MODE_PRIVATE).getInt("startupScreen", 0)
        mutableIntStateOf(startup.takeIf { id -> menu.any { it.id == id } } ?: 0)
    }
    val selectedMenu = storedMenu.takeIf { id -> menu.any { it.id == id } } ?: 0
    var requestedHistoryTab by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionRevision by rememberSaveable { mutableIntStateOf(0) }
    val foregroundRefreshRevision = rememberForegroundRefreshRevision()
    val startupProvider = remember(context) {
        context.getSharedPreferences("${context.packageName}_preferences", android.content.Context.MODE_PRIVATE)
            .getString("ownProvider", "hdrzk.org") ?: "hdrzk.org"
    }
    var homeSessionReady by remember(startupProvider, sessionRevision) { mutableStateOf(false) }
    LaunchedEffect(startupFinished, selectedMenu, startupProvider, sessionRevision) {
        if (!startupFinished && selectedMenu != 0) {
            try { startup.awaitPreparedSession(startupProvider) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* Home's bounded requests expose their own error/retry states. */ }
            homeSessionReady = true
        }
    }
    LaunchedEffect(startupFinished, selectedMenu, homeSessionReady, startupProvider, sessionRevision, foregroundRefreshRevision) {
        if (!startupFinished && selectedMenu != 0 && homeSessionReady)
            dashboard.loadAccount(startupProvider, sessionRevision, foregroundRefreshRevision)
    }
    LaunchedEffect(storedMenu) {
        if (storedMenu != selectedMenu) storedMenu = selectedMenu
    }
    val screenState = rememberSaveableStateHolder()
    val railFocus = remember { FocusRequester() }
    val contentFocus = remember(selectedMenu) { FocusRequester() }
    LaunchedEffect(startupFinished) {
        if (startupFinished) { startup.finishStartup(); railFocus.requestFocus() }
    }
    val startupReady by startup.contentPrepared.collectAsState()
    val preparedCallback by androidx.compose.runtime.rememberUpdatedState(onStartupPrepared)
    LaunchedEffect(startupReady) {
        if (startupReady && !startupFinished) {
            // Let the prepared content mount its images before observing completion.
            androidx.compose.runtime.withFrameNanos { }
            startup.awaitPreparedContent()
            preparedCallback()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalStartupImages provides startup, LocalFilmActions provides filmActions) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, onPrimary = CinemaStyle.OnAccent,
        background = Background, onBackground = CinemaStyle.Text,
        surface = Surface, onSurface = CinemaStyle.Text,
        surfaceVariant = CinemaStyle.Raised, onSurfaceVariant = Muted,
    )) {
        CinemaFocusFrameHost {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { !startupFinished }) {
            if (!startupFinished && selectedMenu != 0 && homeSessionReady) {
                // Mount the actual Home and shared Activity VMs under the illustrated startup layer.
                Box(Modifier.fillMaxSize().padding(start = CollapsedRailWidth)
                    .graphicsLayer { alpha = 0f }
                    .clearAndSetSemantics { }
                    .focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()
                    .onPreviewKeyEvent { true }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }) {
                    HomeDashboard(startupProvider, sessionRevision, foregroundRefreshRevision, dashboard,
                        onReady = startup::homeContentReady, onUpdateCount = startup::showUpdateCount,
                        onAccount = {}, detailOpen = false, historyNavigate = {}, navigate = {}, open = { _, _, _ -> })
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .cinemaBackdrop(),
            ) {
                Spacer(Modifier.width(CollapsedRailWidth))
                screenState.SaveableStateProvider(selectedMenu) {
                    key(selectedMenu) {
                        Box(Modifier.weight(1f).fillMaxHeight()
                            .focusRequester(contentFocus)
                            .focusProperties {
                                onEnter = { contentFocus.restoreFocusedChild() }
                                onExit = {
                                    contentFocus.saveFocusedChild()
                                    when (requestedFocusDirection) {
                                        FocusDirection.Left -> {
                                            if (!railFocus.requestFocus()) cancelFocusChange()
                                        }
                                        FocusDirection.Up, FocusDirection.Down, FocusDirection.Right -> cancelFocusChange()
                                    }
                                }
                            }
                            .focusGroup()) {
                            ConnectedCatalog(selectedMenu, startup = startup,
                                onStartupReady = {
                                    startup.primaryContentReady()
                                    if (selectedMenu == 0) startup.homeContentReady()
                                },
                                onUpdateCount = startup::showUpdateCount, refreshRevision = foregroundRefreshRevision,
                                sessionRevision = sessionRevision, onSessionChanged = { sessionRevision++ },
                                accountOpen = accountOpen, onAccountDismiss = { accountOpen = false },
                                onAccountOpen = { accountOpen = true },
                                historyTabRequest = requestedHistoryTab, onHistoryTabHandled = { requestedHistoryTab = null },
                                onHistoryNavigate = { requestedHistoryTab = it; storedMenu = 7 },
                                onNavigate = { storedMenu = it })
                        }
                    }
                }
            }
            CollapsibleNavigationRail(destinations = menu, selected = selectedMenu,
                onSelected = { requestedHistoryTab = null; storedMenu = it }, updateCount = updateCount,
                focusRequester = railFocus, contentFocus = contentFocus,
                accountName = accountState.nickname, avatar = accountState.profile?.avatar.orEmpty(),
                onAccount = { accountOpen = true },
                modifier = Modifier.align(Alignment.TopStart).zIndex(2f))
            if (startupFinished) {
                ClockNotch(Modifier.align(Alignment.TopCenter).zIndex(1f).graphicsLayer { clip = false })
            }
        }
        }
    }
    }

    BackHandler(enabled = startupFinished && selectedMenu != 0) { storedMenu = 0 }
}

@Composable
internal fun CinematicButton(label: String, primary: Boolean, modifier: Modifier = Modifier, leadingIcon: FieldGlyph? = null, enabled: Boolean = true, filmFocusFrame: Boolean = false, onClick: () -> Unit) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val frameTarget = if (filmFocusFrame) rememberCinemaFilmFocusTarget(focused && enabled) else rememberCinemaButtonFocusTarget(focused && enabled)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .cinemaSurface(focused, selected = primary, pressed = pressed, enabled = enabled, focusFrameTarget = frameTarget)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val color = if (!enabled) CinemaStyle.Muted else CinemaStyle.Text
        if (leadingIcon == null) Text(label, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        else Row(Modifier.wrapContentWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldIcon(leadingIcon, color, Modifier.size(20.dp))
            Text(label, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
