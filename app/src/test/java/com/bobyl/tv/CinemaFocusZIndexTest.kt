package com.bobyl.tv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

class CinemaFocusZIndexTest {
    private val constraints = Constraints.fixed(96, 144)
    private val scope = object : MeasureScope {
        override val density = 1f
        override val fontScale = 1f
        override val layoutDirection = LayoutDirection.Ltr
    }

    @Test fun focusChangesAfterMeasurementUseTheLatestOrderWithoutRemeasuring() {
        for (initial in listOf(false, true)) {
            val focus = mutableStateOf(initial)
            val measurable = RecordingMeasurable()
            val result = scope.measureCinemaFocusZIndex(measurable, constraints, focus)
            assertEquals(constraints, measurable.lastConstraints)
            assertEquals(96, result.width)
            assertEquals(144, result.height)

            focus.value = !initial
            result.placeChildren()
            assertEquals(IntOffset.Zero to if (initial) 0f else 1f, measurable.child.placements.last())
            focus.value = initial
            result.placeChildren()
            assertEquals(IntOffset.Zero to if (initial) 1f else 0f, measurable.child.placements.last())
            assertEquals(1, measurable.measurements)
        }
    }

    @Test fun snapshotChangesInvalidatePlacementButNeverMeasurement() {
        val focus = mutableStateOf(false)
        val measurable = RecordingMeasurable()
        val measurementScope = Any()
        val placementScope = Any()
        var measureInvalidations = 0
        var placementInvalidations = 0
        val onMeasureChanged: (Any) -> Unit = { measureInvalidations++ }
        val onPlacementChanged: (Any) -> Unit = { placementInvalidations++ }
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            lateinit var result: MeasureResult
            observer.observeReads(measurementScope, onMeasureChanged) {
                result = scope.measureCinemaFocusZIndex(measurable, constraints, focus)
            }
            fun placeAndObserve() {
                observer.observeReads(placementScope, onPlacementChanged) { result.placeChildren() }
            }
            placeAndObserve()

            for ((index, next) in listOf(true, false).withIndex()) {
                focus.value = next
                Snapshot.sendApplyNotifications()
                assertEquals(0, measureInvalidations)
                assertEquals(index + 1, placementInvalidations)
                assertEquals(1, measurable.measurements)
                // Re-subscribe the actual placement after each invalidation.
                placeAndObserve()
                assertEquals(IntOffset.Zero to if (next) 1f else 0f, measurable.child.placements.last())
            }
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    private class RecordingMeasurable : Measurable {
        val child = RecordingPlaceable()
        var measurements = 0
        var lastConstraints: Constraints? = null
        override val parentData: Any? = null
        override fun measure(constraints: Constraints): Placeable {
            measurements++
            lastConstraints = constraints
            child.measuredWith(constraints)
            return child
        }
        override fun minIntrinsicWidth(height: Int): Int = error("No intrinsic measurement expected")
        override fun maxIntrinsicWidth(height: Int): Int = error("No intrinsic measurement expected")
        override fun minIntrinsicHeight(width: Int): Int = error("No intrinsic measurement expected")
        override fun maxIntrinsicHeight(width: Int): Int = error("No intrinsic measurement expected")
    }

    private class RecordingPlaceable : Placeable() {
        val placements = mutableListOf<Pair<IntOffset, Float>>()
        fun measuredWith(constraints: Constraints) {
            measurementConstraints = constraints
            measuredSize = IntSize(96, 144)
        }
        override fun get(alignmentLine: AlignmentLine): Int = AlignmentLine.Unspecified
        override fun placeAt(position: IntOffset, zIndex: Float, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
            placements += position to zIndex
        }
    }
}
