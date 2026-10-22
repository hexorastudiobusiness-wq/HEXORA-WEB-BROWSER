package com.example.browser.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserPresentationPolicyTest {
    @Test
    fun `empty and zero-tab pages reject every fullscreen combination`() {
        for (user in listOf(false, true)) {
            for (page in listOf(false, true)) {
                assertEquals(false, BrowserPresentationPolicy.isFullscreen(true, true, user, page))
                assertEquals(false, BrowserPresentationPolicy.isFullscreen(false, false, user, page))
                assertEquals(false, BrowserPresentationPolicy.isFullscreen(false, true, user, page))
            }
        }
    }

    @Test
    fun `web tabs retain user or page fullscreen`() {
        for (user in listOf(false, true)) {
            for (page in listOf(false, true)) {
                assertEquals(user || page, BrowserPresentationPolicy.isFullscreen(true, false, user, page))
            }
        }
    }
}
