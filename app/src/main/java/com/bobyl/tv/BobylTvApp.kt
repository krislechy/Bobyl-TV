package com.bobyl.tv

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.foundation.lazy.LazyColumn

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
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

private data class MenuItem(val id: Int, val icon: Int, val title: String)
private val menu = listOf(
    MenuItem(0, R.drawable.nav_home, "Главная"),
    MenuItem(1, R.drawable.nav_news, "Новинки"),
    MenuItem(6, R.drawable.nav_categories, "Категории"),
    MenuItem(7, R.drawable.nav_history, "Продолжить"),
    MenuItem(8, R.drawable.nav_updates, "Новые серии"),
    MenuItem(3, R.drawable.nav_favorite, "Избранное"),
    MenuItem(4, R.drawable.nav_search, "Поиск"),
    MenuItem(5, R.drawable.nav_settings, "Настройки"),
)

@Composable
fun BobylTvApp() {
    val context = LocalContext.current
    val startup: StartupViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val updateCount by startup.updateCount.collectAsState()
    val trackingRevision by com.bobyl.tv.data.ServerHistoryChanges.revision.collectAsState()
    LaunchedEffect(trackingRevision) {
        startup.refreshUpdateCount(trackingRevision)
    }
    var selectedMenu by rememberSaveable {
        val startup = context.getSharedPreferences("${context.packageName}_preferences", android.content.Context.MODE_PRIVATE).getInt("startupScreen", 0)
        mutableIntStateOf(startup.takeIf { id -> menu.any { it.id == id } } ?: 0)
    }
    var historyScreenRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedMenu) {
        if (menu.none { it.id == selectedMenu }) selectedMenu = 0
    }
    val screenState = rememberSaveableStateHolder()
    val railFocus = remember { FocusRequester() }
    val contentFocus = remember(selectedMenu) { FocusRequester() }
    var startupFinished by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startupFinished) {
        if (startupFinished) railFocus.requestFocus()
    }
    var minimumElapsed by remember { mutableStateOf(false) }
    var startupReady by remember { mutableStateOf(false) }
    val startupOpacity = remember { Animatable(1f) }
    LaunchedEffect(minimumElapsed, startupReady) {
        if (!startupFinished && minimumElapsed && startupReady) {
            // Let the prepared content mount its images before observing completion.
            androidx.compose.runtime.withFrameNanos { }
            startup.awaitVisibleImages()
            startupOpacity.animateTo(
                targetValue = 0f,
                animationSpec = tween(250, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)),
            )
            startupFinished = true
            startup.finishStartup()
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalStartupImages provides startup) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, onPrimary = CinemaStyle.OnAccent,
        background = Background, onBackground = CinemaStyle.Text,
        surface = Surface, onSurface = CinemaStyle.Text,
        surfaceVariant = CinemaStyle.Raised, onSurfaceVariant = Muted,
    )) {
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { !startupFinished }) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background),
            ) {
                NavigationRail(
                    selected = selectedMenu,
                    onSelected = { selectedMenu = it; if (it == 7 || it == 8) historyScreenRevision++ },
                    updateCount = updateCount,
                    focusRequester = railFocus,
                    contentFocus = contentFocus,
                )
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
                                onStartupReady = { startup.primaryContentReady(); startupReady = true },
                                onUpdateCount = startup::showUpdateCount, refreshRevision = historyScreenRevision)
                        }
                    }
                }
            }
            if (startupFinished) {
                ClockNotch(Modifier.align(Alignment.TopCenter))
            }
            if (!startupFinished) {
                StartupLoadingScreen(
                    onMinimumElapsed = { minimumElapsed = true },
                    modifier = Modifier.graphicsLayer { alpha = startupOpacity.value },
                )
            }
        }
    }
    }

    BackHandler(enabled = startupFinished && selectedMenu != 0) { selectedMenu = 0 }
}

@Composable
private fun NavigationRail(selected: Int, onSelected: (Int) -> Unit, updateCount: Int,
    focusRequester: FocusRequester, contentFocus: FocusRequester) {
    LazyColumn(
        modifier = Modifier
            .width(192.dp)
            .fillMaxHeight()
            .focusRequester(focusRequester)
            .focusProperties {
                onEnter = { focusRequester.restoreFocusedChild() }
                onExit = {
                    focusRequester.saveFocusedChild()
                    when (requestedFocusDirection) {
                        FocusDirection.Right -> {
                            if (!contentFocus.requestFocus()) cancelFocusChange()
                        }
                        FocusDirection.Up, FocusDirection.Down, FocusDirection.Left -> cancelFocusChange()
                    }
                }
            }
            .background(CinemaStyle.Rail)
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(CinemaStyle.Corner)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.bobyl_icon), "Бобыль", Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Text("Бобыль", color = CinemaStyle.Text, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp)) }
        itemsIndexed(menu, key = { _, item -> item.id }) { _, item ->
            RailItem(
                item = item,
                selected = item.id == selected,
                onClick = { onSelected(item.id) },
                badge = if (item.id == 8) updateCount else 0,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun RailItem(item: MenuItem, selected: Boolean, onClick: () -> Unit, badge: Int = 0) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    val foreground = when {
        selected -> CinemaStyle.OnAccent
        focused -> CinemaStyle.Text
        else -> Muted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(CinemaStyle.Corner))
            .background(
                when {
                    selected -> Accent
                    focused -> CinemaStyle.Raised
                    else -> Color.Transparent
                },
            )
            .border(
                BorderStroke(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else Color.Transparent),
                RoundedCornerShape(CinemaStyle.Corner),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Image(painterResource(item.icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(foreground))
            if (badge > 0) NewSeriesCountBadge(badge, Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            item.title,
            color = foreground,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NewSeriesCountBadge(count: Int, modifier: Modifier = Modifier) {
    val motionEnabled = remember { Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled() }
    val pulse = if (motionEnabled) {
        val transition = rememberInfiniteTransition(label = "new-series-count")
        transition.animateFloat(.94f, 1.06f,
            infiniteRepeatable(tween(1000, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)), RepeatMode.Reverse), label = "pulse")
    } else remember { mutableStateOf(1f) }
    Box(modifier.size(if (count > 99) 22.dp else 18.dp).graphicsLayer {
        scaleX = pulse.value
        scaleY = pulse.value
    }.background(Color(0xFFD52F45), CircleShape), contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99+" else count.toString(), color = Color.White,
            fontSize = if (count > 99) 9.sp else 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun CinematicButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(CinemaStyle.Corner))
            .background(if (primary) Accent else if (focused) CinemaStyle.Raised else Surface)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) CinemaStyle.Focus else Color.Transparent,
                RoundedCornerShape(CinemaStyle.Corner),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) CinemaStyle.OnAccent else CinemaStyle.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
