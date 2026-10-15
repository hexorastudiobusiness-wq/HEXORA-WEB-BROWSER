package com.example.browser.browser

import android.content.Context
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

    fun loadUrl(url: String)
    fun reload()
    fun stopLoading()
    fun goBack()
    fun goForward()

    /** Active sessions render/paint; inactive ones are paused by the engine. */
    fun setActive(active: Boolean)

    /**
     * Creates (once) and returns the native view rendering this session.
     * Typed as a plain [View] so no engine type leaks into the UI layer.
     */
    fun createContentView(context: Context): View

    /** Releases the session and its view. The session cannot be reused afterwards. */
    fun close()
}
