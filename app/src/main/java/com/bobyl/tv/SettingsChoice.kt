package com.bobyl.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text

@Composable
internal fun SettingsChoice(label: String, options: List<Pair<String, String>>, selected: String, compactPopup: Boolean = true, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val triggerFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val choices = remember(options) { options.distinctBy { it.first } }
    val current = choices.firstOrNull { it.first == selected }?.second ?: selected.ifBlank { "Не выбрано" }
    SettingValueRow(label, current, Modifier.focusRequester(triggerFocus), dropdown = true) {
        restoreFocus = true
        expanded = true
    }
    LaunchedEffect(expanded, windowFocused) {
        if (!expanded && restoreFocus && windowFocused) {
            withFrameNanos { }
            inputMode.requestInputMode(InputMode.Keyboard)
            triggerFocus.requestFocus()
            restoreFocus = false
        }
    }
    if (expanded) Dialog({ expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val selectedIndex = choices.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
        val optionFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            inputMode.requestInputMode(InputMode.Keyboard)
            optionFocus.requestFocus()
        }
        val popupModifier = if (compactPopup) Modifier.widthIn(max = 320.dp).fillMaxWidth()
            else Modifier.fillMaxWidth(.62f).heightIn(max = 440.dp)
        Column(popupModifier
            .clip(RoundedCornerShape(CinemaStyle.PanelCorner)).background(CinemaStyle.Background)
            .padding(if (compactPopup) 20.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(if (compactPopup) 12.dp else 16.dp)) {
            Text(label, color = CinemaStyle.Text, fontSize = if (compactPopup) 22.sp else 26.sp, fontWeight = FontWeight.SemiBold)
            val listModifier = if (compactPopup) Modifier.height((choices.size * 52 + 8).coerceAtMost(260).dp)
                else Modifier.weight(1f, fill = false)
            LazyColumn(listModifier.fillMaxWidth().popupScrollIndicator(listState).padding(end = 10.dp), state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
                items(choices, key = { it.first }) { (value, title) ->
                    val modifier = if (value == choices.getOrNull(selectedIndex)?.first)
                        Modifier.fillMaxWidth().focusRequester(optionFocus) else Modifier.fillMaxWidth()
                    CinematicButton(title, value == selected, modifier) {
                        choose(value)
                        expanded = false
                    }
                }
            }
            if (!compactPopup) CinematicButton("Отмена", false) { expanded = false }
        }
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, color = CinemaStyle.Accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f).height(1.dp).background(CinemaStyle.Outline))
        }
        content()
    }
}

@Composable
internal fun SettingsToggle(label: String, checked: Boolean, toggle: () -> Unit) {
    SettingValueRow(label, if (checked) "Включено" else "Выключено", onClick = toggle)
}

@Composable
private fun SettingValueRow(
    label: String, value: String, modifier: Modifier = Modifier, dropdown: Boolean = false, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CinemaStyle.Corner)
    val foreground = CinemaStyle.Text
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", color = CinemaStyle.Text, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.weight(.42f))
        Row(modifier.weight(.58f).heightIn(min = 48.dp).clip(shape)
            .background(if (focused) CinemaStyle.Raised else CinemaStyle.Surface)
            .border(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else CinemaStyle.Outline, shape)
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = foreground, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (dropdown) Canvas(Modifier.size(12.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(foreground, Offset(0f, size.height * .3f), Offset(size.width * .5f, size.height * .75f), stroke)
                drawLine(foreground, Offset(size.width * .5f, size.height * .75f), Offset(size.width, size.height * .3f), stroke)
            }
        }
    }
}

/** Draws in the settings page's existing gutter; never measures, scrolls or receives input. */
internal fun Modifier.settingsScrollIndicator(state: LazyListState): Modifier = listScrollIndicator(state, settings = true)

/** Visual only: leaves native scrolling and focus handling intact. */
internal fun Modifier.popupScrollIndicator(state: LazyListState): Modifier = listScrollIndicator(state, settings = false)

private fun Modifier.listScrollIndicator(state: LazyListState, settings: Boolean): Modifier = drawWithContent {
    drawContent()
    if (!state.canScrollBackward && !state.canScrollForward) return@drawWithContent
    val layout = state.layoutInfo
    val first = layout.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
    val last = layout.visibleItemsInfo.lastOrNull() ?: return@drawWithContent
    if (layout.totalItemsCount == 0) return@drawWithContent
    val start = if (!state.canScrollBackward) 0f else first.index +
        ((layout.viewportStartOffset - first.offset).toFloat() / first.size.coerceAtLeast(1)).coerceIn(0f, 1f)
    val end = if (!state.canScrollForward) layout.totalItemsCount.toFloat() else last.index +
        ((layout.viewportEndOffset - last.offset).toFloat() / last.size.coerceAtLeast(1)).coerceIn(0f, 1f)
    val visible = (end - start).coerceAtLeast(.01f)
    val progress = (start / (layout.totalItemsCount - visible).coerceAtLeast(.01f)).coerceIn(0f, 1f)
    val thickness = 3.dp.toPx()
    val trackLength = size.height - (if (settings) 56.dp else 8.dp).toPx()
    if (trackLength <= 0f) return@drawWithContent
    val thumb = (trackLength * visible / layout.totalItemsCount).coerceAtLeast(24.dp.toPx()).coerceAtMost(trackLength)
    val origin = Offset(size.width - (if (settings) 12.dp else 4.dp).toPx(), (if (settings) 24.dp else 4.dp).toPx())
    val radius = CornerRadius(thickness / 2)
    drawRoundRect(CinemaStyle.Outline, origin, Size(thickness, trackLength), radius)
    drawRoundRect(CinemaStyle.Accent, origin + Offset(0f, (trackLength - thumb) * progress), Size(thickness, thumb), radius)
}
