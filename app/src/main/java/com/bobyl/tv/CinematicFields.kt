package com.bobyl.tv

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

internal enum class FieldGlyph { Search, Clear, Microphone, Reply, Like, Delete, Send, More, MoreVertical, Refresh, Restore, Eye, Star, Play, Comments, Bookmark, Back }

/** Shared field surface; action buttons remain separate D-pad and accessibility targets. */
@Composable
internal fun Input(
    value: String,
    hint: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    searchField: Boolean = false,
    onSubmit: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val editingValue = if (editor.text == value) editor else TextFieldValue(value, TextRange(value.length))
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(CinemaStyle.Corner)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .onFocusChanged { focused = it.hasFocus }
            .background(CinemaStyle.Surface, shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else CinemaStyle.Outline, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (searchField) FieldIcon(FieldGlyph.Search, CinemaStyle.Muted, Modifier.padding(horizontal = 4.dp).size(26.dp))
        BasicTextField(
            value = editingValue, onValueChange = { editor = it; if (it.text != value) onChange(it.text) },
            modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = hint }
                .pointerInput(Unit) { detectTapGestures(onTap = { keyboard?.show() }) }
                .onFocusChanged { if (it.isFocused) keyboard?.hide() }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key in setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)) {
                        keyboard?.show()
                    }
                    // At the text boundary, arrows leave the editor for the adjacent TV controls.
                    // Within the text, keep normal cursor movement and selection.
                    if (event.type != KeyEventType.KeyDown || !editingValue.selection.collapsed) false
                    else when {
                        searchField && event.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        searchField && event.key == Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        event.key == Key.DirectionRight && editingValue.selection.end == value.length -> focusManager.moveFocus(FocusDirection.Right)
                        event.key == Key.DirectionLeft && editingValue.selection.start == 0 -> focusManager.moveFocus(FocusDirection.Left)
                        else -> false
                    }
                },
            textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                autoCorrectEnabled = false,
                imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }),
            cursorBrush = SolidColor(CinemaStyle.Accent), singleLine = true,
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, color = CinemaStyle.Muted, fontSize = 18.sp, maxLines = 1)
                    inner()
                }
            },
        )
        actions()
    }
}

@Composable
internal fun FieldAction(glyph: FieldGlyph, label: String, primary: Boolean = false, enabled: Boolean = true, checked: Boolean = false, loading: Boolean = false, modifier: Modifier = Modifier, tooltip: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(CinemaStyle.Corner)
    Box(
        modifier.size(48.dp).clip(shape)
            .background(if (enabled && primary) CinemaStyle.Accent else CinemaStyle.Raised)
            .border(if (focused) 3.dp else 1.dp, if (focused) CinemaStyle.Focus else CinemaStyle.Outline, shape)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label }
            .clickable(enabled = enabled || loading, role = Role.Button, onClickLabel = label, onClick = { if (enabled && !loading) onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            val transition = rememberInfiniteTransition(label = "history-action")
            val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinner")
            Canvas(Modifier.size(24.dp)) {
                drawArc(CinemaStyle.Text, angle, 270f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        else if (glyph == FieldGlyph.Eye) WatchStatusIcon(checked, enabled = enabled, action = true)
        else FieldIcon(glyph, if (glyph == FieldGlyph.Bookmark && checked) CinemaStyle.Accent
            else if (glyph == FieldGlyph.Star && checked) Color(0xFFF4C85E)
            else if (!enabled) CinemaStyle.Muted else if (primary) CinemaStyle.OnAccent else CinemaStyle.Text,
            Modifier.size(if (glyph == FieldGlyph.Star) 28.dp else if (glyph == FieldGlyph.Bookmark) 26.dp else 24.dp), filled = checked)
        if (tooltip) ActionTooltip(label, focused)
    }
}

@Composable
private fun FieldIcon(glyph: FieldGlyph, color: Color, modifier: Modifier, filled: Boolean = false) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val stroke = 1.8f * unit
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun line(x: Float, y: Float, x2: Float, y2: Float) =
            drawLine(color, point(x, y), point(x2, y2), stroke, StrokeCap.Round)
        when (glyph) {
            FieldGlyph.Back -> {
                line(20f, 12f, 4f, 12f)
                line(4f, 12f, 11f, 5f); line(4f, 12f, 11f, 19f)
            }
            FieldGlyph.Bookmark -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(8f * unit, 3f * unit); lineTo(16f * unit, 3f * unit)
                    quadraticTo(18f * unit, 3f * unit, 18f * unit, 5f * unit)
                    lineTo(18f * unit, 21f * unit); lineTo(12f * unit, 17f * unit)
                    lineTo(6f * unit, 21f * unit); lineTo(6f * unit, 5f * unit)
                    quadraticTo(6f * unit, 3f * unit, 8f * unit, 3f * unit); close()
                }
                drawPath(path, color, style = if (filled) androidx.compose.ui.graphics.drawscope.Fill
                    else Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            FieldGlyph.Play -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(8f * unit, 4f * unit); lineTo(20f * unit, 12f * unit)
                    lineTo(8f * unit, 20f * unit); close()
                }
                drawPath(path, color)
            }
            FieldGlyph.Comments -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(5f * unit, 4f * unit); lineTo(19f * unit, 4f * unit)
                    quadraticTo(21f * unit, 4f * unit, 21f * unit, 6f * unit)
                    lineTo(21f * unit, 15f * unit)
                    quadraticTo(21f * unit, 17f * unit, 19f * unit, 17f * unit)
                    lineTo(10f * unit, 17f * unit); lineTo(5f * unit, 21f * unit)
                    lineTo(5f * unit, 17f * unit)
                    quadraticTo(3f * unit, 17f * unit, 3f * unit, 15f * unit)
                    lineTo(3f * unit, 6f * unit)
                    quadraticTo(3f * unit, 4f * unit, 5f * unit, 4f * unit); close()
                }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                line(7f, 8.5f, 17f, 8.5f); line(7f, 12.5f, 14f, 12.5f)
            }
            FieldGlyph.Restore -> {
                drawArc(color, -130f, 280f, false, topLeft = point(5f, 5f), size = androidx.compose.ui.geometry.Size(15f * unit, 15f * unit),
                    style = Stroke(stroke, cap = StrokeCap.Round))
                line(4f, 4f, 4f, 10f); line(4f, 10f, 10f, 10f)
            }
            FieldGlyph.Eye -> Unit // Rendered as the shared SVG-derived WatchStatusIcon above.
            FieldGlyph.Star -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(12f * unit, 2f * unit)
                    listOf(15.1f to 8.4f, 22.2f to 9.4f, 17.1f to 14.4f, 18.3f to 21.5f,
                        12f to 18.2f, 5.7f to 21.5f, 6.9f to 14.4f, 1.8f to 9.4f, 8.9f to 8.4f)
                        .forEach { (x, y) -> lineTo(x * unit, y * unit) }
                    close()
                }
                drawPath(path, color, style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(stroke))
            }
            FieldGlyph.Reply -> {
                line(10f, 5f, 4f, 11f); line(4f, 11f, 10f, 17f)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(4f * unit, 11f * unit); lineTo(13f * unit, 11f * unit)
                    quadraticTo(20f * unit, 11f * unit, 20f * unit, 20f * unit)
                }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            FieldGlyph.Like -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(12f * unit, 21f * unit)
                    cubicTo(-5f * unit, 10f * unit, 5f * unit, -2f * unit, 12f * unit, 7f * unit)
                    cubicTo(19f * unit, -2f * unit, 29f * unit, 10f * unit, 12f * unit, 21f * unit)
                    close()
                }
                drawPath(path, color, style = Stroke(stroke))
            }
            FieldGlyph.Delete -> {
                line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f)
                line(6f, 6f, 7f, 21f); line(7f, 21f, 17f, 21f); line(17f, 21f, 18f, 6f)
                line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
            }
            FieldGlyph.Send -> {
                line(3f, 3f, 22f, 12f); line(22f, 12f, 3f, 21f)
                line(3f, 21f, 6f, 12f); line(6f, 12f, 3f, 3f); line(6f, 12f, 15f, 12f)
            }
            FieldGlyph.More -> { line(6f, 9f, 12f, 15f); line(12f, 15f, 18f, 9f) }
            FieldGlyph.MoreVertical -> {
                listOf(5f, 12f, 19f).forEach { y -> drawCircle(color, 1.8f * unit, point(12f, y)) }
            }
            FieldGlyph.Refresh -> {
                drawArc(color, 35f, 290f, false, point(4f, 4f), Size(16f * unit, 16f * unit), style = Stroke(stroke, cap = StrokeCap.Round))
                line(20f, 3f, 20f, 9f); line(20f, 9f, 14f, 9f)
            }
            FieldGlyph.Search -> {
                drawCircle(color, 7f * unit, point(10f, 10f), style = Stroke(stroke))
                line(15f, 15f, 21f, 21f)
            }
            FieldGlyph.Clear -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            FieldGlyph.Microphone -> {
                drawRoundRect(color, point(9f, 2f), Size(6f * unit, 13f * unit),
                    androidx.compose.ui.geometry.CornerRadius(3f * unit), style = Stroke(stroke))
                drawArc(color, 0f, 180f, false, point(5f, 6f), Size(14f * unit, 13f * unit), style = Stroke(stroke, cap = StrokeCap.Round))
                line(5f, 10f, 5f, 12f); line(19f, 10f, 19f, 12f)
                line(12f, 19f, 12f, 22f); line(9f, 22f, 15f, 22f)
            }
        }
    }
}
