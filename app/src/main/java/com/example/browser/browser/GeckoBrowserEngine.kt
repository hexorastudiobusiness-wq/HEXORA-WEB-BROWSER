package com.example.browser.browser

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.geckoview.Autofill
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

    internal val promptContext: Context
        get() = appContext

    override var newSessionCreatedListener: ((BrowserSession, String?) -> Unit)? = null

    @Volatile
    private var textZoomPercent: Int = 100

    @Volatile
    private var preferredColorScheme: Int = GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM

    /**
     * The Gecko runtime is a process-wide singleton: GeckoView throws if a
     * second one is created in the same process. It must therefore outlive
     * activities, ViewModels and engine instances (for example when the app
     * is closed with finishAndRemoveTask() but Android keeps the process).
     */
    internal val runtime: GeckoRuntime
        get() = synchronized(RUNTIME_LOCK) {
            processRuntime ?: GeckoRuntime.create(
                appContext,
                GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(javaScriptEnabled)
                    // Text zoom is user-controlled; font inflation must not
                    // fight it (setFontSizeFactor throws while enabled).
                    .automaticFontSizeAdjustment(false)
                    .fontSizeFactor(textZoomPercent / 100f)
                    .preferredColorScheme(preferredColorScheme)
                    .build(),
            ).also { processRuntime = it }
        }

    override fun createSession(config: BrowserSessionConfig): BrowserSession =
        createSessionInternal(config).also { it.ensureOpen() }

    override fun restoreSession(
        config: BrowserSessionConfig,
        savedState: SavedTabState,
    ): BrowserSession =
        createSessionInternal(config, pendingRestore = savedState).also { it.ensureOpen() }

    override fun setTextZoomPercent(percent: Int) {
        textZoomPercent = percent.coerceIn(50, 200)
        // Takes effect on each session's next reload/navigation.
        processRuntime?.settings?.let { settings ->
            settings.setAutomaticFontSizeAdjustment(false)
            settings.setFontSizeFactor(textZoomPercent / 100f)
        }
    }

    override fun setPreferredColorScheme(scheme: WebColorScheme) {
        val geckoScheme = when (scheme) {
            WebColorScheme.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
            WebColorScheme.LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
            WebColorScheme.DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        }
        if (preferredColorScheme == geckoScheme) return
        preferredColorScheme = geckoScheme
        // Gecko updates `prefers-color-scheme` for pages that provide a dark
        // theme. It does not algorithmically recolor sites without one.
        processRuntime?.settings?.setPreferredColorScheme(geckoScheme)
    }

    override fun clearSiteData(host: String) {
        if (host.isBlank()) return
        processRuntime?.storageController
            ?.clearDataFromHost(host, StorageController.ClearFlags.ALL)
    }

    override fun clearBrowsingData() {
        // Only touch the runtime if it already exists; clearing data must
        // never boot the whole engine just to delete something.
        processRuntime?.storageController?.clearData(StorageController.ClearFlags.ALL)
    }

    override fun clearCache(onComplete: () -> Unit) {
        val controller = processRuntime?.storageController
        if (controller == null) {
            onComplete()
            return
        }
        controller.clearData(StorageController.ClearFlags.ALL_CACHES).accept(
            { onComplete() },
            { onComplete() },
        )
    }

    // GeckoView is designed to paint sessions through one attached view:
    // tabs are switched by replacing the session on that view (the model
    // used by Mozilla's own Android Components). A separate GeckoView per
    // tab leaves detached SurfaceViews behind and previously shown tabs can
    // return white/black even though their GeckoSession is still alive.
    private var sharedContentView: HexoraGeckoView? = null
    private var sharedContentHost: FrameLayout? = null
    private var renderedSession: GeckoBrowserSession? = null
    /** Session to re-attach automatically when the shared view returns. */
    private var sessionToRestoreOnAttach: GeckoBrowserSession? = null

    internal fun obtainContentView(
        session: GeckoBrowserSession,
        context: Context,
    ): View {
        val existing = sharedContentView
        val geckoView = if (existing == null || existing.context !== context) {
            existing?.let { oldView ->
                oldView.onViewDetached = null
                oldView.onViewAttached = null
                oldView.releaseSession()
                (oldView.parent as? ViewGroup)?.removeView(oldView)
            }
            renderedSession?.contentView = null
            renderedSession = null
            sessionToRestoreOnAttach = null
            HexoraGeckoView(context).also { newView ->
                newView.onViewDetached = { handleSharedViewDetached(newView) }
                newView.onViewAttached = { handleSharedViewAttached(newView) }
                sharedContentView = newView
            }
        } else {
            existing
        }

        // AndroidView receives a fresh lightweight host. The real GeckoView
        // is moved into that host by the engine, so a Compose branch change
        // (for example entering fullscreen) can never try to add a GeckoView
        // that still has another parent.
        val host = FrameLayout(context)
        sharedContentHost = host
        attachToHost(geckoView, host)
        bindContentView(session, host)
        return host
    }

    internal fun bindContentView(session: GeckoBrowserSession, view: View) {
        val geckoView = sharedContentView ?: return
        val host = sharedContentHost ?: return
        if (view !== host) return
        attachToHost(geckoView, host)
        if (renderedSession !== session) {
            renderedSession?.contentView = null
        }
        if (geckoView.session !== session.geckoSession) {
            // Hexora provides its own offline credential vault through the
            // session Autofill.Delegate. Disable GeckoView's Android
            // Autofill bridge so it does not replace that delegate.
            geckoView.setAutofillEnabled(false)
            // Mozilla's GeckoEngineView covers the not-yet-painted surface
            // with the app's background color so a tab switch or return can
            // never flash GeckoView's default black surface.
            geckoView.coverUntilFirstPaint(coverColor())
            if (geckoView.session != null) geckoView.releaseSession()
            // setSession() throws for a closed session; reopen defensively
            // (e.g. after a content-process crash) instead of crashing.
            if (!session.geckoSession.isOpen) session.ensureOpen()
            geckoView.setSession(session.geckoSession)
            session.installAutofillDelegate()
        }
        renderedSession = session
        sessionToRestoreOnAttach = session
        session.contentView = geckoView
    }

    /**
     * Called by [HexoraGeckoView] *before* GeckoView's own detach handling.
     * The subclass releases the session itself (otherwise GeckoView closes
     * it); here we only forget the stale view binding and remember which
     * session must be rendered again when the view comes back. The session
     * itself stays open and loaded: no reload, no state loss.
     */
    internal fun handleSharedViewDetached(view: HexoraGeckoView) {
        if (view !== sharedContentView) return
        if (renderedSession != null) {
            sessionToRestoreOnAttach = renderedSession
        }
        renderedSession?.contentView = null
        renderedSession = null
    }

    internal fun handleSharedViewAttached(view: HexoraGeckoView) {
        if (view !== sharedContentView) return
        val host = sharedContentHost ?: return
        if (view.parent !== host) return
        val session = sessionToRestoreOnAttach ?: return
        if (view.session === session.geckoSession) return
        bindContentView(session, host)
    }

    internal fun releaseContentView(session: GeckoBrowserSession, view: View) {
        val geckoView = sharedContentView ?: return
        // The UI passes the host; session.close() passes the GeckoView.
        if (view !== sharedContentHost && view !== geckoView) return
        // Release BEFORE removing the view: removing a plain GeckoView with
        // a session still attached lets its detach handling close that
        // session. (Our subclass also guards this, but the explicit order
        // keeps the lifecycle readable.)
        if (geckoView.session === session.geckoSession) {
            geckoView.releaseSession()
        }
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
        sharedContentHost = null
        if (renderedSession === session) renderedSession = null
        if (sessionToRestoreOnAttach === session) sessionToRestoreOnAttach = null
        if (session.contentView === geckoView) session.contentView = null
    }

    /** Background colour GeckoView paints until the page's first frame. */
    private fun coverColor(): Int {
        val night = when (preferredColorScheme) {
            GeckoRuntimeSettings.COLOR_SCHEME_DARK -> true
            GeckoRuntimeSettings.COLOR_SCHEME_LIGHT -> false
            else ->
                (appContext.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        return if (night) COVER_DARK else Color.WHITE
    }

    private fun attachToHost(geckoView: GeckoView, host: FrameLayout) {
        if (geckoView.parent === host) return
        (geckoView.parent as? ViewGroup)?.removeView(geckoView)
        host.removeAllViews()
        host.addView(
            geckoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    internal fun createSessionInternal(
        config: BrowserSessionConfig,
        pendingRestore: SavedTabState? = null,
    ): GeckoBrowserSession {
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
        return GeckoBrowserSession(
            engine = this,
            geckoSession = GeckoSession(settings),
            config = config,
            pendingRestore = pendingRestore,
        )
    }

    internal fun notifyNewSessionCreated(session: BrowserSession, openerSessionId: String?) {
        newSessionCreatedListener?.invoke(session, openerSessionId)
    }

    private companion object {
        private val RUNTIME_LOCK = Any()

        @Volatile
        private var processRuntime: GeckoRuntime? = null

        /** Matches the app's dark background so resume never flashes black. */
        private val COVER_DARK: Int = 0xFF100B0F.toInt()
    }
}

/**
 * The one shared content view.
 *
 * A plain [GeckoView] **closes its session** in `onDetachedFromWindow()`
 * when any state was not explicitly saved. That is exactly what used to
 * destroy pages on tab switches, navigation to the tab switcher and
 * minimise/return: the GeckoSession died with the detach, and the next
 * render had to reopen and reload it. Mozilla's own Android Components
 * (`GeckoEngineView`) avoids this by releasing the session *before*
 * GeckoView's detach handling runs; this subclass does the same thing for
 * Hexora's single shared view. The engine re-attaches the same, still-open
 * session when the view returns, so returning is a repaint, not a reload.
 */
internal class HexoraGeckoView(context: Context) : GeckoView(context) {

    var onViewAttached: ((HexoraGeckoView) -> Unit)? = null
    var onViewDetached: ((HexoraGeckoView) -> Unit)? = null

    override fun onAttachedToWindow() {
        // GeckoView would create and open a brand-new session here when it
        // has none. Suppress that; the engine decides which tab renders.
        val hadSession = session != null
        super.onAttachedToWindow()
        if (!hadSession) {
            // That fresh session is not ours and never loaded anything:
            // drop it so the engine can attach the tab's real session.
            val fresh = session
            if (fresh != null) {
                releaseSession()
                if (fresh.isOpen) fresh.close()
            }
        }
        onViewAttached?.invoke(this)
    }

    override fun onDetachedFromWindow() {
        // Release first: prevents GeckoView from closing the session.
        if (session != null) releaseSession()
        onViewDetached?.invoke(this)
        super.onDetachedFromWindow()
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
    private var pendingRestore: SavedTabState? = null,
) : BrowserSession {

    override val id: String = UUID.randomUUID().toString()
    override val isPrivate: Boolean = config.isPrivate

    private val _state = MutableStateFlow(
        SessionPageState(isDesktopMode = config.desktopMode),
    )

    @Volatile
    private var latestStateString: String? = null

    @Volatile
    private var latestFindQuery: String = ""
    override val state: StateFlow<SessionPageState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BrowserSessionEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<BrowserSessionEvent> = _events.asSharedFlow()

    internal var contentView: GeckoView? = null

    @Volatile
    private var closed = false

    @Volatile
    private var priorityHigh: Boolean = false

    /** Opens the session against the shared runtime if it is not open yet. */
    internal fun ensureOpen() {
        if (!closed && !geckoSession.isOpen) {
            geckoSession.open(engine.runtime)
            geckoSession.setPriorityHint(
                if (priorityHigh) {
                    GeckoSession.PRIORITY_HIGH
                } else {
                    GeckoSession.PRIORITY_DEFAULT
                },
            )
            pendingRestore?.let { saved ->
                pendingRestore = null
                GeckoSession.SessionState.fromString(saved.serialized)
                    ?.let { restored -> geckoSession.restoreState(restored) }
            }
        }
    }

    override fun latestSavedState(): SavedTabState? =
        latestStateString?.let { SavedTabState(it) }

    override fun setDesktopMode(enabled: Boolean) {
        if (closed || _state.value.isDesktopMode == enabled) return
        _state.update { it.copy(isDesktopMode = enabled) }
        if (geckoSession.isOpen) {
            geckoSession.settings.setUserAgentMode(
                if (enabled) {
                    GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                },
            )
            geckoSession.settings.setViewportMode(
                if (enabled) {
                    GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                },
            )
            geckoSession.reload()
        }
    }

    override fun findText(query: String, forward: Boolean) {
        if (closed || !geckoSession.isOpen || query.isBlank()) return
        latestFindQuery = query
        val flags = if (forward) {
            GeckoSession.FINDER_FIND_FORWARD
        } else {
            GeckoSession.FINDER_FIND_BACKWARDS
        }
        geckoSession.finder.find(query, flags).accept(
            { result ->
                // Ignore a late answer for text the user has already edited;
                // otherwise an older query could briefly claim "no results".
                if (result != null && !closed && result.searchString == latestFindQuery) {
                    _state.update {
                        it.copy(
                            findResult = FindResultUi(
                                query = result.searchString,
                                current = result.current,
                                total = result.total.coerceAtLeast(0),
                                found = result.found,
                                completed = true,
                            ),
                        )
                    }
                }
            },
            {
                if (!closed && latestFindQuery == query) {
                    _state.update {
                        it.copy(
                            findResult = FindResultUi(
                                query = query,
                                completed = true,
                            ),
                        )
                    }
                }
            },
        )
    }

    override fun clearFind() {
        latestFindQuery = ""
        if (!closed && geckoSession.isOpen) geckoSession.finder.clear()
        _state.update { it.copy(findResult = FindResultUi()) }
    }

    override fun captureThumbnail(onCaptured: (Bitmap?) -> Unit) {
        val view = contentView
        if (closed || view == null) {
            onCaptured(null)
            return
        }
        view.capturePixels().accept(
            { bitmap ->
                // Tab-switcher thumbnails never need the full surface. Scaling
                // here avoids retaining and JPEG-encoding a huge bitmap on
                // every page load.
                if (bitmap == null || bitmap.width <= MAX_THUMBNAIL_WIDTH) {
                    onCaptured(bitmap)
                } else {
                    val scale = MAX_THUMBNAIL_WIDTH.toFloat() / bitmap.width
                    val scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        MAX_THUMBNAIL_WIDTH,
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                    if (scaled !== bitmap) bitmap.recycle()
                    onCaptured(scaled)
                }
            },
            { onCaptured(null) },
        )
    }

    override fun exitFullscreen() {
        if (!closed && geckoSession.isOpen) geckoSession.exitFullScreen()
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

    override fun setPriorityHigh(high: Boolean) {
        priorityHigh = high
        if (!closed && geckoSession.isOpen) {
            geckoSession.setPriorityHint(
                if (high) {
                    GeckoSession.PRIORITY_HIGH
                } else {
                    GeckoSession.PRIORITY_DEFAULT
                },
            )
        }
    }

    override fun createContentView(context: Context): View =
        engine.obtainContentView(this, context)

    override fun renderContentView(view: View) {
        engine.bindContentView(this, view)
    }


    override fun releaseContentView(view: View) {
        engine.releaseContentView(this, view)
    }

    override fun setAutofillCredential(credential: AutofillCredential?) {
        autofillCredential = credential
    }

    internal fun installAutofillDelegate() {
        if (!closed) geckoSession.setAutofillDelegate(autofillDelegate)
    }

    override fun close() {
        if (closed) return
        closed = true
        autofillCredential = null
        cancelAllFilePickers()
        geckoSession.setPromptDelegate(null)
        geckoSession.setNavigationDelegate(null)
        geckoSession.setProgressDelegate(null)
        geckoSession.setContentDelegate(null)
        geckoSession.setAutofillDelegate(null)
        contentView?.let { releaseContentView(it) }
        if (geckoSession.isOpen) geckoSession.close()
    }

    @Volatile
    private var autofillCredential: AutofillCredential? = null

    private data class PendingFilePrompt(
        val prompt: GeckoSession.PromptDelegate.FilePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
    )

    private val pendingFilePrompts = mutableMapOf<String, PendingFilePrompt>()

    private val promptDelegate = object : GeckoSession.PromptDelegate {
        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            if (closed) return GeckoResult.fromValue(prompt.dismiss())
            val requestId = UUID.randomUUID().toString()
            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            pendingFilePrompts[requestId] = PendingFilePrompt(prompt, result)
            val emitted = _events.tryEmit(
                BrowserSessionEvent.FilePickerRequested(
                    sessionId = id,
                    requestId = requestId,
                    allowMultiple =
                        prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
                    isFolder =
                        prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER,
                    mimeTypes = prompt.mimeTypes?.filterNotNull()?.take(16) ?: emptyList(),
                ),
            )
            if (!emitted) {
                pendingFilePrompts.remove(requestId)
                return GeckoResult.fromValue(prompt.dismiss())
            }
            return result
        }
    }

    override fun completeFilePicker(requestId: String, uris: List<android.net.Uri>) {
        val pending = pendingFilePrompts.remove(requestId) ?: return
        if (closed || uris.isEmpty()) {
            pending.result.complete(pending.prompt.dismiss())
            return
        }
        val response = if (pending.prompt.type ==
            GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE
        ) {
            pending.prompt.confirm(engine.promptContext, uris.toTypedArray())
        } else {
            pending.prompt.confirm(engine.promptContext, uris.first())
        }
        pending.result.complete(response)
    }

    override fun cancelFilePicker(requestId: String) {
        val pending = pendingFilePrompts.remove(requestId) ?: return
        pending.result.complete(pending.prompt.dismiss())
    }

    private fun cancelAllFilePickers() {
        val pending = pendingFilePrompts.values.toList()
        pendingFilePrompts.clear()
        pending.forEach { it.result.complete(it.prompt.dismiss()) }
    }

    private val autofillDelegate = object : Autofill.Delegate {
        override fun onSessionCommit(
            session: GeckoSession,
            node: Autofill.Node,
            data: Autofill.NodeData,
        ) {
            captureSubmittedCredential(session)
        }

        override fun onNodeFocus(
            session: GeckoSession,
            focused: Autofill.Node,
            data: Autofill.NodeData,
        ) {
            fillSavedCredential(session, focused)
        }
    }

    private fun nodeMatchesCurrentPage(node: Autofill.Node): Boolean {
        val pageHost = runCatching {
            android.net.Uri.parse(_state.value.url).host
        }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return false
        val nodeDomain = node.domain.trim().lowercase()
        if (nodeDomain.isBlank()) return false
        val nodeHost = runCatching {
            android.net.Uri.parse(
                if ("://" in nodeDomain) nodeDomain else "https://$nodeDomain",
            ).host
        }.getOrNull()?.lowercase()?.removePrefix("www.") ?: nodeDomain.removePrefix("www.")
        return nodeHost == pageHost
    }

    private fun captureSubmittedCredential(session: GeckoSession) {
        if (closed || config.isPrivate) return
        val autofillSession = session.getAutofillSession()
        val nodes = mutableListOf<Autofill.Node>()
        fun visit(node: Autofill.Node) {
            nodes += node
            node.children.forEach(::visit)
        }
        visit(autofillSession.root)

        fun valueOf(node: Autofill.Node, trim: Boolean): String? =
            runCatching { autofillSession.dataFor(node).value }
                .getOrNull()
                ?.let { if (trim) it.trim() else it }
                ?.takeIf { it.isNotEmpty() }

        val username = nodes.firstOrNull { node ->
            nodeMatchesCurrentPage(node) &&
                (node.hint == Autofill.Hint.USERNAME ||
                    node.hint == Autofill.Hint.EMAIL_ADDRESS) &&
                valueOf(node, trim = true) != null
        }?.let { valueOf(it, trim = true) }
        val password = nodes.firstOrNull { node ->
            nodeMatchesCurrentPage(node) &&
                node.hint == Autofill.Hint.PASSWORD &&
                valueOf(node, trim = false) != null
        }?.let { valueOf(it, trim = false) }

        if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
            _events.tryEmit(
                BrowserSessionEvent.CredentialSubmitted(
                    sessionId = id,
                    pageUrl = _state.value.url,
                    username = username,
                    password = password,
                ),
            )
        }
    }

    private fun fillSavedCredential(session: GeckoSession, focused: Autofill.Node) {
        val credential = autofillCredential ?: return
        if (closed || config.isPrivate) return
        if (focused.hint != Autofill.Hint.USERNAME &&
            focused.hint != Autofill.Hint.EMAIL_ADDRESS &&
            focused.hint != Autofill.Hint.PASSWORD
        ) {
            return
        }
        val autofillSession = session.getAutofillSession()
        val nodes = mutableListOf<Autofill.Node>()
        fun visit(node: Autofill.Node) {
            nodes += node
            node.children.forEach(::visit)
        }
        visit(autofillSession.root)

        val values = SparseArray<CharSequence>()
        nodes.firstOrNull { node ->
            nodeMatchesCurrentPage(node) &&
                (node.hint == Autofill.Hint.USERNAME ||
                    node.hint == Autofill.Hint.EMAIL_ADDRESS)
        }?.let { node ->
            runCatching { autofillSession.dataFor(node).id }
                .getOrNull()
                ?.let { values.put(it, credential.username) }
        }
        nodes.firstOrNull { node ->
            nodeMatchesCurrentPage(node) && node.hint == Autofill.Hint.PASSWORD
        }?.let { node ->
                runCatching { autofillSession.dataFor(node).id }
                    .getOrNull()
                    ?.let { values.put(it, credential.password) }
            }
        if (values.size() > 0) autofillSession.autofill(values)
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
            engine.notifyNewSessionCreated(sibling, id)
            return GeckoResult.fromValue(sibling.geckoSession)
        }
    }

    init {
        runCatching {
            geckoSession.finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onSessionStateChange(
            session: GeckoSession,
            sessionState: GeckoSession.SessionState,
        ) {
            // Continuously kept so the tab can be suspended at any moment.
            latestStateString = sessionState.toString()
        }

        override fun onPageStart(session: GeckoSession, url: String) {
            // A new document invalidates any previous Find result; the UI
            // re-issues the visible query once loading finishes.
            latestFindQuery = ""
            _state.update {
                it.copy(
                    url = url,
                    isLoading = true,
                    loadFailed = false,
                    progress = 0f,
                    findResult = FindResultUi(),
                )
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

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            _state.update { it.copy(isPageFullscreen = fullScreen) }
        }

        override fun onContextMenu(
            session: GeckoSession,
            screenX: Int,
            screenY: Int,
            element: GeckoSession.ContentDelegate.ContextElement,
        ) {
            val mediaKind = when (element.type) {
                GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE ->
                    PageMediaKind.IMAGE
                GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO ->
                    PageMediaKind.VIDEO
                GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO ->
                    PageMediaKind.AUDIO
                else -> PageMediaKind.NONE
            }
            _events.tryEmit(
                BrowserSessionEvent.ContextMenuRequested(
                    sessionId = id,
                    linkUri = element.linkUri?.takeIf { it.isNotBlank() },
                    mediaUri = element.srcUri?.takeIf { it.isNotBlank() },
                    mediaKind = mediaKind,
                    label = element.title?.takeIf { it.isNotBlank() }
                        ?: element.altText?.takeIf { it.isNotBlank() },
                ),
            )
        }

        override fun onCloseRequest(session: GeckoSession) {
            _events.tryEmit(BrowserSessionEvent.CloseRequested(sessionId = id))
        }

        override fun onCrash(session: GeckoSession) = onSessionDied()

        override fun onKill(session: GeckoSession) = onSessionDied()

        /**
         * Gecko has closed this session because its content process died
         * (crash, or the system killing it while the app was backgrounded).
         * Tell the tab layer so it can restore the tab from saved state
         * instead of leaving a dead session attached to the view, which is
         * what used to come back black/empty after minimising the app.
         */
        private fun onSessionDied() {
            _state.update { it.copy(isLoading = false, loadFailed = true) }
            _events.tryEmit(BrowserSessionEvent.SessionDied(sessionId = id))
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            val fileName = DownloadFileName.fromContentDisposition(
                response.headers.headerValue("Content-Disposition"),
            ) ?: DownloadFileName.fromUrl(response.uri)
            val mimeType = response.headers.headerValue("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val contentLength = response.headers
                .headerValue("Content-Length")
                ?.toLongOrNull()
                ?: -1L
            val body = response.body
            if (body != null) {
                // The download layer reads this stream on a background
                // thread; the default 30 s per-read timeout would kill slow
                // or large transfers mid-stream.
                runCatching { response.setReadTimeoutMillis(0) }
            }

            val handedOff = _events.tryEmit(
                BrowserSessionEvent.DownloadRequested(
                    sessionId = id,
                    url = response.uri,
                    fileName = fileName,
                    mimeType = mimeType,
                    isPrivate = isPrivate,
                    pageUrl = _state.value.url.takeIf { it.isNotBlank() },
                    contentLength = contentLength,
                    // Hand the already-received body to the download layer.
                    // Re-GET-ing the URL cannot reproduce blob/data/POST or
                    // authenticated downloads (e.g. generated ZIP exports),
                    // which is why those used to fail with "could not start".
                    bodySource = body?.let { stream -> { stream } },
                ),
            )
            if (!handedOff && body != null) {
                // Nobody took the stream: it must still be closed, otherwise
                // the underlying connection leaks until GC.
                try {
                    body.close()
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to close download response stream", e)
                }
            }
        }
    }

    init {
        geckoSession.setPromptDelegate(promptDelegate)
        geckoSession.setNavigationDelegate(navigationDelegate)
        geckoSession.setProgressDelegate(progressDelegate)
        geckoSession.setContentDelegate(contentDelegate)
        geckoSession.setAutofillDelegate(autofillDelegate)
    }

    private companion object {
        const val TAG = "GeckoBrowserSession"
        const val MAX_THUMBNAIL_WIDTH = 480

        /** HTTP header names are case-insensitive. */
        fun Map<String, String>.headerValue(name: String): String? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}
