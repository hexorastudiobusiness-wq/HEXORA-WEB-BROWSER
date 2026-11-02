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

    /**
     * Called when the user performs a horizontal tab-swipe gesture on the
     * rendered web content (+1 = swiped left / next tab, -1 = swiped right /
     * previous tab). Only delivered while [tabSwipeEnabled] is true; the
     * gesture is detected at the native-view level because touch events over
     * the web surface never reach Compose.
     */
    var onTabSwipe: ((Int) -> Unit)?

    /**
     * Called when the user performs a pull-to-refresh gesture (dragging
     * down while the page is already at its top), in and out of fullscreen.
     * Detected by the engine from Gecko's own overscroll reporting, so it
     * never fires while the page can still scroll.
     */
    var onPullToRefresh: (() -> Unit)?

    /**
     * Enables content-level tab swiping (used in fullscreen, where the
     * browser chrome — and its own swipe area — is hidden).
     */
    var tabSwipeEnabled: Boolean

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

    /**
     * Applies global/per-session content controls without restarting the
     * engine: JavaScript is a runtime-wide switch and Enhanced Tracking
     * Protection is enabled per session by GeckoView.
     */
    fun applyContentControls(
        javaScriptEnabled: Boolean,
        trackingProtectionEnabled: Boolean,
        httpsOnlyMode: Boolean = false,
    ) {}

    /** Loads all stored site permissions for the permissions manager. */
    fun loadSitePermissions(onResult: (List<SitePermissionEntry>) -> Unit) {}

    /** Changes one stored site permission; ASK restores the prompt default. */
    fun setSitePermission(
        uri: String,
        kind: SitePermissionKind,
        decision: SitePermissionDecision,
        isPrivate: Boolean = false,
        permissionType: Int = -1,
    ) {}

    /** Clears cookies/cache/storage belonging to one [host] only. */
    fun clearSiteData(host: String)

    /** Clears cookies, cache, site data etc. via the engine. Fire-and-forget. */
    fun clearBrowsingData()

    /**
     * Clears only web caches (network/image), leaving history, bookmarks,
     * cookies, site storage and saved logins untouched.
     */
    fun clearCache(onComplete: () -> Unit = {})
}

/** Color-scheme preference passed from the app theme to web content. */
enum class WebColorScheme {
    SYSTEM,
    LIGHT,
    DARK,
}
