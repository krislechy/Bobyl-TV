package com.bobyl.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared surfaces and focus colours for the TV screening-room theme. */
internal object CinemaStyle {
    val Background = Color(0xFF090F18)
    val Rail = Color(0xFF101923)
    val Surface = Color(0xFF172234)
    val Raised = Color(0xFF23364A)
    val Accent = Color(0xFFC9E5FF)
    val Focus = Color(0xFFEDF8FF)
    val OnAccent = Color(0xFF112033)
    val Text = Color(0xFFF3F7FC)
    val Muted = Color(0xFFA9BACD)
    val Outline = Color(0xFF3C526B)
    val Selected = Color(0xFF304961)
    val Pressed = Color(0xFF3D5D78)
    val Error = Color(0xFFFFB9C3)
    val Success = Color(0xFFA8DFCB)
    val Scrim = Color(0xD9090F18)
    val Corner = 12.dp
    val PanelCorner = 20.dp
    val PagePadding = 24.dp
    val SectionGap = 24.dp
    val FocusDuration = 140
    val PressDuration = 100
    val Display = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
    val Heading = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val Title = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
}
