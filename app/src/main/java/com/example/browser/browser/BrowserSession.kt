package com.example.browser.browser

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Immutable snapshot of everything the UI needs to render one tab's page.
 * Produced by a [BrowserSession]; the UI never talks to the engine directly.
 */
data class SessionPageState(
    val url: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    /** 0f..1f */
    val progress: Float = 0f,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isSecure: Boolean = false,
    val loadFailed: Boolean = false,
    val isDesktopMode: Boolean = false,
    /** The page itself requested fullscreen (e.g. a video player). */
    val isPageFullscreen: Boolean = false,
    val findResult: FindResultUi = FindResultUi(),
)

/** Result of an in-page text search (Find in page). */
data class FindResultUi(
    val current: Int = 0,
    val total: Int = 0,
    val found: Boolean = false,
)

/**
 * Opaque saved state of a suspended tab (engine-serialized navigation
 * history, scroll position and form data). Kept in memory only.
 */
class SavedTabState internal constructor(internal val serialized: String)

/** One-time events coming out of a session (not part of persistent state). */
sealed interface BrowserSessionEvent {
    val sessionId: String

    /** The page asked for a file download (handled by the download layer). */
    data class DownloadRequested(
        override val sessionId: String,
        val url: String,
        val fileName: String,
        val mimeType: String?,
    ) : BrowserSessionEvent

    /** The page asked to close its own tab (window.close()). */
    data class CloseRequested(
        override val sessionId: String,
    ) : BrowserSessionEvent
}

/**
 * One browser tab's engine session.
 *
 * Every tab owns exactly one session, and sessions are never shared between
 * unrelated tabs. Implementations wrap the concrete engine (GeckoView);
 * UI/ViewModel code only ever sees this interface.
 */
interface BrowserSession {
    val id: String
    val isPrivate: Boolean
    val state: StateFlow<SessionPageState>
    val events: SharedFlow<BrowserSessionEvent>

    /** Latest saved state for suspending this tab, if the engine has one. */
    fun latestSavedState(): SavedTabState?

    /** Switches desktop/mobile rendering for this tab and reloads the page. */
    fun setDesktopMode(enabled: Boolean)

    /** Finds [query] in the page; [forward] = false searches backwards. */
    fun findText(query: String, forward: Boolean)

    /** Clears the current in-page search highlights. */
    fun clearFind()

    /** Captures a small snapshot of the visible page for tab previews. */
    fun captureThumbnail(onCaptured: (Bitmap?) -> Unit)

    fun loadUrl(url: String)
    fun reload()
    fun stopLoading()
    fun goBack()
    fun goForward()

    /** Active sessions render/paint; inactive ones are paused by the engine. */
    fun setActive(active: Boolean)

    /**
     * Creates a fresh native view rendering this session.
     *
     * A new view is intentionally returned for every display period: browser
     * engine surfaces must be detached when hidden and rebound when shown.
     * Reusing a detached surface-backed view can leave a tab black even
     * though its session and page are still alive. The caller must pass the
     * returned view to [releaseContentView] when it leaves the hierarchy.
     * Typed as a plain [View] so no engine type leaks into the UI layer.
     */
    fun createContentView(context: Context): View

    /** Detaches [view] from this session without closing the session. */
    fun releaseContentView(view: View)

    /** Releases the session and its view. The session cannot be reused afterwards. */
    fun close()
}
