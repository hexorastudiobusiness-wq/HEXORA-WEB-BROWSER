package com.example.browser.browser

import android.content.Context
import android.util.Log
import android.view.View
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse
import com.example.browser.download.DownloadFileName

/**
 * [BrowserEngine] implementation backed by Mozilla GeckoView.
 *
 * All GeckoView usage in the app is confined to this file (plus dependency
 * wiring). API surface verified against the GeckoView source of the pinned
 * release (see gradle/libs.versions.toml) — no invented APIs.
 *
 * A single [GeckoRuntime] is shared by every session and bound to the
 * process lifetime via the application context, as required by GeckoView.
 */
class GeckoBrowserEngine(
    context: Context,
    private val javaScriptEnabled: Boolean,
) : BrowserEngine {

    private val appContext = context.applicationContext

    override var newSessionCreatedListener: ((BrowserSession) -> Unit)? = null

    private var runtimeOrNull: GeckoRuntime? = null

    /** Lazily creates the process-wide runtime on first use (main thread). */
    internal val runtime: GeckoRuntime
        get() = runtimeOrNull ?: GeckoRuntime.create(
            appContext,
            GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(javaScriptEnabled)
                .build(),
        ).also { runtimeOrNull = it }

    override fun createSession(config: BrowserSessionConfig): BrowserSession =
        createSessionInternal(config).also { it.ensureOpen() }

    override fun clearBrowsingData() {
        // Only touch the runtime if it already exists; clearing data must
        // never boot the whole engine just to delete something.
        runtimeOrNull?.storageController?.clearData(StorageController.ClearFlags.ALL)
    }

    internal fun createSessionInternal(config: BrowserSessionConfig): GeckoBrowserSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(config.isPrivate)
            .useTrackingProtection(true)
            .userAgentMode(
                if (config.desktopMode) {
                    GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                },
            )
            .viewportMode(
                if (config.desktopMode) {
                    GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                },
            )
            .build()
        return GeckoBrowserSession(engine = this, geckoSession = GeckoSession(settings), config = config)
    }

    internal fun notifyNewSessionCreated(session: BrowserSession) {
        newSessionCreatedListener?.invoke(session)
    }
}

/**
 * [BrowserSession] wrapping exactly one [GeckoSession].
 * One session per tab, never shared (ARCHITECTURE.md §4).
 */
internal class GeckoBrowserSession(
    private val engine: GeckoBrowserEngine,
    internal val geckoSession: GeckoSession,
    private val config: BrowserSessionConfig,
) : BrowserSession {

    override val id: String = UUID.randomUUID().toString()
    override val isPrivate: Boolean = config.isPrivate

    private val _state = MutableStateFlow(SessionPageState())
    override val state: StateFlow<SessionPageState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BrowserSessionEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<BrowserSessionEvent> = _events.asSharedFlow()

    private var contentView: GeckoView? = null

    @Volatile
    private var closed = false

    /** Opens the session against the shared runtime if it is not open yet. */
    internal fun ensureOpen() {
        if (!closed && !geckoSession.isOpen) {
            geckoSession.open(engine.runtime)
        }
    }

    override fun loadUrl(url: String) {
        if (closed || url.isBlank()) return
        ensureOpen()
        geckoSession.loadUri(url)
    }

    override fun reload() {
        if (closed) return
        if (geckoSession.isOpen) {
            geckoSession.reload()
        } else {
            // Session was closed by a content-process crash: reopen and
            // navigate back to the last known URL.
            ensureOpen()
            val lastUrl = _state.value.url
            if (lastUrl.isNotBlank()) geckoSession.loadUri(lastUrl)
        }
    }

    override fun stopLoading() {
        if (!closed && geckoSession.isOpen) geckoSession.stop()
    }

    override fun goBack() {
        if (!closed && geckoSession.isOpen && _state.value.canGoBack) geckoSession.goBack()
    }

    override fun goForward() {
        if (!closed && geckoSession.isOpen && _state.value.canGoForward) geckoSession.goForward()
    }

    override fun setActive(active: Boolean) {
        if (!closed && geckoSession.isOpen) geckoSession.setActive(active)
    }

    override fun createContentView(context: Context): View {
        contentView?.let { return it }
        val view = GeckoView(context)
        view.setSession(geckoSession)
        contentView = view
        return view
    }

    override fun close() {
        if (closed) return
        closed = true
        geckoSession.setNavigationDelegate(null)
        geckoSession.setProgressDelegate(null)
        geckoSession.setContentDelegate(null)
        contentView?.releaseSession()
        contentView = null
        if (geckoSession.isOpen) geckoSession.close()
    }

    // ---- GeckoView delegates -------------------------------------------------

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            _state.update { it.copy(url = url.orEmpty()) }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            _state.update { it.copy(canGoBack = canGoBack) }
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            _state.update { it.copy(canGoForward = canGoForward) }
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // GeckoView requires the returned session to be *unopened* — it
            // opens and navigates it itself. We only create + register it.
            val sibling = engine.createSessionInternal(
                BrowserSessionConfig(isPrivate = config.isPrivate, desktopMode = config.desktopMode),
            )
            engine.notifyNewSessionCreated(sibling)
            return GeckoResult.fromValue(sibling.geckoSession)
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            _state.update {
                it.copy(url = url, isLoading = true, loadFailed = false, progress = 0f)
            }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            _state.update {
                it.copy(
                    isLoading = false,
                    progress = if (success) 1f else it.progress,
                    loadFailed = !success,
                )
            }
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            _state.update { it.copy(progress = progress / 100f) }
        }

        override fun onSecurityChange(
            session: GeckoSession,
            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
        ) {
            _state.update { it.copy(isSecure = securityInfo.isSecure) }
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            _state.update { it.copy(title = title.orEmpty()) }
        }

        override fun onCloseRequest(session: GeckoSession) {
            _events.tryEmit(BrowserSessionEvent.CloseRequested(sessionId = id))
        }

        override fun onCrash(session: GeckoSession) {
            _state.update { it.copy(isLoading = false, loadFailed = true) }
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            val fileName = DownloadFileName.fromContentDisposition(
                response.headers.headerValue("Content-Disposition"),
            ) ?: DownloadFileName.fromUrl(response.uri)
            val mimeType = response.headers.headerValue("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            _events.tryEmit(
                BrowserSessionEvent.DownloadRequested(
                    sessionId = id,
                    url = response.uri,
                    fileName = fileName,
                    mimeType = mimeType,
                ),
            )
            // The response body stream must always be closed, even when the
            // download is re-fetched by the download layer instead.
            try {
                response.body?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Failed to close download response stream", e)
            }
        }
    }

    init {
        geckoSession.setNavigationDelegate(navigationDelegate)
        geckoSession.setProgressDelegate(progressDelegate)
        geckoSession.setContentDelegate(contentDelegate)
    }

    private companion object {
        const val TAG = "GeckoBrowserSession"

        /** HTTP header names are case-insensitive. */
        fun Map<String, String>.headerValue(name: String): String? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}
