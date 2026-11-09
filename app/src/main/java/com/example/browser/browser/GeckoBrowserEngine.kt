package com.example.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autofill
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.StorageController
import com.example.browser.data.settings.SitePreferencesStore
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import com.example.browser.download.DownloadFileName
import com.example.browser.util.AppLog
import com.example.browser.util.CredentialOrigin
import com.example.browser.util.UrlUtils

/** Gecko's NS_BINDING_ABORTED: the request was superseded, it did not fail. */
private const val NS_BINDING_ABORTED = 0x804B0002.toInt()

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

    override var onTabSwipe: ((Int) -> Unit)? = null

    override var onPullToRefresh: (() -> Unit)? = null

    override var onPullProgress: ((Float) -> Unit)? = null

    /** host -> stored zoom percent, resolved synchronously by the shell. */
    override var siteZoomProvider: ((String) -> Int?)? = null

    @Volatile
    override var tabSwipeEnabled: Boolean = false

    @Volatile
    private var textZoomPercent: Int = 100

    @Volatile
    private var preferredColorScheme: Int = GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM

    @Volatile
    private var trackingProtectionEnabled: Boolean = true

    @Volatile
    private var httpsOnlyMode: Boolean = false

    /** Live session wrappers, used only to apply per-session controls. */
    private val liveSessions: MutableSet<GeckoBrowserSession> =
        Collections.newSetFromMap(WeakHashMap())

    /** Last loaded Gecko permission objects, needed by StorageController. */
    private val storedPermissionObjects =
        mutableMapOf<String, PermissionDelegate.ContentPermission>()

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
            ).also { created ->
                processRuntime = created
                // Third-party cookie partitioning (Firefox's Total Cookie
                // Protection) is deliberately NOT enabled: without the
                // Storage Access API that Firefox pairs it with, Google's
                // federated sign-in and other cross-site login flows bounce
                // back to the error card in a retry loop. Correct sign-in
                // beats a privacy feature we cannot complete, and it is
                // re-enabled only together with storage-access support.
                created.settings.setAllowInsecureConnections(
                    if (httpsOnlyMode) {
                        GeckoRuntimeSettings.HTTPS_ONLY
                    } else {
                        GeckoRuntimeSettings.ALLOW_ALL
                    },
                )
            }
        }

    override fun createSession(config: BrowserSessionConfig): BrowserSession =
        createSessionInternal(config).also { it.ensureOpen() }

    override fun restoreSession(
        config: BrowserSessionConfig,
        savedState: SavedTabState,
    ): BrowserSession =
        createSessionInternal(config, pendingRestore = savedState).also { it.ensureOpen() }

    override fun setTextZoomPercent(percent: Int) {
        applyZoomFactor(percent)
    }

    /**
     * Applies a zoom factor to the (process-wide) Gecko settings.
     *
     * Gecko exposes text scaling only at runtime level, so the factor follows
     * the page in front of the user: [applySiteZoom] is called on every page
     * start, which is what makes a per-site zoom survive both a tab switch and
     * a cold start (the old code applied it once, at settings-load time, and a
     * page that started before that kept rendering at 100% while the UI still
     * showed 75%).
     */
    private fun applyZoomFactor(percent: Int) {
        textZoomPercent = percent.coerceIn(
            SitePreferencesStore.MIN_ZOOM,
            SitePreferencesStore.MAX_ZOOM,
        )
        // Font size is a runtime setting: pushing the same value again forces
        // every open page to lay out again. That was a steady source of jank
        // on every page start and every tab switch.
        if (textZoomPercent == appliedZoomPercent) return
        val settings = processRuntime?.settings ?: return
        runCatching {
            settings.setAutomaticFontSizeAdjustment(false)
            settings.setFontSizeFactor(textZoomPercent / 100f)
            // Only remember the factor once it really reached the runtime;
            // otherwise a call made before the runtime exists would block the
            // first real application later.
            appliedZoomPercent = textZoomPercent
        }
    }

    /** Applies the stored zoom for [url]'s site; 100% when none is stored. */
    internal fun applySiteZoom(url: String) {
        val host = SitePreferencesStore.hostOf(url) ?: return
        val percent = siteZoomProvider?.invoke(host) ?: SitePreferencesStore.DEFAULT_ZOOM
        applyZoomFactor(percent)
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

    override fun applyContentControls(
        javaScriptEnabled: Boolean,
        trackingProtectionEnabled: Boolean,
        httpsOnlyMode: Boolean,
    ) {
        this.trackingProtectionEnabled = trackingProtectionEnabled
        this.httpsOnlyMode = httpsOnlyMode
        processRuntime?.settings?.let { runtimeSettings ->
            runtimeSettings.setJavaScriptEnabled(javaScriptEnabled)
            runtimeSettings.setAllowInsecureConnections(
                if (httpsOnlyMode) {
                    GeckoRuntimeSettings.HTTPS_ONLY
                } else {
                    GeckoRuntimeSettings.ALLOW_ALL
                },
            )
        }
        synchronized(liveSessions) {
            liveSessions.toList()
        }.forEach { session ->
            session.applyTrackingProtection(trackingProtectionEnabled)
        }
    }

    override fun loadSitePermissions(
        onResult: (List<SitePermissionEntry>) -> Unit,
    ) {
        val runtime = processRuntime
        if (runtime == null) {
            onResult(emptyList())
            return
        }
        runtime.storageController.getAllPermissions().accept(
            { permissions ->
                val entries = permissions.orEmpty()
                    .filterNot { it.privateMode }
                    .mapNotNull { permission ->
                    val kind = permission.toAppPermissionKind() ?: return@mapNotNull null
                    storedPermissionObjects[
                        permissionKey(
                            permission.uri,
                            kind,
                            permission.privateMode,
                            permission.permission,
                        )
                    ] = permission
                    SitePermissionEntry(
                        uri = permission.uri,
                        kind = kind,
                        state = when (permission.value) {
                            PermissionDelegate.ContentPermission.VALUE_ALLOW ->
                                SitePermissionState.ALLOW
                            PermissionDelegate.ContentPermission.VALUE_DENY ->
                                SitePermissionState.DENY
                            else -> SitePermissionState.PROMPT
                        },
                        isPrivate = permission.privateMode,
                        permissionType = permission.permission,
                    )
                }.sortedWith(compareBy({ it.uri }, { it.kind.name }))
                onResult(entries)
            },
            { onResult(emptyList()) },
        )
    }

    override fun setSitePermission(
        uri: String,
        kind: SitePermissionKind,
        decision: SitePermissionDecision,
        isPrivate: Boolean,
        permissionType: Int,
    ) {
        if (uri.isBlank()) return
        val permission = storedPermissionObjects[
            permissionKey(uri, kind, isPrivate, permissionType)
        ] ?: return
        val value = when (decision) {
            SitePermissionDecision.ASK ->
                PermissionDelegate.ContentPermission.VALUE_PROMPT
            SitePermissionDecision.ALLOW ->
                PermissionDelegate.ContentPermission.VALUE_ALLOW
            SitePermissionDecision.DENY ->
                PermissionDelegate.ContentPermission.VALUE_DENY
        }
        processRuntime?.storageController?.setPermission(permission, value)
    }

    private fun permissionKey(
        uri: String,
        kind: SitePermissionKind,
        isPrivate: Boolean,
        permissionType: Int,
    ): String = "$uri|$kind|$isPrivate|$permissionType"


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

    // ---- Content surface: one GeckoView, sessions swapped into it ----------
    //
    // Copied from Mozilla rather than invented. In android-components a
    // `GeckoEngineView` is a FrameLayout holding ONE `NestedGeckoView`, and
    // `render(session)` swaps the session inside it:
    //
    //     geckoView.session?.let { geckoView.releaseSession() }
    //     geckoView.setSession(next.geckoSession)
    //
    // (components/browser/engine-gecko/GeckoEngineView.kt). The session object
    // owns the page — history, scroll, form state, the live document — and the
    // view is only the glass it is drawn through, which is why a tab switch is
    // a repaint and never a load.
    //
    // The previous version of this file did the opposite: one surface in the
    // tree per tab, INVISIBLE for the ones not on screen, plus
    // `GeckoSession.setActive(false)` on every tab it left. `setActive(false)`
    // is the API whose documented right is to drop the document to save
    // memory, so a trip to another tab came back as a reload, and the stack of
    // per-tab surfaces needed a shelf of repairs (re-binds, re-attaches, paint
    // bookkeeping) for problems a single view never has.
    private var contentContainer: FrameLayout? = null
    private var contentContainerContext: Context? = null

    /** The one surface every session is drawn through. */
    private var contentView: HexoraGeckoView? = null

    /**
     * False while an overlay (Start Page, crash card, error card) covers the
     * content: the web surfaces then ignore touches instead of consuming them,
     * so the Compose UI on top receives the tap. A real View that consumes the
     * MotionEvent first is what made Start Page buttons look dead.
     */
    @Volatile
    internal var contentTouchesAllowed = true

    /** True while the container is hidden under an overlay. */
    @Volatile
    private var contentHidden = false

    /** Touches refused by the surface while an overlay was on top. */
    private var blockedTouches = 0

    /** The session currently on screen; only it may change the text zoom. */
    @Volatile
    private var activeSession: GeckoBrowserSession? = null

    internal fun isActiveSession(session: GeckoBrowserSession): Boolean =
        activeSession === session

    /** Last factor pushed to the runtime, to avoid needless global reflows. */
    @Volatile
    private var appliedZoomPercent: Int = -1

    // Diagnostics counters (see BrowserEngine.diagnostics): they let a user
    // report what actually happened without a logcat cable.
    internal var paintRecoveries: Int = 0
    private var contentBinds: Int = 0
    private var paintStatusResets: Int = 0
    private var firstComposites: Int = 0

    /** Invalidates an older resume check when a new one starts. */
    private var resumeCheckToken = 0

    /** How many times the surface was re-bound from the live session. */
    internal var activationRepairs: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The app's single content container for [context].
     *
     * A container holding one [SwipeableHostLayout] holding one
     * [HexoraGeckoView] — the same shape android-components uses
     * (`GeckoEngineView` is a FrameLayout with a single `NestedGeckoView`
     * inside). It is created once and never rebuilt per tab; a tab switch
     * changes the session inside the view, not the view.
     */
    internal fun obtainContainer(context: Context): View {
        val existing = contentContainer
        if (existing != null && contentContainerContext === context) {
            // Compose may hand the same instance back while the previous
            // AndroidView node is still disposing it; a view can only have one
            // parent, so detach it first.
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        // A different Activity context invalidates every view built with it.
        // The sessions are untouched and re-render into the new view.
        existing?.removeAllViews()
        contentView = null

        val host = SwipeableHostLayout(context).apply {
            swipeEnabledProvider = { tabSwipeEnabled }
            onSwipeTab = { delta -> onTabSwipe?.invoke(delta) }
        }
        val view = HexoraGeckoView(context).apply {
            // Hexora provides its own offline credential vault; GeckoView's
            // Android Autofill bridge would replace that delegate.
            setAutofillEnabled(false)
            touchDeliveryEnabled = contentTouchesAllowed
            onBlockedTouch = { noteBlockedTouch() }
            onPullProgress = { progress -> onPullProgress?.invoke(progress) }
            onPullRefresh = { onPullToRefresh?.invoke() }
            onViewAttached = { attached -> onSurfaceAttached(attached) }
        }
        host.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val container = FrameLayout(context).apply {
            clipChildren = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(
                host,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        container.visibility = if (contentHidden) View.INVISIBLE else View.VISIBLE
        contentContainer = container
        contentView = view
        contentContainerContext = context
        (container.parent as? ViewGroup)?.removeView(container)
        // Whatever tab is on screen goes straight back onto the new glass: a
        // repaint of the live document, never a load.
        activeSession?.let { present(it, container) }
        return container
    }

    /**
     * Puts [session] on screen inside [container].
     *
     * Runs on every UI state emission, so the early return is what keeps it
     * cheap: the work happens only when the session actually changes.
     */
    internal fun present(session: GeckoBrowserSession, container: View) {
        if (container !== contentContainer) return
        val view = contentView ?: return
        // Text zoom is process-wide, so the session on screen is the only one
        // allowed to set it; [render] applies it for this one before binding.
        activeSession = session
        render(view, session)
    }

    /**
     * Swaps [session] into the one surface.
     *
     * Mozilla's `GeckoEngineView.render()`: release what the view is holding so
     * GeckoView does not close it, then bind the next session. The session
     * keeps the page; the view is only the glass.
     */
    private fun render(view: HexoraGeckoView, session: GeckoBrowserSession) {
        if (view.session === session.geckoSession) return
        view.session?.let {
            // Release first: GeckoView closes a bound session when it is
            // replaced or detached, and this one has to outlive its tab.
            runCatching { view.releaseSession() }
        }
        // Set the factor before the document is bound, so the page lays out
        // with this site's zoom instead of reflowing after its first paint.
        session.applyOwnZoom()
        runCatching { view.setSession(session.geckoSession) }
            .onSuccess {
                session.installAutofillDelegate()
                contentBinds++
            }
    }

    /**
     * The surface came back into the window. GeckoView drops its display when
     * it is detached; the session still owns the page, so it is bound again —
     * a repaint, never a load.
     */
    private fun onSurfaceAttached(view: HexoraGeckoView) {
        val session = activeSession ?: return
        if (!session.geckoSession.isOpen) return
        render(view, session)
    }

    override fun setContentHidden(hidden: Boolean) {
        // Re-applied even when the flag does not change: coming back from the
        // background can leave the container INVISIBLE (it was hidden under an
        // overlay) with no state change to trigger a new call.
        val wasHidden = contentHidden
        contentHidden = hidden
        val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        contentContainer?.visibility = visibility
        // GeckoView does not react to onVisibilityChanged (Mozilla bug
        // 1630775); android-components propagates it to the inner view by hand
        // for exactly this reason, and so does this.
        contentView?.visibility = visibility
        if (wasHidden && !hidden) verifySurfaceAfterUnhide()
    }

    /**
     * Coming out from under an overlay: the surface was invisible, and on some
     * devices GeckoView's display does not come back until it is told again.
     * The question asked here is Gecko's own — did a frame arrive? — and only a
     * "no" costs anything, in one re-bind of the live session.
     */
    private fun verifySurfaceAfterUnhide() {
        val session = activeSession ?: return
        if (!session.geckoSession.isOpen) return
        val framesAtShow = session.frameCount
        val token = ++resumeCheckToken
        mainHandler.postDelayed({
            if (token != resumeCheckToken) return@postDelayed
            if (contentHidden) return@postDelayed
            if (!session.geckoSession.isOpen) return@postDelayed
            if (session.frameCount != framesAtShow) return@postDelayed
            reRender(session)
        }, PAINT_RELOAD_GRACE_MS)
    }

    /** Applied from [HexoraGeckoView] when it refuses a gesture. */
    internal fun noteBlockedTouch() {
        blockedTouches++
    }

    /** The surface, but only when it is currently drawing [session]. */
    internal fun renderedView(session: GeckoBrowserSession): HexoraGeckoView? {
        val view = contentView ?: return null
        if (contentHidden) return null
        if (view.session !== session.geckoSession) return null
        if (view.visibility != View.VISIBLE) return null
        if (!ViewCompat.isAttachedToWindow(view)) return null
        return view
    }

    /** Drops [session] off the glass before its tab closes. */
    internal fun releaseSurfaceIfRendered(session: GeckoBrowserSession) {
        val view = contentView ?: return
        if (view.session !== session.geckoSession) return
        runCatching { view.releaseSession() }
    }

    /** Applies [allowed] to the surface; a rebuilt view inherits it. */
    override fun setContentTouchAllowed(allowed: Boolean) {
        if (contentTouchesAllowed == allowed) return
        contentTouchesAllowed = allowed
        contentView?.touchDeliveryEnabled = allowed
    }

    /** Gecko told us a surface's painted content is no longer displayed. */
    internal fun notifyPaintStatusReset(session: GeckoSession) {
        paintStatusResets++
    }

    /** Gecko produced a frame: that surface is definitely alive. */
    internal fun notifyFirstComposite(session: GeckoSession) {
        firstComposites++
    }

    override fun noteAppBackgrounded() {
        // The per-tab paint state is the source of truth now; nothing to
        // snapshot here. Kept as the hook the Activity posts on stop.
    }

    override fun onWindowGeometryChanged() {
        val container = contentContainer ?: return
        // Measure again from the window down. A window that grew while the
        // surface was frozen is how a page pane ends up at the old height with
        // the app's background filling the rest.
        container.requestLayout()
        contentView?.requestLayout()
        val session = activeSession ?: return
        if (!session.geckoSession.isOpen) return
        val token = ++resumeCheckToken
        mainHandler.postDelayed({
            if (token != resumeCheckToken) return@postDelayed
            if (contentHidden) return@postDelayed
            if (!session.geckoSession.isOpen) return@postDelayed
            if (session.hasPainted && !session.painted) reRender(session)
        }, PAINT_RESUME_GRACE_MS)
    }

    /**
     * Puts the live session back on the glass after Gecko reported that its
     * painted content is gone and no frame followed. No load, no history, no
     * scroll change: the session never left.
     */
    private fun reRender(session: GeckoBrowserSession) {
        val view = contentView ?: return
        if (view.session !== session.geckoSession) return
        if (!session.geckoSession.isOpen) return
        runCatching { view.releaseSession() }
        runCatching { view.setSession(session.geckoSession) }
            .onSuccess {
                session.installAutofillDelegate()
                paintRecoveries++
                activationRepairs++
                AppLog.event("content re-rendered from the live session")
            }
    }

    /**
     * After a trip to the background Gecko may have dropped the painted
     * content. If no frame arrives, the live session goes back on the glass.
     */
    override fun verifyPaintAfterResume() {
        if (contentContainer == null) return
        val token = ++resumeCheckToken
        mainHandler.postDelayed({
            if (token != resumeCheckToken) return@postDelayed
            if (contentHidden) return@postDelayed
            // Resolved late: a tab restored during the grace period is the one
            // that has to be re-rendered, not the dead one seen first.
            val session = activeSession ?: return@postDelayed
            if (!session.geckoSession.isOpen) return@postDelayed
            if (session.hasPainted && !session.painted) reRender(session)
        }, PAINT_RESUME_GRACE_MS)
    }

    /**
     * Post-load verification, used after Retry or a refresh: a load that
     * started but never painted leaves an error card over a pane that ignores
     * every recovery button.
     */
    override fun verifyDisplayAfterReload() {
        if (contentContainer == null) return
        val token = ++resumeCheckToken
        mainHandler.postDelayed({
            if (token != resumeCheckToken) return@postDelayed
            if (contentHidden) return@postDelayed
            val session = activeSession ?: return@postDelayed
            if (!session.geckoSession.isOpen) return@postDelayed
            if (session.hasPainted && session.painted) return@postDelayed
            reRender(session)
        }, PAINT_RELOAD_GRACE_MS)
    }

    /**
     * Called once the window has a surface again after a return to the
     * foreground. The platform drops a GeckoView's display while the app is
     * away, and GeckoView forgets which session it belonged to; binding the
     * live session again is the repair — not a reload.
     */
    override fun ensureContentDisplayed() {
        val view = contentView ?: return
        if (view.session != null) return
        val session = activeSession ?: return
        if (!session.geckoSession.isOpen) return
        runCatching { view.setSession(session.geckoSession) }
            .onSuccess {
                session.installAutofillDelegate()
                paintRecoveries++
            }
    }

    /** Human-readable counters for the in-app diagnostics screen. */
    override fun diagnostics(): String = buildString {
        append("renders=").append(contentBinds)
        append(" paintResets=").append(paintStatusResets)
        append(" firstPaint=").append(firstComposites)
        append(" recoveries=").append(paintRecoveries)
        append(" reattaches=").append(activationRepairs)
        // Text zoom is process-wide; this is the value actually pushed to the
        // runtime. surface= says whether the one view currently holds a session.
        append(" zoom=").append(
            if (appliedZoomPercent < 0) textZoomPercent else appliedZoomPercent,
        )
        append(" surface=").append(if (contentView?.session != null) 1 else 0)
        append(" touchBlocked=").append(blockedTouches)
        append(" contentHidden=").append(contentHidden)
    }

    override fun finishPullToRefresh() {
        onPullProgress?.invoke(0f)
    }

    internal fun createSessionInternal(
        config: BrowserSessionConfig,
        pendingRestore: SavedTabState? = null,
    ): GeckoBrowserSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(config.isPrivate)
            .useTrackingProtection(trackingProtectionEnabled)
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
        ).also { session ->
            synchronized(liveSessions) { liveSessions += session }
        }
    }

    internal fun unregisterSession(session: GeckoBrowserSession) {
        synchronized(liveSessions) { liveSessions -= session }
    }


    internal fun notifyNewSessionCreated(session: BrowserSession, openerSessionId: String?) {
        newSessionCreatedListener?.invoke(session, openerSessionId)
    }

    private companion object {
        /** How long a resumed surface may stay unpainted before we repair it. */
        const val PAINT_RESUME_GRACE_MS = 1200L

        /** A load that produced no frame in this window is re-acquired. */
        const val PAINT_RELOAD_GRACE_MS = 1500L

        private val RUNTIME_LOCK = Any()

        @Volatile
        private var processRuntime: GeckoRuntime? = null

        /**
         * Grace period after returning to the foreground before the display is
         * re-acquired because Gecko reported no frame at all.
         */
    }
}

internal fun PermissionDelegate.ContentPermission.toAppPermissionKind(): SitePermissionKind? =
    when (permission) {
        PermissionDelegate.PERMISSION_GEOLOCATION -> SitePermissionKind.GEOLOCATION
        PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> SitePermissionKind.NOTIFICATIONS
        PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> SitePermissionKind.PERSISTENT_STORAGE
        PermissionDelegate.PERMISSION_XR -> SitePermissionKind.XR
        PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
        PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
        -> SitePermissionKind.AUTOPLAY
        PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ->
            SitePermissionKind.MEDIA_KEY_SYSTEM
        PermissionDelegate.PERMISSION_STORAGE_ACCESS -> SitePermissionKind.STORAGE_ACCESS
        PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
        PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS,
        -> SitePermissionKind.LOCAL_NETWORK
        else -> null
    }

/**
 * Host of the shared content view.
 *
 * It exists for one gesture only: the fullscreen left/right tab swipe, which
 * has to be detected at the view level because touch events over a web surface
 * never reach Compose.
 *
 * It is deliberately NOT a SwipeRefreshLayout. A parent that intercepts touch
 * events to run pull-to-refresh cannot know whether the page can still scroll
 * up (a GeckoView is not a scrolling View and has no synchronous answer), so
 * in practice it steals ordinary page scrolling: the page stops scrolling and
 * the refresh indicator moves instead. Pull-to-refresh is detected *inside*
 * the GeckoView from Gecko's own touch result, and the page keeps every event
 * it would get without us.
 */
internal class SwipeableHostLayout(context: Context) : FrameLayout(context) {

    /** +1 = swiped left (next tab), -1 = swiped right (previous tab). */
    var onSwipeTab: ((Int) -> Unit)? = null

    /** Consulted on every gesture start; swipes are ignored while false. */
    var swipeEnabledProvider: () -> Boolean = { false }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var swiping = false
    private var fired = false
    private var multiTouch = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                swiping = false
                fired = false
                multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger makes the gesture a pinch or a two-finger
                // pan of a zoomed page. Taking it for a tab swipe moved the
                // page out from under the pinch — the gesture that was left
                // over then had nothing to do with what the fingers were doing.
                multiTouch = true
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                // A tab swipe must be a quick, deliberate flick, and it is
                // never active while the keyboard is up: slow drags on a text
                // field belong to the page.
                if (!swiping && !multiTouch && swipeEnabledProvider() && !isKeyboardVisible()) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (event.eventTime - downTime <= SWIPE_MAX_DURATION_MS &&
                        kotlin.math.abs(dx) > touchSlop * 4 &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 3f
                    ) {
                        swiping = true
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swiping = false
                multiTouch = false
            }
        }
        return super.onInterceptTouchEvent(event) || swiping
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (swiping) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    if (!fired &&
                        kotlin.math.abs(dx) > SWIPE_DISTANCE_DP * density &&
                        event.eventTime - downTime <= SWIPE_MAX_DURATION_MS
                    ) {
                        fired = true
                        onSwipeTab?.invoke(if (dx < 0) 1 else -1)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swiping = false
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun isKeyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(this)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private companion object {
        const val SWIPE_DISTANCE_DP = 64f

        /** Longer than this and the gesture is a drag, not a tab swipe. */
        const val SWIPE_MAX_DURATION_MS = 700L
    }
}

/**
 * The one surface every tab is drawn through — Mozilla's `NestedGeckoView`,
 * with two behaviours added on top, both of them theirs too:
 *
 * Two behaviours matter here and both follow Mozilla's `GeckoEngineView` /
 * `NestedGeckoView`:
 *
 * 1. **The session survives a detach.** A plain [GeckoView] closes its session
 *    in `onDetachedFromWindow()`; releasing it first keeps the page alive so
 *    returning to it is a repaint, not a reload.
 * 2. **Pull-to-refresh asked of Gecko, not guessed.** Gecko's own
 *    `InputResultDetail` says whether the page can still scroll towards its
 *    top, so a pull is only ever recognised when the page really is at the
 *    top, and it never takes a touch away from the page.
 */
@SuppressLint("ClickableViewAccessibility")
internal class HexoraGeckoView(context: Context) : GeckoView(context) {

    var onViewAttached: ((HexoraGeckoView) -> Unit)? = null
    var onViewDetached: ((HexoraGeckoView) -> Unit)? = null

    /** 0f..1.5f pull progress, for the in-app indicator. */
    var onPullProgress: ((Float) -> Unit)? = null

    /** The user released a pull that passed the threshold. */
    var onPullRefresh: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val pullThresholdPx = PULL_THRESHOLD_DP * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * While false the view refuses the gesture outright (no consumption), so a
     * Compose overlay drawn above it — Start Page, crash card, error card —
     * receives the tap. A consuming View would swallow it first.
     *
     * Turning it off while Gecko still holds a gesture closes that gesture
     * (see [cancelTouchInGecko]); an overlay appearing mid-drag used to leave
     * the pan/zoom controller with a finger that was never lifted.
     */
    var touchDeliveryEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                endPullGesture()
                cancelTouchInGecko()
            }
        }

    /** Called for every gesture this view refuses while an overlay is up. */
    var onBlockedTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!touchDeliveryEnabled) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onBlockedTouch?.invoke()
            }
            // Nothing takes this gesture, and anything of it Gecko already
            // received is closed rather than dropped.
            endPullGesture()
            cancelTouchInGecko()
            return false
        }
        return super.dispatchTouchEvent(event)
    }

    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    /** Down time of the gesture in flight; a cancel is only accepted with it. */
    private var lastDownTime = 0L
    /** True from a delivered ACTION_DOWN until its UP or CANCEL. */
    private var gestureInFlight = false
    /** Gecko's answer for the current gesture; pessimistic until it arrives. */
    private var pageCanScrollUp = true
    private var gestureDecided = false
    private var pulling = false
    private var lastProgress = 0f

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                lastX = ev.x
                lastY = ev.y
                lastDownTime = ev.downTime
                gestureInFlight = true
                pulling = false
                gestureDecided = false
                pageCanScrollUp = true
                reportPull(0f)
                // This call *is* the delivery: Gecko's own documentation says
                // it is "the same as onTouchEvent(MotionEvent), but instead
                // returns a result". Sending the same event through
                // onTouchEvent() afterwards handed Gecko two touch streams for
                // one finger. What that left behind was a pan/zoom controller
                // convinced a pinch was still open: the page could be scrolled
                // off its own top and no scroll would bring it back — only a
                // new tab did.
                //
                // The event is passed as it arrived, never a recycled copy:
                // while the view is not attached Gecko queues the object and
                // replays it later, so a copy recycled here came back as
                // garbage coordinates and pointer indices.
                onTouchEventForDetailResult(ev).accept(
                    { detail ->
                        val canScrollTop = detail != null &&
                            (detail.scrollableDirections() and
                                PanZoomController.SCROLLABLE_FLAG_TOP) != 0
                        val siteHandled = detail?.handledResult() ==
                            PanZoomController.INPUT_RESULT_HANDLED_CONTENT
                        pageCanScrollUp = detail == null || canScrollTop || siteHandled
                        gestureDecided = true
                    },
                    { gestureDecided = true },
                )
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                lastX = ev.x
                lastY = ev.y
                if (!pulling && gestureDecided && !pageCanScrollUp &&
                    ev.y - downY > touchSlop
                ) {
                    pulling = true
                }
                if (pulling) {
                    val distance = (ev.y - downY).coerceAtLeast(0f)
                    reportPull((distance / pullThresholdPx).coerceIn(0f, 1.5f))
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is a pinch; it is not a pull towards refresh.
                endPullGesture()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val shouldRefresh = pulling &&
                    ev.actionMasked == MotionEvent.ACTION_UP &&
                    (ev.y - downY) >= pullThresholdPx * 0.9f
                gestureInFlight = false
                pulling = false
                reportPull(0f)
                if (shouldRefresh) onPullRefresh?.invoke()
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun endPullGesture() {
        pulling = false
        reportPull(0f)
    }

    /**
     * Ends a gesture Gecko is still holding with an ACTION_CANCEL.
     *
     * The cancel carries the gesture's own down time: the pan/zoom controller
     * drops any event whose down time does not match the one it recorded, so a
     * cancel built from "now" would be ignored and the finger would stay down.
     *
     * It goes to [super.onTouchEvent] on purpose — through this class's
     * [dispatchTouchEvent] it would hit the guard above and be dropped, which
     * is exactly how the phantom finger used to survive.
     */
    private fun cancelTouchInGecko() {
        if (!gestureInFlight) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // The pan/zoom controller only accepts input on the UI thread.
            uiHandler.post { cancelTouchInGecko() }
            return
        }
        gestureInFlight = false
        val cancel = MotionEvent.obtain(
            lastDownTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL,
            lastX,
            lastY,
            0,
        )
        // Deliberately not recycled: Gecko queues the object when the view is
        // between sessions and replays it on re-attach.
        super.onTouchEvent(cancel)
    }

    private fun reportPull(progress: Float) {
        if (progress == lastProgress && progress != 0f) return
        lastProgress = progress
        onPullProgress?.invoke(progress)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onViewAttached?.invoke(this)
    }

    override fun onDetachedFromWindow() {
        // A gesture in flight is closed first: a view detached mid-drag leaves
        // the pan/zoom controller holding a finger it will replay on re-attach.
        endPullGesture()
        cancelTouchInGecko()
        // Release first: prevents GeckoView from closing the session.
        if (session != null) releaseSession()
        onViewDetached?.invoke(this)
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Drag distance that completes a pull-to-refresh. */
        const val PULL_THRESHOLD_DP = 72f
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

    internal var rootScrollY: Int? = null
        private set

    override val id: String = UUID.randomUUID().toString()
    override val isPrivate: Boolean = config.isPrivate

    private val _state = MutableStateFlow(
        SessionPageState(isDesktopMode = config.desktopMode),
    )

    /**
     * Latest Gecko session snapshot. Kept as the engine object and only
     * JSON-serialized when the tab is actually suspended or persisted:
     * serializing on every state-change callback (scroll/form updates can
     * arrive constantly) burned CPU and battery for a string nobody reads
     * until suspension.
     */
    @Volatile
    private var latestSessionState: GeckoSession.SessionState? = null

    @Volatile
    private var latestFindQuery: String = ""
    override val state: StateFlow<SessionPageState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BrowserSessionEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<BrowserSessionEvent> = _events.asSharedFlow()

    /**
     * Paint bookkeeping for this tab.
     *
     * Gecko reports when painted content is gone (`onPaintStatusReset`) and
     * when a frame exists (`onFirstComposite`). A session that is not on screen
     * has no glass of its own, so the only repair left is binding the live
     * session to the shared view again — and it is only worth doing when Gecko
     * itself says the content was dropped.
     */
    /** When this tab last finished a load successfully (for stale-error filtering). */
    @Volatile
    private var lastSuccessfulStopAt = 0L

    @Volatile
    internal var hasPainted = false

    /** A frame arrived after the last paint-status reset. */
    @Volatile
    internal var painted = false

    /** Frames composed for this tab (monotonic, for the repair check). */
    @Volatile
    internal var frameCount = 0

    /** Paint-status resets reported for this tab (monotonic). */
    @Volatile
    internal var resetCount = 0

    /** True when Gecko itself said the displayed content is gone and nothing replaced it. */
    internal val lostDisplay: Boolean
        get() = hasPainted && !painted

    /** Re-applies this site's zoom if this tab is the visible one. */
    internal fun applyOwnZoom() {
        val url = _state.value.url
        if (url.isNotBlank() && engine.isActiveSession(this)) engine.applySiteZoom(url)
    }

    @Volatile
    private var closed = false

    @Volatile
    private var priorityHigh: Boolean = false

    /**
     * Set when Gecko reports this session's content process died
     * (`onCrash`/`onKill`). A later defensive reopen must restore the saved
     * state; reopening blank is what used to turn the tab into an empty
     * page after the app spent time in the background.
     */
    @Volatile
    private var closedUnexpectedly: Boolean = false

    /** Opens the session against the shared runtime if it is not open yet. */
    internal fun ensureOpen() {
        if (closed || geckoSession.isOpen) return
        geckoSession.open(engine.runtime)
        geckoSession.setPriorityHint(
            if (priorityHigh) {
                GeckoSession.PRIORITY_HIGH
            } else {
                GeckoSession.PRIORITY_DEFAULT
            },
        )
        val explicitRestore = pendingRestore
        val restore = explicitRestore
            ?: if (closedUnexpectedly) latestSavedState() else null
        pendingRestore = null
        closedUnexpectedly = false
        restore?.let { saved ->
            val restored = GeckoSession.SessionState.fromString(saved.serialized)
            when {
                restored != null -> geckoSession.restoreState(restored)
                // An explicit restore that cannot be parsed must fail loudly
                // so the caller falls back to loading the tab's last URL
                // instead of silently opening an empty tab.
                explicitRestore != null ->
                    throw IllegalStateException("Unreadable saved tab state")
                else -> Unit
            }
        }
    }

    override fun latestSavedState(): SavedTabState? =
        latestSessionState?.toString()?.let { SavedTabState(it) }

    override fun isUsable(): Boolean = !closed && geckoSession.isOpen

    internal fun applyTrackingProtection(enabled: Boolean) {
        if (!closed && geckoSession.isOpen) {
            geckoSession.settings.setUseTrackingProtection(enabled)
        }
    }

    override fun setDesktopMode(enabled: Boolean, reload: Boolean) {
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
            if (reload) geckoSession.reload()
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
        // One view, one session on it: only the tab on the glass has pixels to
        // capture. This is Mozilla's `GeckoEngineView.captureThumbnail()`,
        // which reads `geckoView.capturePixels()` — a background tab keeps
        // whatever preview it already has, and handing back someone else's
        // pixels would be worse than handing back nothing.
        val view = engine.renderedView(this)
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

    override fun saveAsPdf(onResult: (java.io.InputStream?) -> Unit) {
        if (closed || !geckoSession.isOpen) {
            onResult(null)
            return
        }
        geckoSession.saveAsPdf().accept(
            { stream -> onResult(stream) },
            { onResult(null) },
        )
    }

    override fun requestPrint(onResult: (java.io.InputStream?) -> Unit) {
        if (closed || !geckoSession.isOpen) {
            onResult(null)
            return
        }
        // A second print request must not strand the first one's callback
        // forever; fail the superseded request instead.
        pendingPrintCallback?.invoke(null)
        pendingPrintCallback = onResult
        geckoSession.didPrintPageContent().accept(
            { },
            {
                pendingPrintCallback = null
                onResult(null)
            },
        )
    }

    private var pendingPrintCallback: ((java.io.InputStream?) -> Unit)? = null

    private val printDelegate = object : GeckoSession.PrintDelegate {
        override fun onPrintWithStatus(
            pdfInputStream: java.io.InputStream,
        ): GeckoResult<Boolean> {
            val callback = pendingPrintCallback
            pendingPrintCallback = null
            if (callback == null) {
                val emitted = _events.tryEmit(
                    BrowserSessionEvent.PrintRequested(
                        sessionId = id,
                        bodySource = pdfInputStream,
                    ),
                )
                if (!emitted) runCatching { pdfInputStream.close() }
                return GeckoResult.fromValue(emitted)
            }
            callback(pdfInputStream)
            return GeckoResult.fromValue(true)
        }
    }

    override fun loadUrl(url: String) {
        if (closed || url.isBlank()) return
        ensureOpen()
        // Apply this site's zoom first: Gecko lays the document out with the
        // factor that is current when the navigation starts, so doing it in
        // onPageStart alone let the first frames render at the old value and
        // then snap.
        if (engine.isActiveSession(this)) engine.applySiteZoom(url)
        geckoSession.loadUri(url)
    }

    override fun reload() {
        if (closed) return
        if (engine.isActiveSession(this) && _state.value.url.isNotBlank()) {
            engine.applySiteZoom(_state.value.url)
        }
        if (geckoSession.isOpen) {
            val state = _state.value
            // After a failed load Gecko halts the navigation and there is no
            // document to reload, which is why the Retry button used to do
            // nothing. Ask for the URL again instead.
            if (state.loadFailed && state.url.isNotBlank()) {
                geckoSession.loadUri(state.url)
            } else {
                geckoSession.reload()
            }
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
        // Every tab switch walks the whole strip; a no-op hint push is an IPC
        // round-trip per tab, so only send real changes.
        if (priorityHigh == high && geckoSession.isOpen) return
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

    override fun contentContainer(context: Context): View =
        engine.obtainContainer(context)

    override fun presentContentView(container: View) {
        engine.present(this, container)
    }

    override fun setAutofillCredential(credential: AutofillCredential?) {
        val changed = credential?.origin != autofillCredential?.origin ||
            credential?.username != autofillCredential?.username
        autofillCredential = credential
        // A different (or newly loaded) login may be offered again; the same
        // one is offered only once per page.
        if (changed) savedLoginOffered = false
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
        geckoSession.setPermissionDelegate(null)
        geckoSession.setPrintDelegate(null)
        pendingPrintCallback?.invoke(null)
        pendingPrintCallback = null
        engine.unregisterSession(this)
        geckoSession.setNavigationDelegate(null)
        geckoSession.setProgressDelegate(null)
        geckoSession.setContentDelegate(null)
        geckoSession.setScrollDelegate(null)
        geckoSession.setAutofillDelegate(null)
        engine.releaseSurfaceIfRendered(this)
        if (geckoSession.isOpen) geckoSession.close()
    }

    @Volatile
    private var autofillCredential: AutofillCredential? = null
    /** The saved login for this page has already been offered once. */
    private var savedLoginOffered = false

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
                    captureMode = prompt.capture,
                ),
            )
            if (!emitted) {
                pendingFilePrompts.remove(requestId)
                return GeckoResult.fromValue(prompt.dismiss())
            }
            return result
        }

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AlertPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.ALERT,
                title = prompt.title,
                message = prompt.message,
                confirm = { prompt.dismiss() },
                dismiss = { prompt.dismiss() },
            )

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ButtonPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.CONFIRM,
                title = prompt.title,
                message = prompt.message,
                confirm = {
                    prompt.confirm(
                        GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE,
                    )
                },
                dismiss = {
                    prompt.confirm(
                        GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE,
                    )
                },
            )

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.TextPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.TEXT,
                title = prompt.title,
                message = prompt.message,
                defaultValue = prompt.defaultValue,
                confirmWithText = { text -> prompt.confirm(text.orEmpty()) },
                dismiss = { prompt.dismiss() },
            )

        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.BEFORE_UNLOAD,
                title = null,
                message = null,
                confirm = {
                    prompt.confirm(AllowOrDeny.ALLOW)
                },
                dismiss = {
                    prompt.confirm(AllowOrDeny.DENY)
                },
            )

        override fun onRepostConfirmPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.REPOST_CONFIRM,
                title = null,
                message = null,
                confirm = {
                    prompt.confirm(AllowOrDeny.ALLOW)
                },
                dismiss = {
                    prompt.confirm(AllowOrDeny.DENY)
                },
            )

        override fun onFolderUploadPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FolderUploadPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.FOLDER_UPLOAD,
                title = null,
                message = prompt.directoryName,
                confirm = {
                    prompt.confirm(AllowOrDeny.ALLOW)
                },
                dismiss = {
                    prompt.confirm(AllowOrDeny.DENY)
                },
            )

        override fun onPopupPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.PopupPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.POPUP,
                title = null,
                message = prompt.targetUri,
                confirm = {
                    prompt.confirm(AllowOrDeny.ALLOW)
                },
                dismiss = {
                    prompt.confirm(AllowOrDeny.DENY)
                },
            )

        override fun onAuthPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.AuthPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.AUTH,
                title = prompt.title,
                message = prompt.message,
                defaultValue = prompt.authOptions.username,
                confirmWithText = { credentials ->
                    val parts = credentials.orEmpty().split('\u001F', limit = 2)
                    val username = parts.getOrNull(0).orEmpty()
                    val password = parts.getOrNull(1).orEmpty()
                    if (prompt.authOptions.flags and
                        GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
                    ) {
                        prompt.confirm(password)
                    } else {
                        prompt.confirm(username, password)
                    }
                },
                dismiss = { prompt.dismiss() },
                passwordOnly =
                    prompt.authOptions.flags and
                        GeckoSession.PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0,
            )

        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ChoicePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            val flat = mutableListOf<GeckoSession.PromptDelegate.ChoicePrompt.Choice>()
            fun addChoices(
                choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>,
            ) {
                choices.forEach { choice ->
                    val children = choice.items
                    if (children == null) {
                        flat += choice
                    } else {
                        addChoices(children)
                    }
                }
            }
            addChoices(prompt.choices)
            return webPrompt(
                kind = WebPromptKind.CHOICE,
                title = prompt.title,
                message = prompt.message,
                confirmWithSelection = { selectedIndices ->
                    // Indices travel from the UI; page-controlled option
                    // ids are only ever read here, never parsed back out
                    // of a delimited string.
                    val ids = selectedIndices
                        .mapNotNull { index -> flat.getOrNull(index)?.id }
                        .toTypedArray()
                    when {
                        ids.isEmpty() -> prompt.dismiss()
                        prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE ->
                            prompt.confirm(ids)
                        else -> prompt.confirm(ids.first())
                    }
                },
                dismiss = { prompt.dismiss() },
                options = flat.map { it.label },
                optionIds = flat.map { it.id },
                optionEnabled = flat.map { !it.disabled && !it.separator },
                optionSelected = flat.map { it.selected },
                allowMultiple =
                    prompt.type ==
                        GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE,
            )
        }

        override fun onColorPrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.ColorPrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.COLOR,
                title = prompt.title,
                message = null,
                defaultValue = prompt.defaultValue,
                confirmWithText = { color -> prompt.confirm(color.orEmpty()) },
                dismiss = { prompt.dismiss() },
                options = prompt.predefinedValues?.filterNotNull().orEmpty(),
                optionIds = prompt.predefinedValues?.filterNotNull().orEmpty(),
                optionEnabled = prompt.predefinedValues
                    ?.map { true }
                    .orEmpty(),
            )

        override fun onDateTimePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.DateTimePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.DATE_TIME,
                title = prompt.title,
                message = null,
                defaultValue = prompt.defaultValue,
                confirmWithText = { value -> prompt.confirm(value.orEmpty()) },
                dismiss = { prompt.dismiss() },
            )

        override fun onSharePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.SharePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
            webPrompt(
                kind = WebPromptKind.SHARE,
                title = prompt.title,
                message = listOfNotNull(prompt.text, prompt.uri)
                    .joinToString("\n")
                    .ifBlank { null },
                confirmWithText = { outcome ->
                    prompt.confirm(
                        when (outcome) {
                            "success" -> GeckoSession.PromptDelegate.SharePrompt.Result.SUCCESS
                            "failure" -> GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE
                            else -> GeckoSession.PromptDelegate.SharePrompt.Result.ABORT
                        },
                    )
                },
                dismiss = { prompt.dismiss() },
            )
    }

    private fun webPrompt(
        kind: WebPromptKind,
        title: String?,
        message: String?,
        defaultValue: String? = null,
        confirm: (() -> GeckoSession.PromptDelegate.PromptResponse)? = null,
        confirmWithText: ((String?) -> GeckoSession.PromptDelegate.PromptResponse)? = null,
        confirmWithSelection: ((List<Int>) -> GeckoSession.PromptDelegate.PromptResponse)? = null,
        dismiss: () -> GeckoSession.PromptDelegate.PromptResponse,
        options: List<String> = emptyList(),
        optionIds: List<String> = emptyList(),
        optionEnabled: List<Boolean> = emptyList(),
        optionSelected: List<Boolean> = emptyList(),
        allowMultiple: Boolean = false,
        passwordOnly: Boolean = false,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        if (closed) return GeckoResult.fromValue(dismiss())
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        var completed = false
        val emitted = _events.tryEmit(
            BrowserSessionEvent.WebPromptRequested(
                sessionId = id,
                kind = kind,
                title = title,
                message = message,
                defaultValue = defaultValue,
                confirm = { text, selectedIndices ->
                    if (!completed) {
                        completed = true
                        result.complete(
                            confirmWithSelection?.invoke(selectedIndices)
                                ?: confirmWithText?.invoke(text)
                                ?: confirm?.invoke()
                                ?: dismiss(),
                        )
                    }
                },
                dismiss = {
                    if (!completed) {
                        completed = true
                        result.complete(dismiss())
                    }
                },
                options = options,
                optionIds = optionIds,
                optionEnabled = optionEnabled,
                optionSelected = optionSelected,
                allowMultiple = allowMultiple,
                passwordOnly = passwordOnly,
            ),
        )
        if (!emitted) return GeckoResult.fromValue(dismiss())
        return result
    }

    private val permissionDelegate = object : PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: PermissionDelegate.Callback,
        ) {
            val requested = permissions?.filter { it.isNotBlank() }.orEmpty()
            if (closed || requested.isEmpty()) {
                callback.reject()
                return
            }
            val emitted = _events.tryEmit(
                BrowserSessionEvent.AndroidPermissionRequested(
                    sessionId = id,
                    permissions = requested,
                    grant = { runCatching { callback.grant() } },
                    deny = { runCatching { callback.reject() } },
                ),
            )
            if (!emitted) callback.reject()
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            permission: PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> {
            val kind = permission.toAppPermissionKind()
            if (closed || kind == null) {
                return GeckoResult.fromValue(
                    PermissionDelegate.ContentPermission.VALUE_DENY,
                )
            }
            // Permissions mainstream browsers decide internally are
            // answered right here and never become a user prompt: asking
            // "allow autoplay?" (and friends) on every site is the single
            // biggest prompt annoyance there is. What remains for the
            // user dialog is only what genuinely needs their judgement:
            // geolocation, notifications, and device media (handled by
            // the media/Android permission flows).
            val silentValue: Int? = when (kind) {
                // Chrome/Firefox policy: muted autoplay is harmless and
                // expected to just work; audible autoplay is blocked
                // until the user interacts with the page (which Gecko
                // then no longer treats as autoplay).
                SitePermissionKind.AUTOPLAY -> {
                    if (permission.permission ==
                        PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                    ) {
                        PermissionDelegate.ContentPermission.VALUE_ALLOW
                    } else {
                        PermissionDelegate.ContentPermission.VALUE_DENY
                    }
                }
                // Eviction protection only: no data leaves the device.
                SitePermissionKind.PERSISTENT_STORAGE ->
                    PermissionDelegate.ContentPermission.VALUE_ALLOW
                // DRM module access; playback breaks without it and no
                // user data is exposed by allowing it.
                SitePermissionKind.MEDIA_KEY_SYSTEM ->
                    PermissionDelegate.ContentPermission.VALUE_ALLOW
                // Privacy-sensitive capabilities default to no, quietly:
                // sites must work without unpartitioned third-party
                // storage, VR, or local-network probing.
                SitePermissionKind.STORAGE_ACCESS,
                SitePermissionKind.XR,
                SitePermissionKind.LOCAL_NETWORK,
                -> PermissionDelegate.ContentPermission.VALUE_DENY
                else -> null
            }
            if (silentValue != null) {
                return GeckoResult.fromValue(silentValue)
            }
            val result = GeckoResult<Int>()
            var completed = false
            fun completeOnce(value: Int) {
                if (!completed) {
                    completed = true
                    result.complete(value)
                }
            }
            val emitted = _events.tryEmit(
                BrowserSessionEvent.SitePermissionRequested(
                    sessionId = id,
                    origin = permission.uri.ifBlank { _state.value.url },
                    kind = kind,
                    allow = {
                        completeOnce(
                            PermissionDelegate.ContentPermission.VALUE_ALLOW,
                        )
                    },
                    deny = {
                        completeOnce(
                            PermissionDelegate.ContentPermission.VALUE_DENY,
                        )
                    },
                ),
            )
            if (!emitted) {
                result.complete(PermissionDelegate.ContentPermission.VALUE_DENY)
            }
            return result
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback,
        ) {
            val videoSource = video?.firstOrNull()
            val audioSource = audio?.firstOrNull()
            if (closed || (videoSource == null && audioSource == null)) {
                callback.reject()
                return
            }
            val emitted = _events.tryEmit(
                BrowserSessionEvent.MediaPermissionRequested(
                    sessionId = id,
                    origin = uri.ifBlank { _state.value.url },
                    wantsVideo = videoSource != null,
                    wantsAudio = audioSource != null,
                    videoLabels = video?.map {
                        it.name?.ifBlank { null } ?: it.id
                    }.orEmpty(),
                    audioLabels = audio?.map {
                        it.name?.ifBlank { null } ?: it.id
                    }.orEmpty(),
                    videoFromScreen = video?.any {
                        it.source == PermissionDelegate.MediaSource.SOURCE_SCREEN
                    } == true,
                    wantsMicrophone = audio?.any {
                        it.source == PermissionDelegate.MediaSource.SOURCE_MICROPHONE
                    } == true,
                    grant = {
                        runCatching { callback.grant(videoSource, audioSource) }
                    },
                    reject = { runCatching { callback.reject() } },
                ),
            )
            if (!emitted) callback.reject()
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
        }.getOrNull()?.lowercase() ?: return false
        val nodeDomain = node.domain.trim().lowercase()
        if (nodeDomain.isBlank()) return false
        val nodeHost = runCatching {
            android.net.Uri.parse(
                if ("://" in nodeDomain) nodeDomain else "https://$nodeDomain",
            ).host
        }.getOrNull()?.lowercase() ?: nodeDomain
        // Exact host only. Treating www.example.com and example.com as the
        // same node would let the exact-origin credential gate be bypassed
        // by a same-site-but-different-origin frame/document.
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
        // Exact-origin gate at fill time (scheme + host + port). The
        // ViewModel loads a credential for the page's origin at navigation
        // time, but a focus event can race a navigation; the looser
        // host-only node check below must never let an HTTPS login leak
        // into the same host over HTTP or a different port.
        val expectedOrigin = credential.origin
        if (expectedOrigin != null &&
            CredentialOrigin.fromUrl(_state.value.url) != expectedOrigin
        ) {
            return
        }
        if (focused.hint != Autofill.Hint.USERNAME &&
            focused.hint != Autofill.Hint.EMAIL_ADDRESS &&
            focused.hint != Autofill.Hint.PASSWORD
        ) {
            return
        }
        // Nothing is written into the page here. The owner reported the vault
        // putting a previously saved Gmail address AND password into a Google
        // sign-in form without ever asking; a password must only be filled
        // after an explicit user decision. Offer it instead and let the shell
        // call fillSavedCredentialNow() when the user taps.
        if (savedLoginOffered) return
        val autofillSession = session.getAutofillSession()
        var hasField = false
        fun hasLoginField(node: Autofill.Node) {
            if (hasField) return
            if (nodeMatchesCurrentPage(node) &&
                (node.hint == Autofill.Hint.USERNAME ||
                    node.hint == Autofill.Hint.EMAIL_ADDRESS ||
                    node.hint == Autofill.Hint.PASSWORD)
            ) {
                hasField = true
                return
            }
            node.children.forEach(::hasLoginField)
        }
        hasLoginField(autofillSession.root)
        if (!hasField) return
        savedLoginOffered = true
        _events.tryEmit(
            BrowserSessionEvent.SavedLoginOffered(
                sessionId = id,
                host = CredentialOrigin.fromUrl(_state.value.url)?.substringAfter("://").orEmpty(),
                username = credential.username,
            ),
        )
    }

    /**
     * Fills the login fields the user is looking at, and only then. Called by
     * the shell in response to [BrowserSessionEvent.SavedLoginOffered]; the
     * origin is re-checked here so a navigation between the offer and the tap
     * can never move a password to another site.
     */
    override fun fillSavedCredentialNow(): Boolean {
        if (closed || config.isPrivate || !geckoSession.isOpen) return false
        val credential = autofillCredential ?: return false
        val expectedOrigin = credential.origin
        if (expectedOrigin != null &&
            CredentialOrigin.fromUrl(_state.value.url) != expectedOrigin
        ) {
            return false
        }
        val autofillSession = geckoSession.getAutofillSession()
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
        if (values.size() == 0) return false
        autofillSession.autofill(values)
        return true
    }

    // ---- GeckoView delegates -------------------------------------------------

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> {
            val uri = request.uri
            val scheme = uri.substringBefore(':', missingDelimiterValue = "")
                .lowercase()
            // data: is deliberately not an in-page navigation scheme: a
            // top-level data document can spoof arbitrary trusted UI.
            val webScheme = scheme in setOf(
                "http", "https", "about", "blob", "view-source",
                "moz-extension", "resource", "chrome",
            )
            if (webScheme || scheme.isBlank()) {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
            if (scheme in setOf("file", "data", "javascript", "vbscript")) {
                // Dangerous/local schemes are blocked outright. Forwarding
                // them to Android as ACTION_VIEW could hand a local file or
                // an attacker-controlled document to another app; they are
                // not external links in any useful sense.
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            _events.tryEmit(
                BrowserSessionEvent.ExternalLinkRequested(
                    sessionId = id,
                    uri = uri,
                ),
            )
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

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

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError,
        ): GeckoResult<String>? {
            // NS_BINDING_ABORTED means "this request was superseded", which is
            // what every redirect chain and federated sign-in round-trip looks
            // like from Gecko's side. It is not a failure, and showing the
            // error card with Retry for it is what made Google's sign-in look
            // broken over and over.
            if (error.code == NS_BINDING_ABORTED) return null
            // A redirect chain reports the failure of a navigation that has
            // already been superseded — Google's sign-in is nothing but such a
            // chain, and that is the page the owner saw the card on. The
            // session's current URL is the authority on what is on screen: if
            // it is a real page and it is not the URI that failed, this is a
            // stale error, not a failure worth a card with a Retry.
            val currentUrl = _state.value.url
            val justFinished = System.currentTimeMillis() - lastSuccessfulStopAt <
                STALE_ERROR_WINDOW_MS
            if (justFinished &&
                currentUrl.isNotBlank() &&
                UrlUtils.isHttpUrl(currentUrl) &&
                uri != null &&
                !sameDocument(uri, currentUrl)
            ) {
                AppLog.event(
                    "stale load error ignored code=${error.code}",
                )
                return null
            }
            // Counters only (code/category, never the URL): the bug report the
            // shake gesture prepares must be able to say what failed.
            AppLog.event("load error code=${error.code} category=${error.category}")
            // Recorded WITHOUT the URI (never log or surface full URLs); the
            // numeric code/category is what makes a failure diagnosable from
            // a screenshot alone.
            _state.update {
                it.copy(
                    // The load was halted below, so it is no longer loading.
                    // Leaving isLoading true left the toolbar showing "stop":
                    // the only refresh control on screen did nothing, which is
                    // half of the "refresh does not work" report.
                    isLoading = false,
                    loadFailed = true,
                    loadErrorCode = error.code,
                    loadErrorCategory = error.category,
                )
            }
            // Returning null tells Gecko to halt the load; the shell shows its
            // own localized error card with a working Retry instead of
            // Gecko's English net-error page.
            return null
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            // GeckoView requires the returned session to be *unopened* — it
            // opens and navigates it itself. We only create + register it.
            val sibling = engine.createSessionInternal(
                BrowserSessionConfig(
                    isPrivate = config.isPrivate,
                    // The opener may have been switched between mobile and
                    // desktop after its session was created; inherit the
                    // mode it is actually rendering with, not the stale
                    // creation-time config, so the ViewModel and the new
                    // session cannot disagree about this tab's mode.
                    desktopMode = _state.value.isDesktopMode,
                ),
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

    /**
     * Same document, ignoring the fragment: a redirect that only changes
     * `#hash` is the same page and must not be read as a stale error.
     */
    private fun sameDocument(left: String, right: String): Boolean {
        fun bare(value: String): String = value.substringBefore('#').trimEnd('/')
        return bare(left) == bare(right)
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onSessionStateChange(
            session: GeckoSession,
            sessionState: GeckoSession.SessionState,
        ) {
            // Continuously kept so the tab can be suspended at any moment.
            // Gecko hands us a snapshot copy; serialization is deferred to
            // latestSavedState() instead of running on every callback.
            latestSessionState = sessionState
        }

        override fun onPageStart(session: GeckoSession, url: String) {
            // Per-site zoom must be in effect before this document lays out,
            // but the factor is process-wide: a background tab must not
            // overwrite the zoom of the tab the user is looking at (that is
            // the "zoom jumps to 100% for a moment" report). When a tab
            // becomes visible, its own factor is applied.
            if (engine.isActiveSession(this@GeckoBrowserSession)) engine.applySiteZoom(url)
            rootScrollY = null
            // A new document invalidates any previous Find result; the UI
            // re-issues the visible query once loading finishes.
            latestFindQuery = ""
            savedLoginOffered = false
            _state.update {
                it.copy(
                    url = url,
                    isLoading = true,
                    loadFailed = false,
                    loadErrorCode = 0,
                    loadErrorCategory = 0,
                    progress = 0f,
                    findResult = FindResultUi(),
                )
            }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (success) lastSuccessfulStopAt = System.currentTimeMillis()
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
            _state.update {
                it.copy(
                    isSecure = securityInfo.isSecure,
                    isSecurityException = securityInfo.isException,
                    hasMixedContent =
                        securityInfo.mixedModeActive ==
                            GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED ||
                            securityInfo.mixedModePassive ==
                            GeckoSession.ProgressDelegate.SecurityInformation.CONTENT_LOADED,
                    certificateHost = securityInfo.host,
                    certificateSubject =
                        securityInfo.certificate
                            ?.subjectX500Principal
                            ?.name
                            .orEmpty(),
                    certificateIssuer =
                        securityInfo.certificate
                            ?.issuerX500Principal
                            ?.name
                            .orEmpty(),
                )
            }
        }
    }

    private val scrollDelegate = object : GeckoSession.ScrollDelegate {
        override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
            rootScrollY = scrollY
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onFirstComposite(session: GeckoSession) {
            hasPainted = true
            painted = true
            frameCount++
            engine.notifyFirstComposite(session)
        }

        override fun onPaintStatusReset(session: GeckoSession) {
            // Gecko's own words: the painted content of this tab is no longer
            // displayed. The next frame decides whether the compositor
            // recovered by itself.
            resetCount++
            painted = false
            engine.notifyPaintStatusReset(session)
        }

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
            closedUnexpectedly = true
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
        geckoSession.setPermissionDelegate(permissionDelegate)
        geckoSession.setPrintDelegate(printDelegate)
        geckoSession.setNavigationDelegate(navigationDelegate)
        geckoSession.setProgressDelegate(progressDelegate)
        geckoSession.setContentDelegate(contentDelegate)
        geckoSession.setScrollDelegate(scrollDelegate)
        geckoSession.setAutofillDelegate(autofillDelegate)
    }

    private companion object {
        const val TAG = "GeckoBrowserSession"
        const val MAX_THUMBNAIL_WIDTH = 480

        /**
         * A load error that arrives this soon after a *successful* page stop,
         * for a different URI than the one on screen, belongs to a redirect
         * that already lost the race (Google sign-in is a chain of them).
         * It lives in this class's companion because that is where the
         * `onLoadError` that reads it lives — the previous placement in the
         * engine's companion is what made 0.5.8 fail to compile.
         */
        const val STALE_ERROR_WINDOW_MS = 1500L

        /** HTTP header names are case-insensitive. */
        fun Map<String, String>.headerValue(name: String): String? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}
