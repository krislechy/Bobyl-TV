package com.bobyl.tv

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CinemaFocusFrameTest {
    private val viewport = Rect(0f, 0f, 1000f, 600f)
    private val first = Rect(20f, 40f, 140f, 220f)
    private val second = Rect(160f, 40f, 280f, 220f)
    private val third = Rect(300f, 40f, 420f, 220f)
    private val duration = CinemaStyle.FocusDuration * 1_000_000L

    private fun CinemaFocusFrameState.show(owner: Any, bounds: Rect = first) {
        focus(owner)
        this.bounds(owner, bounds, this@CinemaFocusFrameTest.viewport)
    }

    @Test fun handoffKeepsOneFrameUntilNextBoundsAndIgnoresStaleBlur() {
        val state = CinemaFocusFrameState()
        val a = Any()
        val b = Any()
        state.show(a)
        val blurA = state.blur(a)!!
        state.focus(b) // B can receive focus before its lazy layout is placed.
        state.clear(blurA)
        assertTrue(state.visible)
        assertEquals(first, state.rect)
        assertNull(state.blur(a)) // A disposal must not clear B either.
        state.bounds(a, third, viewport)
        assertEquals(first, state.rect)
        state.bounds(b, second, viewport)
        assertEquals(first, state.rect)
        state.advance(1L)
        state.advance(1L + duration / 2)
        assertTrue(state.rect!!.left > first.left)
        assertTrue(state.rect!!.left < second.left)
        state.advance(1L + duration)
        assertEquals(second, state.rect)
        assertFalse(state.animating)
    }

    @Test fun rapidRetargetStartsAtRenderedGeometryWithoutQueuedDestinations() {
        val state = CinemaFocusFrameState()
        val a = Any()
        val b = Any()
        val c = Any()
        state.show(a)
        state.show(b, second)
        state.advance(1L)
        state.advance(1L + duration / 3)
        val interrupted = state.rect
        state.show(c, third)
        assertEquals(interrupted, state.rect)
        state.advance(1L + duration / 3)
        assertEquals(interrupted, state.rect)
        state.advance(1L + duration / 3 + duration)
        assertEquals(third, state.rect)
        assertSame(c, state.owner)
    }

    @Test fun scrollTracksImmediatelyWithoutRestartingFocusJourney() {
        val state = CinemaFocusFrameState()
        val a = Any()
        val b = Any()
        state.show(a)
        state.show(b, second)
        state.advance(1L)
        state.advance(1L + duration / 2)
        val halfway = state.rect!!
        val scrolled = Rect(second.left, second.top - 30, second.right, second.bottom - 30)
        state.bounds(b, scrolled, viewport)
        assertEquals(halfway.top - 30, state.rect!!.top, .001f)
        assertEquals(halfway.left, state.rect!!.left, .001f)
        state.advance(1L + duration)
        assertEquals(scrolled, state.rect)
        assertFalse(state.animating)
        val nextScroll = Rect(scrolled.left, scrolled.top - 5, scrolled.right, scrolled.bottom - 5)
        state.bounds(b, nextScroll, viewport)
        assertEquals(nextScroll, state.rect)
        assertFalse(state.animating)
    }

    @Test fun distinctInstancesOfSameFilmRemainDistinctOwners() {
        data class FilmTarget(val url: String)
        val state = CinemaFocusFrameState()
        val grid = FilmTarget("same-film")
        val continuation = FilmTarget("same-film")
        assertEquals(grid, continuation)
        state.show(grid)
        state.show(continuation, second)
        assertSame(continuation, state.owner)
        assertNull(state.blur(grid))
        state.advance(1L)
        state.advance(1L + duration)
        assertEquals(second, state.rect)
    }

    @Test fun focusOutsideClearsOnlyTheCurrentGeneration() {
        val state = CinemaFocusFrameState()
        val film = Any()
        state.show(film)
        val ticket = state.blur(film)!!
        assertTrue(state.visible) // Deferred until focus handoff has settled.
        state.clear(ticket)
        assertFalse(state.visible)
        assertNull(state.owner)
        assertNull(state.rect)
    }

    @Test fun motionZeroRetainsFrameAndSnapsToNewBounds() {
        val state = CinemaFocusFrameState().apply { motionEnabled = false }
        val a = Any()
        val b = Any()
        state.show(a)
        state.focus(b)
        assertEquals(first, state.rect)
        state.bounds(b, second, viewport)
        assertEquals(second, state.rect)
        assertFalse(state.animating)
        assertTrue(state.visible)
    }

    @Test fun disablingMotionDuringFlightFinishesWithoutFading() {
        val state = CinemaFocusFrameState()
        state.show(Any())
        state.show(Any(), second)
        state.advance(1L)
        state.advance(duration / 2)
        state.motionEnabled = false
        state.advance(duration / 2 + 1)
        assertEquals(second, state.rect)
        assertTrue(state.visible)
        assertFalse(state.animating)
    }

    @Test fun nativeWindowBlurAndReturnRestoreWithoutAnotherFocusCallback() {
        val state = CinemaFocusFrameState()
        val film = Any()
        state.show(film)
        state.windowFocused = false
        assertFalse(state.visible)
        assertSame(film, state.owner)
        state.bounds(film, second, viewport)
        state.windowFocused = true
        assertTrue(state.visible)
        assertEquals(second, state.rect)
        assertSame(film, state.owner)
    }

    @Test fun differentNativeWindowsHaveIndependentGeometryAndVisibility() {
        val main = CinemaFocusFrameState()
        val dialog = CinemaFocusFrameState()
        main.show(Any())
        main.windowFocused = false
        dialog.show(Any(), third)
        assertFalse(main.visible)
        assertTrue(dialog.visible)
        assertEquals(first, main.rect)
        assertEquals(third, dialog.rect)
    }

    @Test fun movementBetweenRowsUsesViewportUnionThenRestoresTargetClip() {
        val state = CinemaFocusFrameState()
        val top = Rect(0f, 0f, 1000f, 300f)
        val bottom = Rect(0f, 350f, 1000f, 500f)
        val a = Any()
        val b = Any()
        state.focus(a)
        state.bounds(a, first, top)
        state.focus(b)
        state.bounds(b, second, bottom)
        assertEquals(Rect(0f, 0f, 1000f, 500f), state.viewport)
        state.advance(1L)
        state.advance(1L + duration)
        assertEquals(bottom, state.viewport)
    }

    @Test fun leavingButtonGroupClearsItAndAnotherGroupStartsAtItsOwnButton() {
        val rail = CinemaFilmFrameHost()
        val actions = CinemaFilmFrameHost()
        val railButton = Any()
        val actionButton = Any()
        rail.state.show(railButton)
        val departure = rail.state.blur(railButton)!!
        actions.state.show(actionButton, third)
        rail.state.clear(departure)
        assertFalse(rail.state.visible)
        assertEquals(third, actions.state.rect)
        assertFalse(actions.state.animating) // No journey across unrelated groups.
        val actionDeparture = actions.state.blur(actionButton)!!
        rail.state.show(railButton, second)
        actions.state.clear(actionDeparture)
        assertEquals(second, rail.state.rect)
        assertFalse(rail.state.animating)
        assertFalse(actions.state.visible)
    }

    @Test fun rapidButtonHandoffAndStaleDisposalNeverChangeFilmOrOtherGroup() {
        val films = CinemaFilmFrameHost()
        val rail = CinemaFilmFrameHost()
        val filters = CinemaFilmFrameHost()
        val sharedKey = Any() // Even identical external keys cannot join two hosts.
        films.state.show(sharedKey, first)
        filters.state.show(sharedKey, third)
        val filterTicket = filters.state.blur(sharedKey)!!
        filters.state.clear(filterTicket)
        rail.state.show(sharedKey, first)
        val ticket = rail.state.blur(sharedKey)!!
        val nextButton = Any()
        rail.state.show(nextButton, second)
        rail.state.advance(1L)
        rail.state.advance(1L + duration / 3)
        val rendered = rail.state.rect
        rail.state.show(Any(), third)
        rail.state.clear(ticket)
        assertEquals(rendered, rail.state.rect)
        assertTrue(rail.state.visible)
        assertEquals(first, films.state.rect)
        assertSame(sharedKey, films.state.owner)
        assertFalse(filters.state.visible)
    }

    @Test fun onlyCurrentTargetGeometryWakesTheSharedFrame() {
        val host = CinemaFilmFrameHost()
        val a = CinemaFilmFocusTarget(host)
        val b = CinemaFilmFocusTarget(host)
        a.invalidateGeometry()
        assertEquals(0L, host.revision)
        assertTrue(host.wake.tryReceive().isFailure)

        host.state.show(a)
        b.invalidateGeometry()
        assertEquals(0L, host.revision)
        a.invalidateGeometry()
        assertTrue(host.revision > 0L)
        assertTrue(host.wake.tryReceive().isSuccess)

        host.state.show(b, second)
        val before = host.revision
        a.invalidateGeometry()
        assertEquals(before, host.revision)
        assertTrue(host.wake.tryReceive().isFailure)
        b.invalidateGeometry()
        assertTrue(host.revision > before)
        assertTrue(host.wake.tryReceive().isSuccess)
    }

    @Test fun inactiveTargetDisposalCannotWakeOrClearTheCurrentTarget() {
        val host = CinemaFilmFrameHost()
        val previous = CinemaFilmFocusTarget(host)
        val current = CinemaFilmFocusTarget(host)
        host.state.show(previous)
        val staleClear = host.state.blur(previous)!!
        host.deferClear(staleClear)
        host.state.show(current, second)
        host.wake.tryReceive()
        val before = host.revision
        val rendered = host.state.rect

        previous.dispose()
        assertEquals(before, host.revision)
        assertTrue(host.wake.tryReceive().isFailure)
        assertEquals(staleClear, host.pendingClear)
        host.state.clear(staleClear)
        assertSame(current, host.state.owner)
        assertEquals(rendered, host.state.rect)
        assertTrue(host.state.visible)
    }

    @Test fun currentTargetDisposalSchedulesClearWithoutImmediatelyRemovingTheFrame() {
        val host = CinemaFilmFrameHost()
        val target = CinemaFilmFocusTarget(host)
        host.state.show(target)

        target.dispose()
        assertTrue(host.revision > 0L)
        assertTrue(host.wake.tryReceive().isSuccess)
        assertTrue(host.state.visible)
        assertEquals(first, host.state.rect)
        assertSame(target, host.state.owner)
        val ticket = host.pendingClear!!
        assertEquals(host.state.generation, ticket)
        host.state.clear(ticket)
        assertFalse(host.state.visible)
        assertNull(host.state.owner)
    }

    @Test fun alreadyBlurredOwnerDisposalKeepsItsPendingClearAndWake() {
        val host = CinemaFilmFrameHost()
        val target = CinemaFilmFocusTarget(host)
        host.state.show(target)
        val ticket = host.state.blur(target)!!
        host.deferClear(ticket)
        host.wake.tryReceive()
        val before = host.revision

        target.dispose()
        assertEquals(ticket, host.pendingClear)
        assertTrue(host.revision > before)
        assertTrue(host.wake.tryReceive().isSuccess)
        assertTrue(host.state.visible)
        host.state.clear(ticket)
        assertFalse(host.state.visible)
    }
}
