package com.bobyl.tv

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val CollapsedRailWidth = 60.dp
private val ExpandedRailWidth = 224.dp
private val UpdatesBadgeColor = Color(0xFFF44349)
internal data class RailDestination(val id: Int, val icon: Int, val title: String)
private fun isRailActivation(key: Key) = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar

/** The rail resizes over content; the content keeps its constant collapsed inset. */
@Composable
internal fun CollapsibleNavigationRail(destinations: List<RailDestination>, selected: Int, onSelected: (Int) -> Unit,
    updateCount: Int, focusRequester: FocusRequester, contentFocus: FocusRequester, modifier: Modifier = Modifier,
    accountName: String = "", avatar: String = "", onAccount: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    var activity by remember { mutableLongStateOf(0L) }
    val destinationFocus = remember(destinations) { destinations.associate { it.id to FocusRequester() } }
    val currentSelection by rememberUpdatedState(selected)
    val navigationState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun recordActivity() { expanded = true; activity++ }
    LaunchedEffect(activity) {
        delay(3_000L)
        expanded = false // Focus stays on the same item; focus alone never holds the drawer open.
    }
    val motion = cinemaMotionEnabled()
    val expansion by animateFloatAsState(if (expanded) 1f else 0f,
        tween(if (motion) 220 else 0, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)), label = "navigation-rail")
    val width = CollapsedRailWidth + (ExpandedRailWidth - CollapsedRailWidth) * expansion
    CinemaButtonFocusGroup {
        Column(modifier.width(width).fillMaxHeight().cinemaFilmViewport()
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                val entered = state.hasFocus && !hasFocus
                hasFocus = state.hasFocus
                if (entered) recordActivity()
                else if (!state.hasFocus) expanded = false
            }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && !isRailActivation(it.key)) recordActivity()
                false
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if ((event.type == PointerEventType.Enter || event.type == PointerEventType.Move) && event.changes.none { it.pressed } ||
                            event.type == PointerEventType.Scroll) recordActivity()
                    }
                }
            }
            .focusProperties {
                onEnter = {
                    val index = destinations.indexOfFirst { it.id == selected }
                    val target = destinationFocus[selected]
                    if (index >= 0 && target != null) {
                        if (navigationState.layoutInfo.visibleItemsInfo.any { it.key == selected }) {
                            if (!target.requestFocus()) cancelFocusChange()
                        } else {
                            cancelFocusChange()
                            scope.launch {
                                navigationState.scrollToItem(index)
                                withFrameNanos { }
                                if (currentSelection == selected) target.requestFocus()
                            }
                        }
                    }
                }
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
            .focusGroup()
            .background(CinemaStyle.Rail)
            .padding(horizontal = 8.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().cinemaFilmViewport(), state = navigationState,
                contentPadding = PaddingValues(top = 24.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(destinations, key = { it.id }) { item ->
                CollapsibleRailItem(item, item.id == selected, expansion,
                    badge = if (item.id == 8) updateCount else 0, onActivity = ::recordActivity, onClick = {
                        if (item.id != selected) {
                            expanded = false
                            onSelected(item.id)
                        }
                    }, modifier = Modifier.focusRequester(destinationFocus.getValue(item.id)))
            }
            }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 24.dp)) {
                CollapsibleRailItem(RailDestination(-1, R.drawable.nav_home, accountName.ifBlank { "Аккаунт" }),
                    selected = false, expansion = expansion, badge = 0, onActivity = ::recordActivity,
                    onClick = { expanded = false; onAccount() }, avatar = avatar, account = true)
            }
        }
    }
}

@Composable
private fun CollapsibleRailItem(item: RailDestination, selected: Boolean, expansion: Float, badge: Int,
    onActivity: () -> Unit, onClick: () -> Unit, avatar: String = "", account: Boolean = false, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val target = rememberCinemaButtonFocusTarget(focused)
    val foreground = if (selected || focused) CinemaStyle.Text else CinemaStyle.Muted
    Box(modifier.fillMaxWidth().heightIn(min = 44.dp).clipToBounds()
        .then(if (selected || focused || pressed) Modifier.cinemaSurface(focused, selected, pressed, focusFrameTarget = target)
            else Modifier.cinemaFilmTarget(target))
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent {
            if (isRailActivation(it.key)) {
                if (selected) true
                else {
                    if (it.type == KeyEventType.KeyDown) onActivity()
                    false
                }
            } else false
        }
        .pointerInput(selected) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (selected) {
                    // Stop selected taps before clickable can request focus or dispatch a click.
                    down.consume()
                    waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
                } else onActivity()
            }
        }
        .clickable(interactionSource = interactions, indication = null, onClick = onClick), contentAlignment = Alignment.CenterStart) {
        // Fixed inner geometry keeps icons steady and avoids squeezing labels during a width retarget.
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(ExpandedRailWidth - 16.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp)) {
                if (account && avatar.isNotBlank()) {
                    SkeletonAsyncImage(avatar, null, Modifier.fillMaxSize().clip(CircleShape), ContentScale.Crop)
                } else if (account) {
                    Box(Modifier.fillMaxSize().background(CinemaStyle.Accent.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
                        Text(if (item.title == "Аккаунт") "•" else item.title.take(1).uppercase(), color = CinemaStyle.Text,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else Image(painterResource(item.icon), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(foreground))
                if (badge > 0) {
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(6.dp)
                        .graphicsLayer { alpha = 1f - expansion }.background(UpdatesBadgeColor, CircleShape))
                    SeriesCountBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp)
                        .graphicsLayer { alpha = expansion })
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f).graphicsLayer { alpha = expansion }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, color = foreground, fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SeriesCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).background(UpdatesBadgeColor, CircleShape), contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99+" else count.toString(), color = Color.White,
            fontSize = if (count > 99) 8.sp else 10.sp, lineHeight = if (count > 99) 8.sp else 10.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}
