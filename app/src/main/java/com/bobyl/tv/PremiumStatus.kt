package com.bobyl.tv

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.bobyl.tv.data.AccountProfile

/** The same Premium summary and purchased-period fill on Home and in the account dialog. */
@Composable
internal fun PremiumStatus(profile: AccountProfile?, loading: Boolean, fraction: Float?, modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null) {
    val active = profile?.authenticated == true && profile.premium
    HomeStatistic("Premium", premiumStatusValue(profile, loading), FieldGlyph.Star, modifier,
        highlighted = active, progress = fraction.takeIf { active }, onClick = onClick)
}

/** The account identity shares Home's actual Premium value and purchased-period background. */
@Composable
internal fun AccountPremiumSummary(profile: AccountProfile?, loading: Boolean, fraction: Float?,
    modifier: Modifier = Modifier, identity: @Composable RowScope.() -> Unit) {
    val active = profile?.authenticated == true && profile.premium
    Row(modifier.cinemaSurface(false, selected = active).statisticProgressFill(fraction.takeIf { active })
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        identity()
        StatisticValue("Premium", premiumStatusValue(profile, loading), Modifier.width(110.dp))
    }
}

private fun premiumStatusValue(profile: AccountProfile?, loading: Boolean): String? = when {
    loading && profile == null -> null
    profile?.authenticated == true && profile.premium -> homePremiumLabel(profile.premiumInfo)
    profile != null -> "Не активен"
    else -> "—"
}

private fun Modifier.statisticProgressFill(progress: Float?): Modifier = drawWithContent {
    progress?.let { drawRect(CinemaStyle.Accent.copy(alpha = .16f),
        size = size.copy(width = size.width * it.coerceIn(0f, 1f))) }
    drawContent()
}

@Composable
internal fun HomeStatistic(label: String, value: String?, icon: FieldGlyph, modifier: Modifier,
    highlighted: Boolean = false, progress: Float? = null, onClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val target = if (onClick != null) rememberCinemaButtonFocusTarget(focused) else null
    val action = if (onClick == null) Modifier else Modifier.onFocusChanged { focused = it.isFocused }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    Row(modifier.height(62.dp).cinemaSurface(focused, selected = highlighted, pressed = pressed, focusFrameTarget = target)
        .statisticProgressFill(progress).then(action).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (icon == FieldGlyph.Eye) WatchStatusIcon(true, Modifier.size(25.dp))
        else FieldIcon(icon, CinemaStyle.Accent, Modifier.size(25.dp))
        StatisticValue(label, value, Modifier.weight(1f))
    }
}

@Composable
private fun StatisticValue(label: String, value: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = CinemaStyle.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value == null) SkeletonShape(Modifier.fillMaxWidth(.8f).height(17.dp))
        else Text(value, color = CinemaStyle.Text, fontSize = if (value.length > 12) 13.sp else 18.sp,
            lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = if (value.length > 12) 2 else 1, overflow = TextOverflow.Ellipsis)
    }
}
