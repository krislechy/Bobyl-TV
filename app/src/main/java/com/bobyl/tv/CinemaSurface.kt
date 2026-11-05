package com.bobyl.tv

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Visual treatment only. The caller remains the sole owner of input and focus. */
@Composable
internal fun Modifier.cinemaSurface(
    focused: Boolean,
    selected: Boolean = false,
    pressed: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(CinemaStyle.Corner),
    scaleOnFocus: Boolean = false,
    focusFrameTarget: CinemaFilmFocusTarget? = null,
): Modifier {
    val motion = cinemaMotionEnabled()
    val active = enabled && focused
    val scale = animateFloatAsState(
        if (!motion) 1f else if (enabled && pressed) .99f else if (scaleOnFocus && active) 1.025f else 1f,
        tween(if (motion) if (pressed) CinemaStyle.PressDuration else CinemaStyle.FocusDuration else 0,
            easing = CubicBezierEasing(.23f, 1f, .32f, 1f)), label = "cinema-surface-scale")
    val lift = animateFloatAsState(
        if (motion && scaleOnFocus && active && !pressed) -1.5f else 0f,
        tween(if (motion) CinemaStyle.FocusDuration else 0, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)),
        label = "cinema-surface-lift")
    val density = LocalDensity.current.density
    val elevation = if (active) 8.dp else 2.dp
    val layerShape = shape
    val surface = when {
        !enabled -> CinemaStyle.Surface
        pressed -> CinemaStyle.Pressed
        selected -> CinemaStyle.Selected
        active -> CinemaStyle.Raised
        else -> CinemaStyle.Surface
    }
    return this
        .graphicsLayer {
            scaleX = scale.value; scaleY = scale.value; translationY = lift.value * density
            shadowElevation = elevation.toPx()
            this.shape = layerShape
            clip = true
            focusFrameTarget?.invalidateGeometry()
        }
        .background(surface)
        .drawWithCache {
            val light = Brush.linearGradient(
                listOf(CinemaStyle.Accent.copy(alpha = if (enabled && pressed) .18f else if (selected || active) .12f else .045f), Color.Transparent),
                start = Offset.Zero, end = Offset(size.width * .7f, size.height))
            onDrawBehind { drawRect(light) }
        }
        // Film targets delegate only their bright outline to the window overlay.
        .border(if (active && focusFrameTarget == null) 2.dp else 1.dp,
            if (active && focusFrameTarget == null) CinemaStyle.Focus else if (selected) CinemaStyle.Outline else CinemaStyle.Outline.copy(alpha = .35f), shape)
        .cinemaFilmTarget(focusFrameTarget)
}

/** Focus changes affect drawing order during placement, without changing measurement. */
internal fun MeasureScope.measureCinemaFocusZIndex(
    measurable: Measurable,
    constraints: Constraints,
    focused: State<Boolean>,
): MeasureResult {
    val placeable = measurable.measure(constraints)
    return layout(placeable.width, placeable.height) {
        placeable.place(0, 0, zIndex = if (focused.value) 1f else 0f)
    }
}

/** Soft room light, with no shader, bitmap allocation or continuously running animation. */
internal fun Modifier.cinemaBackdrop(): Modifier = background(CinemaStyle.Background).drawWithCache {
    val light = Brush.radialGradient(
        listOf(CinemaStyle.Raised.copy(alpha = .55f), Color.Transparent),
        center = Offset(size.width * .55f, -size.height * .1f), radius = size.width * .75f)
    onDrawBehind { drawRect(light) }
}
