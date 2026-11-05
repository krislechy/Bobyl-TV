package com.bobyl.tv

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.modifier.modifierLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel

/** Geometry/state only: native focus, click and key handling stay with the caller. */
internal class CinemaFocusFrameState {
    var owner: Any? = null
        private set
    var rect: Rect? = null
        private set
    var viewport: Rect? = null
        private set
    var windowFocused = true
    var motionEnabled = true
    var generation = 0L
        private set
    private var focused = false
    private var destination: Rect? = null
    private var destinationViewport: Rect? = null
    private var origin: Rect? = null
    private var startedAt: Long? = null
    private val easing = CubicBezierEasing(.23f, 1f, .32f, 1f)
    val animating: Boolean get() = origin != null
    val visible: Boolean get() = windowFocused && rect != null

    fun focus(target: Any) {
        if (owner === target && focused) return
        owner = target
        focused = true
        generation++
        // Keep the rendered frame until the new owner's geometry actually exists.
        destination = null
        destinationViewport = null
        origin = null
    }

    fun blur(target: Any): Long? {
        if (owner !== target || !focused) return null
        focused = false
        return ++generation
    }

    fun clear(ticket: Long) {
        if (generation != ticket || focused) return
        owner = null
        rect = null
        viewport = null
        destination = null
        origin = null
    }

    fun bounds(target: Any, bounds: Rect, clip: Rect) {
        if (owner !== target || !bounds.isFinite || bounds.isEmpty) return
        val oldDestination = destination
        if (oldDestination == bounds && destinationViewport == clip) return
        destinationViewport = clip
        if (oldDestination == null) {
            destination = bounds
            if (rect == null || !motionEnabled) {
                rect = bounds
                viewport = clip
                origin = null
            } else {
                origin = rect
                startedAt = null
                viewport = viewport?.let { union(it, clip) } ?: clip
            }
        } else {
            // Layout/scroll/scale of this SAME target follows directly, without
            // restarting the focus journey or snapping it to its destination.
            val delta = bounds - oldDestination
            rect = rect?.plus(delta)
            origin = origin?.plus(delta)
            destination = bounds
            viewport = if (animating) viewport?.let { union(it, clip) } ?: clip else clip
        }
    }

    fun advance(now: Long) {
        val end = destination ?: return
        val start = origin ?: return
        if (!motionEnabled) {
            rect = end
            origin = null
            viewport = destinationViewport
            return
        }
        val began = startedAt ?: now.also { startedAt = it }
        val fraction = ((now - began).toFloat() / (CinemaStyle.FocusDuration * 1_000_000f)).coerceIn(0f, 1f)
        val progress = easing.transform(fraction)
        rect = start + (end - start) * progress
        if (fraction == 1f) {
            origin = null
            viewport = destinationViewport
        }
    }

    private fun union(a: Rect, b: Rect) = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    private operator fun Rect.minus(other: Rect) = Rect(left - other.left, top - other.top, right - other.right, bottom - other.bottom)
    private operator fun Rect.plus(other: Rect) = Rect(left + other.left, top + other.top, right + other.right, bottom + other.bottom)
    private operator fun Rect.times(value: Float) = Rect(left * value, top * value, right * value, bottom * value)
}

private class CinemaFilmViewport {
    var coordinates: LayoutCoordinates? = null
    var parent: CinemaFilmViewport? = null
}

private val LocalFilmViewport = modifierLocalOf<CinemaFilmViewport?> { null }
private val LocalFilmFrame = staticCompositionLocalOf<CinemaFilmFrameHost?> { null }
private val LocalButtonFrame = staticCompositionLocalOf<CinemaFilmFrameHost?> { null }

internal class CinemaFilmFocusTarget internal constructor(private val host: CinemaFilmFrameHost) {
    internal var coordinates: LayoutCoordinates? = null
    private var viewport: CinemaFilmViewport? = null
    internal fun invalidateGeometry() {
        if (host.state.owner === this) host.invalidate()
    }
    internal fun Modifier.geometryModifier(): Modifier = this
        .modifierLocalConsumer { viewport = LocalFilmViewport.current }
        .onGloballyPositioned { coordinates = it; invalidateGeometry() }

    internal fun dispose() {
        val wasOwner = host.state.owner === this
        coordinates = null
        host.state.blur(this)?.let(host::deferClear)
        if (wasOwner) host.invalidate()
    }

    internal fun boundsIn(root: LayoutCoordinates): Pair<Rect, Rect>? {
        val node = coordinates?.takeIf { it.isAttached } ?: return null
        if (!root.isAttached) return null
        val bounds = root.localBoundingBoxOf(node, clipBounds = false)
        var clip = Rect(Offset.Zero, Size(root.size.width.toFloat(), root.size.height.toFloat()))
        var current = viewport
        while (current != null) {
            current.coordinates?.takeIf { it.isAttached }?.let { clip = clip.intersect(root.localBoundingBoxOf(it, clipBounds = true)) }
            current = current.parent
        }
        return bounds to clip
    }
}

internal class CinemaFilmFrameHost(val windowView: android.view.View? = null) {
    val state = CinemaFocusFrameState()
    val wake = Channel<Unit>(Channel.CONFLATED)
    var revision by mutableLongStateOf(0)
        private set
    private var revisionCounter = 0L
    var coordinates: LayoutCoordinates? = null
    var pendingClear: Long? = null
    val buttonGroups = mutableStateListOf<CinemaFilmFrameHost>()
    fun deferClear(ticket: Long) { pendingClear = ticket; invalidate() }
    fun redraw() { revision = ++revisionCounter }
    fun invalidate() { redraw(); wake.trySend(Unit) }
}

/** Put INSIDE each native window. Box keeps the original panel's measured size. */
@Composable
internal fun CinemaFocusFrameHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val view = LocalView.current
    val host = remember(view) { CinemaFilmFrameHost(view) }
    CinemaFrameAnimation(host)
    CompositionLocalProvider(LocalFilmFrame provides host, LocalButtonFrame provides null) {
        Box(modifier.onGloballyPositioned { host.coordinates = it; host.invalidate() }, contentAlignment = Alignment.Center) {
            content()
            Canvas(Modifier.matchParentSize()) {
                val root = host.coordinates ?: return@Canvas
                drawCinemaFrame(host, root)
                for (group in host.buttonGroups) drawCinemaFrame(group, root)
            }
        }
    }
}

private fun DrawScope.drawCinemaFrame(frame: CinemaFilmFrameHost, root: LayoutCoordinates) {
    frame.revision // Read only during drawing, never the list's composition/layout.
    val target = frame.state.owner as? CinemaFilmFocusTarget
    target?.boundsIn(root)?.let { (bounds, clip) ->
        val wasAnimating = frame.state.animating
        frame.state.bounds(target, bounds, clip)
        if (!wasAnimating && frame.state.animating) frame.wake.trySend(Unit)
    }
    val rect = frame.state.rect
    val clip = frame.state.viewport
    if (frame.state.visible && rect != null && clip != null && !clip.isEmpty) {
        clipRect(clip.left, clip.top, clip.right, clip.bottom) {
            val stroke = 2.dp.toPx()
            drawRoundRect(CinemaStyle.Focus, Offset(rect.left + stroke / 2, rect.top + stroke / 2),
                Size((rect.width - stroke).coerceAtLeast(0f), (rect.height - stroke).coerceAtLeast(0f)),
                CornerRadius(CinemaStyle.Corner.toPx()), style = Stroke(stroke))
        }
    }
}

/** Logical neighbours share an outline, without adding layout or native focus nodes. */
@Composable
internal fun CinemaButtonFocusGroup(content: @Composable () -> Unit) {
    val view = LocalView.current
    val window = LocalFilmFrame.current?.takeIf { it.windowView === view }
    if (window == null) {
        content()
        return
    }
    val group = remember(window) { CinemaFilmFrameHost(window.windowView) }
    CinemaFrameAnimation(group)
    DisposableEffect(window, group) {
        window.buttonGroups.add(group)
        onDispose { window.buttonGroups.remove(group) }
    }
    CompositionLocalProvider(LocalButtonFrame provides group, content = content)
}

@Composable
private fun CinemaFrameAnimation(host: CinemaFilmFrameHost) {
    val motion = cinemaMotionEnabled()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    SideEffect {
        if (host.state.motionEnabled != motion || host.state.windowFocused != windowFocused) {
            host.state.motionEnabled = motion
            host.state.windowFocused = windowFocused
            host.invalidate()
        }
    }
    LaunchedEffect(host) {
        for (signal in host.wake) {
            do {
                withFrameNanos {
                    host.pendingClear?.let(host.state::clear)
                    host.pendingClear = null
                    host.state.advance(it)
                    host.redraw()
                }
            } while (host.state.animating)
        }
    }
    DisposableEffect(host) { onDispose { host.wake.close() } }
}

@Composable
internal fun rememberCinemaFilmFocusTarget(focused: Boolean): CinemaFilmFocusTarget? {
    return rememberCinemaFocusTarget(LocalFilmFrame.current, focused)
}

@Composable
internal fun rememberCinemaButtonFocusTarget(focused: Boolean): CinemaFilmFocusTarget? =
    rememberCinemaFocusTarget(LocalButtonFrame.current, focused)

@Composable
private fun rememberCinemaFocusTarget(host: CinemaFilmFrameHost?, focused: Boolean): CinemaFilmFocusTarget? {
    // Composition locals cross Dialog/Popup composition, native coordinates do not.
    if (host == null || host.windowView !== LocalView.current) return null
    val target = remember(host) { CinemaFilmFocusTarget(host) }
    SideEffect {
        val generation = host.state.generation
        if (focused) host.state.focus(target)
        else host.state.blur(target)?.let(host::deferClear)
        if (generation != host.state.generation) host.invalidate()
    }
    DisposableEffect(target) {
        onDispose { target.dispose() }
    }
    return target
}

internal fun Modifier.cinemaFilmTarget(target: CinemaFilmFocusTarget?): Modifier =
    if (target == null) this else with(target) { this@cinemaFilmTarget.geometryModifier() }

/** Marks the actual lazy viewport without inserting a layout or changing focus. */
@Composable
internal fun Modifier.cinemaFilmViewport(): Modifier {
    val view = LocalView.current
    val host = LocalFilmFrame.current?.takeIf { it.windowView === view } ?: return this
    val viewport = remember { CinemaFilmViewport() }
    return this.modifierLocalConsumer { viewport.parent = LocalFilmViewport.current }
        .modifierLocalProvider(LocalFilmViewport) { viewport }
        .onGloballyPositioned { viewport.coordinates = it; host.invalidate() }
}
