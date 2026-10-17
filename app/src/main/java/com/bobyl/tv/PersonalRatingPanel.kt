package com.bobyl.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import com.bobyl.tv.data.CatalogFilm

@Composable
internal fun PersonalRatingButton(provider: String, filmId: String, entryFocus: FocusRequester, onClick: () -> Unit) {
    val buttonSize = 48.dp
    val model: PersonalRatingViewModel = viewModel(key = "personal-rating:$provider:$filmId")
    val state by model.state.collectAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CinemaStyle.Corner)
    LaunchedEffect(provider, filmId) { model.load(provider, filmId) }
    if (state.loading) {
        SkeletonShape(Modifier.size(buttonSize))
        return
    }
    val rated = (state.value ?: 0) > 0
    Box(Modifier.size(buttonSize).focusRequester(entryFocus).clip(shape)
        .background(CinemaStyle.Raised)
        .border(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else CinemaStyle.Outline, shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClickLabel = if (rated) "Изменить оценку ${state.value}" else "Оценить фильм", onClick = onClick),
        contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.personal_rating), contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(if (rated) CinemaStyle.Focus else CinemaStyle.Accent))
        if (rated) {
            val score = state.value.toString()
            val scorePaint = remember {
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                }
            }
            val scoreBounds = remember { android.graphics.Rect() }
            Canvas(Modifier.fillMaxSize()) {
                scorePaint.color = CinemaStyle.OnAccent.toArgb()
                scorePaint.textSize = (if (state.value == 10) 16.sp else 20.sp).toPx()
                scorePaint.getTextBounds(score, 0, score.length, scoreBounds)
                // Star centre (64, 62) transformed by the approved SVG group.
                val starCentreY = size.height * 75.325f / 128f
                drawContext.canvas.nativeCanvas.drawText(score,
                    size.width / 2f - scoreBounds.exactCenterX(),
                    starCentreY - scoreBounds.exactCenterY(), scorePaint)
            }
        }
        else Canvas(Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp)) {
            drawLine(CinemaStyle.Accent, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx(), StrokeCap.Round)
            drawLine(CinemaStyle.Accent, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.5.dp.toPx(), StrokeCap.Round)
        }
        ActionTooltip(if (rated) "Изменить оценку: ${state.value}" else "Оценить фильм", focused)
    }
}

@Composable
internal fun PersonalRatingPanel(provider: String, filmId: String, entryFocus: FocusRequester,
    initiallyExpanded: Boolean = false, onSubmitted: () -> Unit = {}, onRated: (Pair<String, String>) -> Unit) {
    val model: PersonalRatingViewModel = viewModel(key = "personal-rating:$provider:$filmId")
    val state by model.state.collectAsState()
    val currentOnRated by rememberUpdatedState(onRated)
    val currentOnSubmitted by rememberUpdatedState(onSubmitted)
    var editing by remember(provider, filmId) { mutableStateOf(initiallyExpanded) }
    var draft by remember { mutableIntStateOf(5) }
    var submitted by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(initiallyExpanded) }
    var ratingFocused by remember { mutableStateOf(false) }
    val expanded = !state.loading && editing
    val scoreFocus = remember { FocusRequester() }
    val wheelFocus = remember { FocusRequester() }
    LaunchedEffect(provider, filmId) { model.load(provider, filmId) }
    LaunchedEffect(state.aggregate) { state.aggregate?.let(currentOnRated) }
    LaunchedEffect(state.loading) { if (!state.loading) draft = state.value?.takeIf { it > 0 } ?: 5 }
    LaunchedEffect(submitted, state.saving, state.error, state.value, state.aggregate) {
        if (submitted && !state.saving) {
            if (state.error.isNotBlank()) submitted = false
            else if (state.aggregate != null && state.value == draft) {
                submitted = false
                editing = false
                restoreFocus = true
                currentOnSubmitted()
            }
        }
    }
    LaunchedEffect(expanded, restoreFocus, state.loading) {
        if (restoreFocus && !state.loading) {
            withFrameNanos { }
            if ((if (expanded) wheelFocus else scoreFocus).requestFocus()) restoreFocus = false
        }
    }
    BackHandler(enabled = expanded && ratingFocused && !state.saving && !initiallyExpanded) { editing = false; restoreFocus = true }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) {
                SkeletonShape(Modifier.size(if (initiallyExpanded) 204.dp else 52.dp, 48.dp))
            } else
            if (expanded) {
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(CinemaStyle.Corner)
                Box(Modifier.width(204.dp).height(54.dp).focusRequester(entryFocus).focusRequester(wheelFocus)
                    .shadow(8.dp, shape, clip = false)
                    .clip(shape).background(Brush.verticalGradient(listOf(CinemaStyle.Background, CinemaStyle.Raised, CinemaStyle.Background)))
                    .border(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else CinemaStyle.Outline, shape)
                    .onFocusChanged { focused = it.isFocused; ratingFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                            if (!state.saving && event.type == KeyEventType.KeyDown)
                                draft = (draft + if (event.key == Key.DirectionRight) 1 else -1).coerceIn(1, 10)
                            true
                        } else false
                    }
                    .clickable {
                        if (!state.saving) {
                            if (draft == state.value) { editing = false; restoreFocus = true; currentOnSubmitted() }
                            else { submitted = true; model.rate(provider, filmId, draft) }
                        }
                    }, contentAlignment = Alignment.Center) {
                    if (state.saving) LoadingIndicator("")
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RatingWheelArrow(false, draft > 1)
                        RatingWheelDigit(draft - 2, -2)
                        RatingWheelDigit(draft - 1, -1)
                        Box(Modifier.size(44.dp, 40.dp).shadow(6.dp, shape, clip = false)
                            .background(Brush.verticalGradient(listOf(lerp(CinemaStyle.Accent, CinemaStyle.Text, .18f), CinemaStyle.Accent,
                                lerp(CinemaStyle.Accent, CinemaStyle.Background, .2f))), shape), contentAlignment = Alignment.Center) {
                            Text(draft.toString(), color = CinemaStyle.OnAccent, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, softWrap = false)
                        }
                        RatingWheelDigit(draft + 1, 1)
                        RatingWheelDigit(draft + 2, 2)
                        RatingWheelArrow(true, draft < 10)
                    }
                    Box(Modifier.align(Alignment.CenterStart).width(28.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent))))
                    Box(Modifier.align(Alignment.CenterEnd).width(28.dp).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .45f)))))
                }
            } else {
                var focused by remember { mutableStateOf(false) }
                Box(Modifier.height(48.dp).widthIn(min = 52.dp).focusRequester(entryFocus).focusRequester(scoreFocus)
                    .background(if (focused) CinemaStyle.Selected else CinemaStyle.Surface, RoundedCornerShape(CinemaStyle.Corner))
                    .border(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else CinemaStyle.Outline, RoundedCornerShape(CinemaStyle.Corner))
                    .onFocusChanged { focused = it.isFocused; ratingFocused = it.isFocused }
                    .clickable {
                        if (!state.loading) { draft = state.value?.takeIf { it > 0 } ?: 5; editing = true; restoreFocus = true }
                    }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(if (state.value == null || state.value == 0) "?" else state.value.toString(),
                        color = CinemaStyle.Accent, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (state.error.isNotBlank()) Text(state.error, color = Color(0xFFFFB5B9), fontSize = 14.sp)
    }
}

@Composable
internal fun FilmRatingDialog(provider: String, film: CatalogFilm, dismiss: () -> Unit,
    onRated: (Pair<String, String>) -> Unit = {}) {
    val entryFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 344.dp).fillMaxWidth().clip(RoundedCornerShape(CinemaStyle.PanelCorner))
            .background(CinemaStyle.Background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Оцените фильм", color = CinemaStyle.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(film.title, color = CinemaStyle.Muted, fontSize = 16.sp, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            PersonalRatingPanel(provider, film.id.takeIf { it.isNotBlank() } ?: film.url, entryFocus,
                initiallyExpanded = true, onSubmitted = dismiss, onRated = onRated)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CinematicButton("Позже", false, onClick = dismiss)
            }
        }
    }
}

@Composable
private fun RatingWheelDigit(value: Int, distance: Int) {
    val outer = kotlin.math.abs(distance) == 2
    Box(Modifier.width(if (outer) 24.dp else 28.dp).graphicsLayer {
        rotationY = if (distance < 0) (if (outer) 62f else 32f) else (if (outer) -62f else -32f)
        scaleX = if (outer) .7f else .92f
        scaleY = if (outer) .65f else .88f
        cameraDistance = 10f * density
    }, contentAlignment = Alignment.Center) {
        Text(if (value in 1..10) value.toString() else "", color = CinemaStyle.Muted.copy(alpha = if (outer) .35f else .7f),
            fontSize = if (outer) 18.sp else 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun RatingWheelArrow(right: Boolean, enabled: Boolean) {
    Canvas(Modifier.size(12.dp, 18.dp)) {
        val color = CinemaStyle.Muted.copy(alpha = if (enabled) .85f else .2f)
        val side = if (right) .2f else .8f
        val tip = if (right) .8f else .2f
        drawLine(color, Offset(size.width * side, size.height * .2f), Offset(size.width * tip, size.height * .5f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * tip, size.height * .5f), Offset(size.width * side, size.height * .8f), 2.dp.toPx(), StrokeCap.Round)
    }
}
