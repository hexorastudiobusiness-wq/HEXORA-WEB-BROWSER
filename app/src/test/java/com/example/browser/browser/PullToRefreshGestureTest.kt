package com.example.browser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullToRefreshGestureTest {
    private fun gesture() = PullToRefreshGesture(100f, 8f).apply { begin(0f, 0f) }

    @Test fun `middle of page never arms even after reaching the top`() {
        val g = gesture()
        g.confirm(false)
        g.move(0f, 400f)
        g.confirm(true)
        assertFalse(g.shouldRefresh)
    }

    @Test fun `unconfirmed motion cannot refresh`() {
        val g = gesture()
        g.move(0f, 400f)
        assertFalse(g.shouldRefresh)
    }

    @Test fun `top confirmed downward pull crosses threshold`() {
        val g = gesture()
        g.confirm(true)
        g.move(0f, 120f)
        assertTrue(g.shouldRefresh)
    }

    @Test fun `upward scroll cannot become refresh on reversal`() {
        val g = gesture()
        g.confirm(true)
        g.move(0f, -40f)
        g.move(0f, 400f)
        assertFalse(g.shouldRefresh)
    }

    @Test fun `horizontal gesture cannot become refresh`() {
        val g = gesture()
        g.confirm(true)
        g.move(80f, 10f)
        g.move(80f, 400f)
        assertFalse(g.shouldRefresh)
    }

    @Test fun `late APZ veto cancels an otherwise armed pull`() {
        val g = gesture()
        g.confirm(true)
        g.move(0f, 400f)
        g.confirm(false)
        assertFalse(g.shouldRefresh)
    }

    @Test fun `short pull and pull reversed below threshold do not refresh`() {
        val g = gesture()
        g.confirm(true)
        g.move(0f, 60f)
        assertFalse(g.shouldRefresh)
        g.move(0f, 200f)
        g.move(0f, 60f)
        assertFalse(g.shouldRefresh)
    }

    @Test fun `cancel cannot be undone by a late positive detail`() {
        val g = gesture()
        g.move(0f, 400f)
        g.cancel()
        g.confirm(true)
        assertFalse(g.shouldRefresh)
        g.begin(0f, 0f)
        g.confirm(true)
        g.move(0f, 200f)
        assertTrue(g.shouldRefresh)
    }
    @Test fun `missing root scroll callback delegates to APZ instead of disabling refresh`() {
        val g = gesture()
        g.begin(0f, 0f, rootScrollY = null)
        g.move(0f, 200f)
        assertFalse(g.shouldRefresh)
        g.confirm(true)
        assertTrue(g.shouldRefresh)
    }

    @Test fun `known root offset vetoes even if a nested container reports top`() {
        val g = gesture()
        g.begin(0f, 0f, rootScrollY = 400)
        g.confirm(true)
        g.move(0f, 200f)
        assertFalse(g.shouldRefresh)
    }

}
