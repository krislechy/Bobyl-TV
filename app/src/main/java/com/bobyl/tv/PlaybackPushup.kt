package com.bobyl.tv

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Text

internal data class PushupPanel(val step: Int, val title: String, val options: List<Pair<String, Boolean>>)

/** Pushup: preserve prior choices as passive cards; only the front card accepts focus. */
@Composable
internal fun PlaybackPushup(panel: PushupPanel, filmTitle: String, selections: Map<Int, String>, next: PushupPanel?, content: @Composable ColumnScope.() -> Unit) {
    val history = remember { mutableStateListOf<PushupPanel>() }
    SideEffect {
        history.removeAll { it.step > panel.step }
        val index = history.indexOfFirst { it.step == panel.step }
        if (index < 0) history.add(panel) else if (history[index] != panel) history[index] = panel
        history.indices.forEach { position ->
            val previous = history[position]
            val selected = selections[previous.step]
            if (previous.step != panel.step && !selected.isNullOrBlank()) {
                val updated = previous.copy(options = previous.options.map { it.first to (it.first == selected) })
                if (updated != previous) history[position] = updated
            }
        }
    }
    val motionEnabled = remember { Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled() }
    val motion = tween<Float>(if (motionEnabled) 220 else 0, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f))
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(.96f).fillMaxHeight(.96f)) {
        val cardWidth = (maxWidth * .5f).coerceAtMost(440.dp)
        val heightLimit = (maxHeight - 180.dp).coerceAtLeast(220.dp).coerceAtMost(maxHeight)
        val cardHeight = (190.dp + 52.dp * panel.options.size.coerceIn(1, 5)).coerceAtLeast(280.dp).coerceAtMost(heightLimit)
        val retreat = with(density) { (maxHeight * .09f).coerceAtMost(48.dp).toPx() }
        next?.let { upcoming ->
            val previewHeight = if (upcoming.options.isEmpty()) 56.dp else 80.dp
            val previewOffset = with(density) { (cardHeight / 2 + previewHeight / 2 + 12.dp).toPx() }
            Column(Modifier.align(Alignment.Center).width(cardWidth * .9f).height(previewHeight)
                .graphicsLayer { translationY = previewOffset; alpha = .8f }
                .clip(RoundedCornerShape(CinemaStyle.PanelCorner)).background(CinemaStyle.Surface)
                .border(1.dp, CinemaStyle.Outline, RoundedCornerShape(CinemaStyle.PanelCorner)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Далее · ${upcoming.title}", color = CinemaStyle.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (upcoming.options.isNotEmpty()) Text(upcoming.options.take(4).joinToString(" · ") { it.first }, color = CinemaStyle.Muted, fontSize = 13.sp, maxLines = 1)
            }
        }
        val cards = if (history.any { it.step == panel.step }) history.filter { it.step <= panel.step } else history.filter { it.step < panel.step } + panel
        cards.forEach { card ->
            key(card.step) {
                var entered by remember { mutableStateOf(!motionEnabled) }
                LaunchedEffect(Unit) { entered = true }
                val depth = cards.lastIndex - cards.indexOf(card)
                val scale by animateFloatAsState(if (!entered) .96f else 1f - depth * .07f, motion, label = "pushup-scale")
                val offset by animateFloatAsState(if (!entered) retreat * .3f else -depth * retreat, motion, label = "pushup-position")
                val opacity by animateFloatAsState(if (!entered) 0f else if (depth == 0) 1f else (.9f - depth * .05f).coerceAtLeast(.7f), motion, label = "pushup-dim")
                Column(Modifier.align(Alignment.Center).width(cardWidth).height(cardHeight)
                    .zIndex(card.step.toFloat())
                    .graphicsLayer { translationY = offset; scaleX = scale; scaleY = scale; alpha = opacity }
                    .clip(RoundedCornerShape(CinemaStyle.PanelCorner))
                    .background(CinemaStyle.Background)
                    .border(1.dp, CinemaStyle.Outline, RoundedCornerShape(CinemaStyle.PanelCorner))
                    .padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (card.step == panel.step) content()
                    else {
                        Text(card.title, color = CinemaStyle.Text, fontSize = 24.sp)
                        Text(filmTitle, color = CinemaStyle.Muted, fontSize = 15.sp, maxLines = 2)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(card.options) { _, option ->
                                Box(Modifier.fillMaxWidth().background(
                                    if (option.second) CinemaStyle.Selected else CinemaStyle.Surface,
                                    RoundedCornerShape(CinemaStyle.Corner)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                                    Text(option.first, color = if (option.second) CinemaStyle.Accent else CinemaStyle.Text,
                                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
