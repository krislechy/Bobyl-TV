package com.bobyl.tv

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter

internal enum class SkeletonLayout { Posters, Rows, Options, Details, Text, Profile, Suggestions, Status, Tabs, Filters }

private class SkeletonClock {
    var users by mutableIntStateOf(0)
    var pulse by mutableStateOf<State<Float>>(mutableFloatStateOf(.7f))
}

private val LocalSkeletonClock = staticCompositionLocalOf { SkeletonClock() }
internal val LocalStartupImages = staticCompositionLocalOf<StartupImageTracker?> { null }

/** A single draw-only pulse, running only while at least one placeholder is visible. */
@Composable
internal fun SkeletonLoadingTheme(content: @Composable () -> Unit) {
    val clock = remember { SkeletonClock() }
    val motion = remember { Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled() }
    val still = remember { mutableFloatStateOf(.7f) }
    val pulse = if (clock.users > 0 && motion) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(.55f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    } else still
    SideEffect { clock.pulse = pulse }
    CompositionLocalProvider(LocalSkeletonClock provides clock, content = content)
}

/** Passive: never clickable or focusable, and never conceals errors after loading ends. */
@Composable
internal fun SkeletonShape(modifier: Modifier = Modifier) {
    val clock = LocalSkeletonClock.current
    DisposableEffect(clock) {
        clock.users++
        onDispose { clock.users-- }
    }
    Box(modifier.drawBehind {
        drawRoundRect(CinemaStyle.Raised, cornerRadius = CornerRadius(6.dp.toPx()), alpha = clock.pulse.value)
    })
}

@Composable
internal fun SkeletonLoading(layout: SkeletonLayout = SkeletonLayout.Rows, modifier: Modifier = Modifier,
    rows: Int = 3, columns: Int = 6, actions: Int = 2) {
    val gap = when (layout) { SkeletonLayout.Posters -> 18.dp; SkeletonLayout.Rows -> 6.dp; else -> 12.dp }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        when (layout) {
            SkeletonLayout.Posters -> repeat(rows.coerceAtLeast(1)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(columns.coerceIn(1, 12)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SkeletonShape(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            Column(Modifier.height(38.dp).fillMaxWidth().padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SkeletonShape(Modifier.fillMaxWidth(.85f).height(12.dp))
                                SkeletonShape(Modifier.fillMaxWidth(.6f).height(12.dp))
                            }
                        }
                    }
                }
            }
            SkeletonLayout.Rows -> repeat(rows.coerceAtLeast(1)) {
                Row(Modifier.fillMaxWidth().background(CinemaStyle.Surface, RoundedCornerShape(CinemaStyle.Corner)).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonShape(Modifier.size(58.dp, 86.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SkeletonShape(Modifier.fillMaxWidth(.7f).height(16.dp))
                        SkeletonShape(Modifier.fillMaxWidth(.5f).height(12.dp))
                        SkeletonShape(Modifier.fillMaxWidth(.25f).height(10.dp))
                    }
                    repeat(actions.coerceIn(0, 2)) { SkeletonShape(Modifier.size(48.dp)) }
                }
            }
            SkeletonLayout.Suggestions -> repeat(rows.coerceAtLeast(1)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(2) {
                        Row(Modifier.weight(1f).background(CinemaStyle.Surface, RoundedCornerShape(CinemaStyle.Corner)).padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            SkeletonShape(Modifier.size(46.dp, 69.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SkeletonShape(Modifier.fillMaxWidth(.9f).height(16.dp))
                                SkeletonShape(Modifier.fillMaxWidth(.65f).height(16.dp))
                                SkeletonShape(Modifier.width(44.dp).height(12.dp))
                            }
                        }
                    }
                }
            }
            SkeletonLayout.Options -> repeat(rows.coerceAtLeast(1)) {
                SkeletonShape(Modifier.fillMaxWidth().height(44.dp))
            }
            SkeletonLayout.Details -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { SkeletonShape(Modifier.width(108.dp).height(44.dp)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    SkeletonShape(Modifier.size(150.dp, 225.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SkeletonShape(Modifier.fillMaxWidth(.65f).height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(3) { SkeletonShape(Modifier.size(100.dp, 38.dp)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SkeletonShape(Modifier.width(100.dp).height(16.dp))
                            SkeletonShape(Modifier.size(52.dp, 44.dp))
                        }
                        repeat(3) { index ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SkeletonShape(Modifier.width(120.dp).height(16.dp))
                                Box(Modifier.weight(1f)) {
                                    SkeletonShape(Modifier.fillMaxWidth(listOf(.55f, .85f, .4f)[index]).height(16.dp))
                                }
                            }
                        }
                    }
                }
                repeat(3) { index -> SkeletonShape(Modifier.fillMaxWidth(if (index == 2) .7f else 1f).height(16.dp)) }
            }
            SkeletonLayout.Text -> repeat(rows.coerceAtLeast(1)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonShape(Modifier.width(140.dp).height(15.dp))
                    SkeletonShape(Modifier.fillMaxWidth().height(13.dp))
                    SkeletonShape(Modifier.fillMaxWidth(.8f).height(13.dp))
                }
            }
            SkeletonLayout.Profile -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkeletonShape(Modifier.size(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonShape(Modifier.fillMaxWidth(.55f).height(20.dp))
                    SkeletonShape(Modifier.fillMaxWidth(.7f).height(13.dp))
                }
            }
            SkeletonLayout.Status -> {
                SkeletonShape(Modifier.width(160.dp).height(10.dp))
                SkeletonShape(Modifier.width(110.dp).height(6.dp))
            }
            SkeletonLayout.Tabs, SkeletonLayout.Filters -> {
                Row(Modifier.fillMaxWidth().background(CinemaStyle.Rail, RoundedCornerShape(CinemaStyle.Corner)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) { SkeletonShape(Modifier.weight(1f).height(44.dp)) }
                }
                if (layout == SkeletonLayout.Filters) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    repeat(2) { SkeletonShape(Modifier.weight(1f).height(48.dp)) }
                }
            }
        }
    }
}

/** Keeps existing image sizing/callbacks; the placeholder ends on success or error. */
@Composable
internal fun SkeletonAsyncImage(model: Any?, contentDescription: String?, modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit, colorFilter: ColorFilter? = null,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null) {
    val remote = model != null && model != "" && model !is Int
    var loading by remember(model) { mutableStateOf(remote) }
    val startupImages = LocalStartupImages.current
    if (loading && remote && startupImages != null) DisposableEffect(startupImages, model) {
        val finished = startupImages.imageStarted()
        onDispose { finished() }
    }
    val clock = LocalSkeletonClock.current
    if (loading) DisposableEffect(clock) {
        clock.users++
        onDispose { clock.users-- }
    }
    // Draw over the original image instead of wrapping it in a size-changing Box.
    val placeholder = if (loading) Modifier.drawWithContent {
        drawRoundRect(CinemaStyle.Raised, cornerRadius = CornerRadius(CinemaStyle.Corner.toPx()), alpha = clock.pulse.value)
    } else Modifier
    coil3.compose.AsyncImage(model, contentDescription, modifier.then(placeholder), contentScale = contentScale, colorFilter = colorFilter,
            onLoading = { loading = remote },
            onSuccess = { loading = false; onSuccess?.invoke(it) },
            onError = { loading = false; onError?.invoke(it) })
}
