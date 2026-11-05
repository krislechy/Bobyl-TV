package com.bobyl.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val CatalogCardGutter = 8.dp
internal val CatalogCardSpacing = 14.dp

internal fun catalogCardWidth(viewportWidth: Dp, columns: Int): Dp =
    ((viewportWidth - CatalogCardSpacing * (columns - 1) - CatalogCardGutter * 2) / columns)
        .coerceAtLeast(44.dp)

/** Keep the old Row's equal rounded widths, rather than distributing leftover pixels. */
internal data class CatalogGridCells(val columns: Int, val cardWidth: Dp) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> =
        List(columns) { cardWidth.roundToPx() }
}

internal data class CatalogGridAnchor(
    val key: String,
    val index: Int,
    val scrollOffset: Int,
    val legacyRow: Boolean = false,
) {
    fun save(): List<Any> = listOf(key, index, scrollOffset, legacyRow)

    companion object {
        fun restore(saved: List<Any>) = CatalogGridAnchor(
            saved[0] as String, saved[1] as Int, saved[2] as Int, saved[3] as Boolean,
        )
    }
}

/** Item indices and logical row indices differ once every film is its own lazy item. */
internal class CatalogGridLayout(
    val columns: Int,
    val headers: List<String>,
    val films: List<String>,
    val footers: List<String>,
) {
    val itemCount: Int get() = headers.size + films.size + footers.size
    private val filmRowCount: Int get() = (films.size + columns - 1) / columns

    fun keyAt(index: Int): String = when {
        index < 0 -> ""
        index < headers.size -> headers[index]
        index < headers.size + films.size -> films[index - headers.size]
        else -> footers.getOrNull(index - headers.size - films.size).orEmpty()
    }

    fun restoreIndex(anchor: CatalogGridAnchor): Int {
        if (anchor.key.isNotEmpty()) {
            headers.indexOf(anchor.key).takeIf { it >= 0 }?.let { return it }
            films.indexOf(anchor.key).takeIf { it >= 0 }?.let { return headers.size + it }
            footers.indexOf(anchor.key).takeIf { it >= 0 }?.let { return headers.size + films.size + it }
        }
        val index = if (!anchor.legacyRow || anchor.index < headers.size) anchor.index else {
            val row = anchor.index - headers.size
            if (row < filmRowCount) headers.size + row * columns
            else headers.size + films.size + row - filmRowCount
        }
        return index.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
    }

    fun nearEnd(lastVisibleItem: Int, rows: Int = 3): Boolean {
        if (lastVisibleItem < 0 || itemCount == 0) return false
        val logicalRow = when {
            lastVisibleItem < headers.size -> lastVisibleItem
            lastVisibleItem < headers.size + films.size ->
                headers.size + (lastVisibleItem - headers.size) / columns
            else -> headers.size + filmRowCount + lastVisibleItem - headers.size - films.size
        }
        return logicalRow >= headers.size + filmRowCount + footers.size - rows
    }
}

private class CatalogGridScrollState(val grid: LazyGridState, var pending: CatalogGridAnchor?)

/** Save only on Android state save, not by publishing every scroll pixel into composition. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberCatalogGridState(
    layout: CatalogGridLayout,
    legacy: LazyListState,
    ready: Boolean,
): LazyGridState {
    val latestLayout by rememberUpdatedState(layout)
    // Prepare the next viewport and retain the previous one for D-pad reversals.
    val cacheWindow = remember {
        LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 1f)
    }
    val saver = remember(cacheWindow) {
        listSaver<CatalogGridScrollState, Any>(
            save = { holder ->
                val grid = holder.grid
                (holder.pending ?: CatalogGridAnchor(
                    key = grid.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == grid.firstVisibleItemIndex }?.key as? String
                        ?: latestLayout.keyAt(grid.firstVisibleItemIndex),
                    index = grid.firstVisibleItemIndex,
                    scrollOffset = grid.firstVisibleItemScrollOffset,
                )).save()
            },
            restore = { saved ->
                val anchor = CatalogGridAnchor.restore(saved)
                CatalogGridScrollState(LazyGridState(cacheWindow = cacheWindow), anchor)
            },
        )
    }
    val holder = rememberSaveable(saver = saver) {
        CatalogGridScrollState(LazyGridState(cacheWindow = cacheWindow), CatalogGridAnchor(
            key = "", index = legacy.firstVisibleItemIndex,
            scrollOffset = legacy.firstVisibleItemScrollOffset, legacyRow = true,
        ))
    }
    LaunchedEffect(holder, layout, ready) {
        val anchor = holder.pending
        if (ready && anchor != null) {
            holder.grid.scrollToItem(layout.restoreIndex(anchor), anchor.scrollOffset)
            holder.pending = null
        }
    }
    return holder.grid
}

internal fun Density.catalogHeaderOffset(): Int = -CatalogCardGutter.roundToPx()

/** Headers keep the former full viewport width; the grid's 8dp card gutters are not a clip. */
@Composable
internal fun CatalogGridFullSpan(viewportWidth: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.layout { measurable, constraints ->
        val width = viewportWidth.roundToPx()
        val child = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, child.height) { child.placeRelative(catalogHeaderOffset(), 0) }
    }, content = content)
}
