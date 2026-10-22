package com.example.browser.ui.browser

/** Fullscreen may only cover web content, never an empty tab or the zero-tab page. */
internal object BrowserPresentationPolicy {
    fun isFullscreen(
        hasActiveTab: Boolean,
        showStartPage: Boolean,
        userFullscreen: Boolean,
        pageFullscreen: Boolean,
    ): Boolean = hasActiveTab && !showStartPage && (userFullscreen || pageFullscreen)
}
