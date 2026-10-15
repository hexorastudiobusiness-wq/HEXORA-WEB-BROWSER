package com.example.browser.browser

/**
 * Abstraction over the concrete web engine (Mozilla GeckoView).
 *
 * The UI and ViewModels depend only on this interface, so the engine can be
 * replaced or faked in tests. All GeckoView calls live behind this boundary.
 */
interface BrowserEngine {

    /**
     * Called when the engine itself created a new session on behalf of a page
     * (e.g. `window.open` / target=_blank). The returned session is already
     * loading the requested URL; the app layer only has to register a tab
     * for it and make it visible.
     */
    var newSessionCreatedListener: ((BrowserSession) -> Unit)?

    /** Creates a new, independent session. Not thread-confined, but cheap. */
    fun createSession(config: BrowserSessionConfig): BrowserSession

    /** Clears cookies, cache, site data etc. via the engine. Fire-and-forget. */
    fun clearBrowsingData()
}
