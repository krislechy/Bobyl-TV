package com.bobyl.tv

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class CatalogGridLayoutTest {
    private fun films(count: Int) = List(count) { "https://example.org/film/$it" }

    @Test fun sixColumnsKeepTheFormerPosterBoundsOn1920PixelScreenAtDensityTwo() {
        val density = Density(2f)
        // The unchanged shell reserves 60dp for the rail, then 20dp on either side.
        val viewport = 960.dp - 60.dp - 40.dp
        val width = catalogCardWidth(viewport, 6)
        assertEquals(129.dp, width)
        val cells = CatalogGridCells(6, width)
        with(density) {
            val sizes = with(cells) { calculateCrossAxisCellSizes(1688, 28) }
            assertEquals(List(6) { 258 }, sizes)
            val first = 60.dp.roundToPx() + 20.dp.roundToPx() + CatalogCardGutter.roundToPx()
            assertEquals(176, first)
            assertEquals(1606, first + 5 * (sizes.first() + CatalogCardSpacing.roundToPx()))
            assertEquals(1864, first + 5 * (sizes.first() + 28) + sizes.last())
        }
    }

    @Test fun cellRoundingAndMinimumWidthMatchRowsForOneSixAndTwelveColumns() {
        for (columns in listOf(1, 6, 12)) for (scale in listOf(1f, 1.25f, 2f)) {
            val density = Density(scale)
            val viewport = 861.dp
            val oldWidth = ((viewport - (columns - 1).dp * 14 - 16.dp) / columns).coerceAtLeast(44.dp)
            val cells = CatalogGridCells(columns, catalogCardWidth(viewport, columns))
            with(density) {
                val widths = with(cells) { calculateCrossAxisCellSizes(1000, 14.dp.roundToPx()) }
                assertEquals(List(columns) { oldWidth.roundToPx() }, widths)
            }
        }
        assertEquals(44.dp, catalogCardWidth(200.dp, 12))
    }

    @Test fun fullSpanContentStartsAtTheOriginalViewportIncludingItsFocusFrame() {
        for (scale in listOf(1f, 1.25f, 2f)) with(Density(scale)) {
            val outerLeft = 20.dp.roundToPx()
            val gridContentLeft = outerLeft + CatalogCardGutter.roundToPx()
            assertEquals(outerLeft, gridContentLeft + catalogHeaderOffset())
            // Width is the whole old viewport, not the grid's inset span or rounded cell sum.
            val fullWidth = 861.dp.roundToPx()
            assertEquals(outerLeft + fullWidth, gridContentLeft + catalogHeaderOffset() + fullWidth)
        }
    }

    @Test fun legacyRowAnchorsPreserveTheirFilmAndOffsetWithDynamicHeaders() {
        for (columns in listOf(1, 6, 12)) for (headerCount in 0..3) {
            val headers = List(headerCount) { "header-$it" }
            val layout = CatalogGridLayout(columns, headers, films(2 * columns + 1), listOf("footer"))
            val old = CatalogGridAnchor("", headerCount + 1, 37, legacyRow = true)
            val restored = CatalogGridAnchor.restore(old.save())
            assertEquals(headerCount + columns, layout.restoreIndex(restored))
            assertEquals("https://example.org/film/$columns", layout.keyAt(layout.restoreIndex(restored)))
            assertEquals(37, restored.scrollOffset)
            for (header in headers.indices) {
                assertEquals(header, layout.restoreIndex(CatalogGridAnchor("", header, 5, legacyRow = true)))
            }
        }
    }

    @Test fun legacyFooterAfterAPartialRowMapsPastActualFilmsNotTheNextFullRow() {
        for (columns in listOf(1, 6, 12)) {
            val count = 2 * columns + 1
            val rows = (count + columns - 1) / columns
            val layout = CatalogGridLayout(columns, listOf("notice", "filters"), films(count), listOf("loading", "error"))
            assertEquals(2 + count, layout.restoreIndex(CatalogGridAnchor("", 2 + rows, 11, true)))
            assertEquals(3 + count, layout.restoreIndex(CatalogGridAnchor("", 3 + rows, 11, true)))
        }
    }

    @Test fun urlAnchorSurvivesHeaderInsertionPaginationAndChangedColumnCount() {
        val original = CatalogGridLayout(6, listOf("filters"), films(20), listOf("footer"))
        val anchor = CatalogGridAnchor(original.keyAt(14), 14, 43)
        val restored = CatalogGridAnchor.restore(anchor.save())
        for (columns in listOf(1, 6, 12)) {
            val current = CatalogGridLayout(columns, listOf("notice", "search", "results"), films(40), listOf("loading"))
            assertEquals(16, current.restoreIndex(restored))
            assertEquals(original.keyAt(14), current.keyAt(current.restoreIndex(restored)))
            assertEquals(43, restored.scrollOffset)
        }
    }

    @Test fun fullSpanHeaderAndFooterAnchorsUseTheirKeysInsteadOfOldIndices() {
        val layout = CatalogGridLayout(6, listOf("notice", "filters"), films(18), listOf("error"))
        assertEquals(1, layout.restoreIndex(CatalogGridAnchor("filters", 0, 7)))
        assertEquals(20, layout.restoreIndex(CatalogGridAnchor("error", 7, 9)))
    }

    @Test fun removedFilmsAndEmptyResultsHaveABoundedFallback() {
        val shorter = CatalogGridLayout(6, listOf("filters"), films(2), listOf("footer"))
        assertEquals(3, shorter.restoreIndex(CatalogGridAnchor("missing-film", 50, 8)))
        val empty = CatalogGridLayout(6, emptyList(), emptyList(), emptyList())
        assertEquals(0, empty.restoreIndex(CatalogGridAnchor("missing-film", 50, 8)))
        assertFalse(empty.nearEnd(0))
        assertFalse(shorter.nearEnd(-1))
    }

    @Test fun paginationStartsThreeLogicalRowsFromTheEndForOneSixAndTwelveColumns() {
        for (columns in listOf(1, 6, 12)) for (headerCount in 0..3) {
            val layout = CatalogGridLayout(columns, List(headerCount) { "header-$it" },
                films(3 * columns + 1), listOf("footer"))
            // Four film rows plus one footer: the threshold is the third film row.
            assertFalse(layout.nearEnd(headerCount + 2 * columns - 1))
            assertTrue(layout.nearEnd(headerCount + 2 * columns))
            assertTrue(layout.nearEnd(headerCount + 3 * columns))
        }
    }

    @Test fun loadingAndErrorFullSpanItemsCountAsSeparateRowsForPagination() {
        for (columns in listOf(1, 6, 12)) {
            val layout = CatalogGridLayout(columns, listOf("filters"), films(3 * columns + 1),
                listOf("loading", "error"))
            assertFalse(layout.nearEnd(1 + 3 * columns - 1))
            assertTrue(layout.nearEnd(1 + 3 * columns))
            assertTrue(layout.nearEnd(layout.itemCount - 1))
        }
    }
}
