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
     * Pull-to-refresh progress (0f..1.5f) for the in-app indicator. The engine
     * reports it from Gecko's own touch result, so the value is honest: it only
     * grows while the page is at its top and cannot scroll further.
     */
    var onPullProgress: ((Float) -> Unit)?

    /**
     * Enables content-level tab swiping (used in fullscreen, where the
     * browser chrome — and its own swipe area — is hidden).
     */
    var tabSwipeEnabled: Boolean

    /**
     * Called every time the app returns to the foreground, after the window has
     * a surface again. The engine checks that Gecko actually painted the page
     * and, if it did not, re-acquires the display without reloading anything.
     * This is the deterministic replacement for timer-based recovery.
     */
    fun ensureContentDisplayed() {}

    /** Remembers the paint state at the moment the app went to the background. */
    fun noteAppBackgrounded() {}

    /**
     * Called shortly after the app comes back to the foreground. If Gecko
     * reported that the painted content was dropped (a paint-status reset)
     * while the app was away and no frame has arrived since, the active tab's
     * display is re-acquired — the same repair as re-selecting the tab, which
     * is what the owner had to do by hand when tabs came back black.
     */
    fun verifyPaintAfterResume() {}

    /**
     * The window changed size: rotation, split screen, or a frozen-app restore
     * that hands the app back at a different geometry. Everything that owns a
     * surface has to take the new bounds; the view tree alone does not, because
     * the Gecko surfaces keep the rectangle they were last measured with.
     */
    fun onWindowGeometryChanged() {}

    /**
     * Same verification for an explicit (re)load — Retry, the toolbar refresh,
     * pull-to-refresh. If the surface produces no frame at all shortly after a
     * load was asked for, it is re-acquired from the live session (a repaint,
     * never a reload).
     */
    fun verifyDisplayAfterReload() {}

    /**
     * Enables or disables touch delivery to the web surfaces.
     *
     * While an overlay (Start Page, crash card, load-error card) covers the
     * content, the surface must not take the gesture: GeckoView is a real View
     * and consumes the MotionEvent before the Compose UI drawn on top of it,
     * so every button over the content area was dead. Engine-level on purpose —
     * it must not depend on the active tab having a session (an empty Start
     * Page tab has none, which is why a session-scoped call silently did
     * nothing).
     */
    fun setContentTouchAllowed(allowed: Boolean) {}

    /**
     * Hides or shows the whole content container.
     *
     * Refusing touches is not enough while an overlay is on top: Compose picks
     * the interop view as the hit target for the area it occupies, so nothing
     * drawn over it receives the tap. An INVISIBLE container is skipped by the
     * view hierarchy entirely. The view stays attached (the session is kept);
     * only its surface is released, which the engine already recovers from.
     */
    fun setContentHidden(hidden: Boolean) {}

    /**
     * One-line counters describing what the content surface has been doing
     * (binds, paint resets, display re-acquisitions). Shown in the in-app
     * diagnostics dialog so a bug report does not need a logcat cable.
     */
    fun diagnostics(): String = ""

    /**
     * Ends the pull-to-refresh indicator. The tab layer calls this once the
     * load it triggered has finished (or its session went away).
     */
    fun finishPullToRefresh() {}

    /** Creates a new, independent session. Not thread-confined, but cheap. */
    fun createSession(config: BrowserSessionConfig): BrowserSession

    /**
     * Resolver for per-site zoom: the engine asks for a site's stored percent
     * on every page start, so the factor always belongs to the page that is
     * being loaded (null = nothing stored, use 100%).
     */
    var siteZoomProvider: ((String) -> Int?)?

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
