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
    /** Query this result belongs to; empty means no completed search. */
    val query: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val found: Boolean = false,
    /** True once the engine has answered for [query]. */
    val completed: Boolean = false,
)

/**
 * Opaque saved state of a suspended tab (engine-serialized navigation
 * history, scroll position and form data). Kept in memory only.
 */
class SavedTabState internal constructor(internal val serialized: String)

/** A locally saved login offered to a page by the in-app password vault. */
data class AutofillCredential(
    val username: String,
    val password: String,
)

/** One-time events coming out of a session (not part of persistent state). */
sealed interface BrowserSessionEvent {
    val sessionId: String

    /** The page asked for a file download (handled by the download layer). */
    data class DownloadRequested(
        override val sessionId: String,
        val url: String,
        val fileName: String,
        val mimeType: String?,
        val isPrivate: Boolean = false,
    ) : BrowserSessionEvent

    /** A login form was submitted; the app may ask whether to save it. */
    data class CredentialSubmitted(
        override val sessionId: String,
        val pageUrl: String,
        val username: String,
        val password: String,
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
     * Returns the browser's single shared native content view and binds this
     * session to it. GeckoView tab switching is designed around one attached
     * GeckoView whose session is replaced; creating a separate surface-backed
     * view per tab can leave previously shown tabs white/black. Typed as a
     * plain [View] so no engine type leaks into the UI layer.
     */
    fun createContentView(context: Context): View

    /** Binds this session to the already-created shared content [view]. */
    fun renderContentView(view: View)

    /** Detaches [view] from this session without closing the session. */
    fun releaseContentView(view: View)

    /** Supplies (or clears) the locally saved login to offer on this page. */
    fun setAutofillCredential(credential: AutofillCredential?)

    /** Releases the session and its view. The session cannot be reused afterwards. */
    fun close()
}
