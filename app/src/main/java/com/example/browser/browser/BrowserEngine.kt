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
     * for it and make it visible. The second value is the opener session id,
     * when known, so Back can return to the tab that opened this one.
     */
    var newSessionCreatedListener: ((BrowserSession, String?) -> Unit)?

    /** Creates a new, independent session. Not thread-confined, but cheap. */
    fun createSession(config: BrowserSessionConfig): BrowserSession

    /** Preferred color scheme exposed to web content (`prefers-color-scheme`). */
    fun setPreferredColorScheme(scheme: WebColorScheme)

    /** Recreates a suspended tab's session from its [SavedTabState]. */
    fun restoreSession(config: BrowserSessionConfig, savedState: SavedTabState): BrowserSession

    /**
     * Sets the global text zoom (50..200 percent). Implemented with the
     * engine's supported text-scaling setting; pages pick it up on reload.
     */
    fun setTextZoomPercent(percent: Int)

    /** Clears cookies/cache/storage belonging to one [host] only. */
    fun clearSiteData(host: String)

    /** Clears cookies, cache, site data etc. via the engine. Fire-and-forget. */
    fun clearBrowsingData()
}

/** Color-scheme preference passed from the app theme to web content. */
enum class WebColorScheme {
    SYSTEM,
    LIGHT,
    DARK,
}
