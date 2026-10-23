package com.example.browser.browser

import kotlin.math.abs

/** Fail-closed, one-finger downward pull. An APZ veto lasts until the next DOWN. */
internal class PullToRefreshGesture(private val threshold: Float, private val slop: Float) {
    private var startX = 0f
    private var startY = 0f
    private var distance = 0f
    private var allowed = false
    private var confirmed = false

    fun begin(x: Float, y: Float, rootScrollY: Int? = null) {
        startX = x
        startY = y
        distance = 0f
        // Unknown root offset relies on the mandatory APZ confirmation.
        allowed = rootScrollY == null || rootScrollY <= 0
        confirmed = false
    }

    fun confirm(canOverscrollTop: Boolean) {
        confirmed = true
        if (!canOverscrollTop) cancel()
    }

    fun move(x: Float, y: Float) {
        if (!allowed) return
        val dx = abs(x - startX)
        val dy = y - startY
        // Scrolling up or beginning horizontally cannot turn into refresh
        // later in the same gesture, even if the scroll reaches an edge.
        if (dy < -slop || (dx > slop && dx > abs(dy))) {
            cancel()
            return
        }
        distance = (dy - slop).coerceAtLeast(0f)
    }

    val progress: Float
        get() = if (allowed && confirmed) (distance / threshold).coerceIn(0f, 1f) else 0f

    val shouldRefresh: Boolean
        get() = allowed && confirmed && distance >= threshold

    fun cancel() {
        allowed = false
        distance = 0f
    }
}
