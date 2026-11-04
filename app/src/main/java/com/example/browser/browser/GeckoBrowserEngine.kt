package com.example.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingChild
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
import org.mozilla.geckoview.WebResponse
import com.example.browser.download.DownloadFileName
import com.example.browser.util.CredentialOrigin

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
                // Firefox-style Total Cookie Protection: partition
                // third-party cookie storage instead of sharing one jar.
                created.settings.setCookieBehaviorOptInPartitioning(true)
                created.settings.setCookieBehaviorOptInPartitioningPBM(true)
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
        sharedContentHost?.updateRefreshChrome(isNightTheme())
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

    // One long-lived content surface per Activity, exactly like Mozilla's
    // GeckoEngineView: a single GeckoView inside a single host, never moved,
    // never re-parented per tab. Tabs are switched by replacing the session
    // bound to that view. Detaching or re-creating the surface per tab (or per
    // resume) is what used to leave pages black, and re-binding a session to
    // a surface that had been thrown away is what made returning tabs reload.
    private var sharedContentView: HexoraGeckoView? = null
    private var sharedContentHost: SwipeableHostLayout? = null
    private var renderedSession: GeckoBrowserSession? = null
    /** Session to re-attach automatically when the shared view returns. */
    private var sessionToRestoreOnAttach: GeckoBrowserSession? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guard for the "did Gecko actually paint after we came back?" check. */
    private var resumeCheckToken = 0L
    private var paintedSinceResume = true

    internal fun obtainContentView(
        session: GeckoBrowserSession,
        context: Context,
    ): View {
        val host = sharedContentHost?.takeIf { it.context === context }
            ?: createContentHost(context)
        // A fresh AndroidView node asking for a host means the previous node
        // is gone; detach the host from any stale parent first so Compose can
        // never hit "child already has a parent".
        if (host.parent != null) {
            (host.parent as? ViewGroup)?.removeView(host)
        }
        bindContentView(session, host)
        return host
    }

    private fun createContentHost(context: Context): SwipeableHostLayout {
        // A different Activity context (Activity recreation) invalidates every
        // view bound to the old one; drop them without touching sessions.
        sharedContentView?.let { oldView ->
            oldView.onViewAttached = null
            oldView.onViewDetached = null
            oldView.releaseSession()
            (oldView.parent as? ViewGroup)?.removeView(oldView)
        }
        sharedContentHost?.let { oldHost ->
            (oldHost.parent as? ViewGroup)?.removeView(oldHost)
        }
        renderedSession?.contentView = null
        renderedSession = null
        sessionToRestoreOnAttach = null
        sharedContentView = null
        sharedContentHost = null

        val host = SwipeableHostLayout(context)
        host.swipeEnabledProvider = { tabSwipeEnabled }
        host.onSwipeTab = { delta -> onTabSwipe?.invoke(delta) }
        // Gecko knows whether the page can still scroll towards its top, so
        // pull-to-refresh starts exactly when a mainstream browser would.
        host.canScrollUpProvider = { (renderedSession?.rootScrollY ?: 0) > 0 }
        host.onRefreshRequested = { onPullToRefresh?.invoke() }
        host.updateRefreshChrome(isNightTheme())
        // NOTE: no `coverUntilFirstPaint` here on purpose.
        //
        // That call is not just a background colour: it stores the colour as
        // the GeckoView's "last cover colour" and pushes it to the session
        // compositor as its clear colour, and it paints an OPAQUE background
        // on the SurfaceView wrapper. The ONLY thing that removes that opaque
        // background is GeckoView's internal FIRST_PAINT callback, which fires
        // "for the first content composite after a start or a restart of the
        // compositor". Our single shared view keeps the same compositor alive
        // across tab switches, resumes and reloads, so a cover applied at the
        // wrong moment could sit on top of a perfectly painted page and never
        // be removed: a black tab that survives reloads and tab switches and
        // only goes away when the whole view is recreated (a new view always
        // restarts the compositor). Gecko has its own sane default background,
        // so the cover is not worth that risk.
        val view = HexoraGeckoView(context).also { newView ->
            newView.onViewDetached = { handleSharedViewDetached(it) }
            newView.onViewAttached = { handleSharedViewAttached(it) }
        }
        host.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        sharedContentView = view
        sharedContentHost = host
        return host
    }

    internal fun bindContentView(session: GeckoBrowserSession, view: View) {
        val geckoView = sharedContentView ?: return
        val host = sharedContentHost ?: return
        // Only the engine-owned host is ever rendered; Compose hands back the
        // same instance for the whole Activity.
        if (view !== host) return
        if (renderedSession !== session) {
            renderedSession?.contentView = null
        }
        if (geckoView.session !== session.geckoSession) {
            // Hexora provides its own offline credential vault through the
            // session Autofill.Delegate. Disable GeckoView's Android Autofill
            // bridge so it does not replace that delegate.
            geckoView.setAutofillEnabled(false)
            // Release explicitly, like Mozilla's GeckoEngineView: GeckoView
            // would otherwise close the incoming/replaced session.
            geckoView.session?.let { geckoView.releaseSession() }
            // setSession() throws for a closed session; reopen defensively
            // (e.g. after a content-process crash) instead of crashing.
            if (!session.geckoSession.isOpen) session.ensureOpen()
            geckoView.setSession(session.geckoSession)
            session.installAutofillDelegate()
            paintedSinceResume = false
        }
        renderedSession = session
        sessionToRestoreOnAttach = session
        session.contentView = geckoView
    }

    /**
     * Called by [HexoraGeckoView] *before* GeckoView's own detach handling.
     * The subclass releases the session itself (otherwise GeckoView closes
     * it); here we only forget the view binding and remember which session
     * must be rendered again when the view comes back. The session itself
     * stays open and loaded: no reload, no state loss.
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
        val host = (view.parent as? SwipeableHostLayout) ?: sharedContentHost ?: return
        sharedContentHost = host
        val session = sessionToRestoreOnAttach ?: return
        if (view.session === session.geckoSession) return
        bindContentView(session, host)
    }

    internal fun releaseContentView(session: GeckoBrowserSession, view: View) {
        val geckoView = sharedContentView ?: return
        if (view !== sharedContentHost && view !== geckoView) return
        // The host belongs to the engine and is reused when the Compose node
        // comes back. Only detach the session, so neither Gecko nor the host
        // is thrown away while a tab still exists.
        if (geckoView.session === session.geckoSession) {
            geckoView.releaseSession()
        }
        if (renderedSession === session) renderedSession = null
        if (sessionToRestoreOnAttach === session) sessionToRestoreOnAttach = null
        if (session.contentView === geckoView) session.contentView = null
    }

    /** Gecko told us the painted content is no longer displayed. */
    internal fun notifyPaintStatusReset(session: GeckoSession) {
        if (renderedSession?.geckoSession !== session) return
        paintedSinceResume = false
    }

    /** Gecko produced a frame: the surface is definitely alive. */
    internal fun notifyFirstComposite(session: GeckoSession) {
        if (renderedSession?.geckoSession !== session) return
        paintedSinceResume = true
    }

    /**
     * Called when the app comes back to the foreground (after the window has a
     * surface again). If Gecko does not report a frame shortly after, the
     * display is re-acquired exactly the way re-entering the browser does —
     * the recovery the owner confirmed works. Without this, a surface that
     * came back without repainting stayed black no matter what the page did.
     */
    override fun ensureContentDisplayed() {
        val geckoView = sharedContentView ?: return
        val session = renderedSession ?: return
        val token = ++resumeCheckToken
        paintedSinceResume = false
        mainHandler.postDelayed(
            {
                if (token != resumeCheckToken || paintedSinceResume) return@postDelayed
                if (renderedSession !== session) return@postDelayed
                if (!ViewCompat.isAttachedToWindow(geckoView)) return@postDelayed
                // Restart the display. No reload, no session loss.
                if (geckoView.session != null) geckoView.releaseSession()
                if (!session.geckoSession.isOpen) session.ensureOpen()
                geckoView.setSession(session.geckoSession)
                session.installAutofillDelegate()
                paintedSinceResume = false
            },
            RESUME_PAINT_GRACE_MS,
        )
    }

    override fun finishPullToRefresh() {
        sharedContentHost?.finishRefresh()
    }

    private fun isNightTheme(): Boolean = when (preferredColorScheme) {
        GeckoRuntimeSettings.COLOR_SCHEME_DARK -> true
        GeckoRuntimeSettings.COLOR_SCHEME_LIGHT -> false
        else ->
            (appContext.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
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
        private val RUNTIME_LOCK = Any()

        @Volatile
        private var processRuntime: GeckoRuntime? = null

        /**
         * Grace period after returning to the foreground before the display is
         * re-acquired because Gecko reported no frame at all.
         */
        private const val RESUME_PAINT_GRACE_MS = 1_200L
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
 * Two jobs, both at the view level because touch events over the web surface
 * never reach Compose:
 *
 * 1. **Pull to refresh.** [SwipeRefreshLayout] provides the standard Android
 *    gesture and indicator, exactly the way it is used around GeckoView by
 *    Mozilla's own browser. Whether a pull may start is answered by Gecko
 *    itself (see [HexoraGeckoView]): the child reports "I can scroll up" via
 *    [canScrollUpProvider], so a pull only starts when the page is really at
 *    its top — no page-specific heuristics.
 * 2. **Fullscreen tab swipes.** A clearly horizontal, quick drag switches
 *    tabs while the browser chrome is hidden.
 */
internal class SwipeableHostLayout(context: Context) : SwipeRefreshLayout(context) {

    /** +1 = swiped left (next tab), -1 = swiped right (previous tab). */
    var onSwipeTab: ((Int) -> Unit)? = null

    /** Consulted on every gesture start; swipes are ignored while false. */
    var swipeEnabledProvider: () -> Boolean = { false }

    /** True while the web content can still scroll towards its top. */
    var canScrollUpProvider: () -> Boolean = { false }

    /** Called when the user releases a valid pull-to-refresh gesture. */
    var onRefreshRequested: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var swiping = false
    private var fired = false

    private val refreshSafety = Runnable { if (isRefreshing) isRefreshing = false }

    init {
        // The child answers "may a pull start?" through Gecko's own scroll
        // state; without this, SwipeRefreshLayout asks the SurfaceView, which
        // knows nothing about the page.
        setOnChildScrollUpCallback { _, _ -> canScrollUpProvider() }
        setOnRefreshListener {
            // Never leave the spinner running if the page never reports a
            // start/stop (failed reload, killed session, ...).
            postDelayed(refreshSafety, REFRESH_SAFETY_MS)
            onRefreshRequested?.invoke()
        }
        setColorSchemeColors(0xFFE77E3D.toInt())
        setProgressBackgroundColorSchemeColor(Color.WHITE)
        isNestedScrollingEnabled = true
    }

    fun updateRefreshChrome(dark: Boolean) {
        setProgressBackgroundColorSchemeColor(if (dark) REFRESH_DARK else Color.WHITE)
        setColorSchemeColors(if (dark) 0xFFE77E3D.toInt() else 0xFF8A4415.toInt())
    }

    /** Ends the spinner; called when the engine reports the load finished. */
    fun finishRefresh() {
        removeCallbacks(refreshSafety)
        if (isRefreshing) isRefreshing = false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                swiping = false
                fired = false
            }
            MotionEvent.ACTION_MOVE -> {
                // A tab swipe must be a quick, deliberate flick — and it is
                // never active while the keyboard is up. Slow, careful drags
                // (moving the text cursor or a selection handle while
                // typing) belong to the page and must not switch tabs.
                if (!swiping && swipeEnabledProvider() && !isKeyboardVisible()) {
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swiping = false
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
        const val REFRESH_SAFETY_MS = 8_000L

        /** Matches the app's Hexora/dark background. */
        val REFRESH_DARK = 0xFF100B0F.toInt()
    }
}

/**
 * The one shared content view.
 *
 * Two behaviours matter here and both follow Mozilla's `NestedGeckoView` /
 * `GeckoEngineView`:
 *
 * 1. **The session survives a detach.** A plain [GeckoView] closes its session
 *    in `onDetachedFromWindow()`; releasing it first keeps the page alive so
 *    returning to it is a repaint, not a reload.
 * 2. **Nested scrolling.** The view is a [NestedScrollingChild], so a parent
 *    [SwipeRefreshLayout] can own pull-to-refresh. Gecko's own APZ answer
 *    decides whether a gesture may leave the page (content already at the
 *    top) or must stay inside it (the page is scrolling, or the site consumed
 *    the touch). The previous custom implementation guessed with its own
 *    thresholds, which is why it worked on some sites and not on others.
 */
@SuppressLint("ClickableViewAccessibility")
internal class HexoraGeckoView(context: Context) : GeckoView(context), NestedScrollingChild {

    var onViewAttached: ((HexoraGeckoView) -> Unit)? = null
    var onViewDetached: ((HexoraGeckoView) -> Unit)? = null

    private val childHelper = NestedScrollingChildHelper(this)
    private val scrollOffset = IntArray(2)
    private val scrollConsumed = IntArray(2)

    private var lastY = 0
    private var initialDownY = 0f
    private var nestedOffsetY = 0

    /**
     * True while a gesture may still be taken over by the parent. It starts
     * true, is confirmed by Gecko on DOWN (page already at its top), and is
     * cleared for good once the page scrolls down inside this gesture.
     */
    private var gestureCanReachParent = true

    init {
        isNestedScrollingEnabled = true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // A copy is used because the event is offset while nested scrolling.
        val event = MotionEvent.obtain(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val eventY = event.y.toInt()
                var deltaY = lastY - eventY

                if (dispatchNestedPreScroll(0, deltaY, scrollConsumed, scrollOffset)) {
                    deltaY -= scrollConsumed[1]
                    event.offsetLocation(0f, (-scrollOffset[1]).toFloat())
                    nestedOffsetY += scrollOffset[1]
                }
                lastY = eventY - scrollOffset[1]

                if (dispatchNestedScroll(0, scrollOffset[1], 0, deltaY, scrollOffset)) {
                    lastY -= scrollOffset[1]
                    event.offsetLocation(0f, scrollOffset[1].toFloat())
                    nestedOffsetY += scrollOffset[1]
                }

                // The first drag move (and any move while the gesture may
                // still reach the parent) is dispatched through Gecko's
                // detail query so the answer about who owns it arrives with
                // the event; later moves go straight to Gecko, once.
                if (gestureCanReachParent && event.y != initialDownY) {
                    askInputResult(event)
                    event.recycle()
                    return true
                }
            }

            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                nestedOffsetY = 0
                lastY = event.y.toInt()
                initialDownY = event.y
                askInputResult(event)
                event.recycle()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopNestedScroll()
                // Let the next gesture reach the parent again.
                parent?.requestDisallowInterceptTouchEvent(false)
                gestureCanReachParent = true
            }
        }
        val handled = super.onTouchEvent(event)
        event.recycle()
        return handled
    }

    /**
     * Asks Gecko who should own the current gesture. The answer is async, so
     * it is only used to gate interception; the event itself has already been
     * forwarded to Gecko by [onTouchEventForDetailResult].
     */
    private fun askInputResult(event: MotionEvent) {
        val action = event.actionMasked
        val eventY = event.y
        onTouchEventForDetailResult(event).accept(
            { detail ->
                if (!gestureCanReachParent) return@accept
                val handled = detail?.handledResult() ?: PanZoomController.INPUT_RESULT_UNHANDLED
                val atTop = detail != null &&
                    detail.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP == 0
                val handledBySite = handled == PanZoomController.INPUT_RESULT_HANDLED_CONTENT
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        gestureCanReachParent = atTop
                        if (gestureCanReachParent &&
                            handled != PanZoomController.INPUT_RESULT_HANDLED_CONTENT
                        ) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        when {
                            // Pulling down: the parent may take over unless the
                            // site itself is using the touch (drag handles,
                            // carousels, map panes...).
                            initialDownY < eventY -> {
                                if (!handledBySite) {
                                    parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            // The page is scrolling towards its content: the
                            // gesture belongs to the page from now on.
                            initialDownY > eventY -> {
                                parent?.requestDisallowInterceptTouchEvent(true)
                                gestureCanReachParent = false
                            }
                            else -> parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL)
            },
            { /* No answer: keep whatever the DOWN event decided. */ },
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onViewAttached?.invoke(this)
    }

    override fun onDetachedFromWindow() {
        // Release first: prevents GeckoView from closing the session.
        if (session != null) releaseSession()
        onViewDetached?.invoke(this)
        super.onDetachedFromWindow()
    }

    // ---- NestedScrollingChild ------------------------------------------------

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled(): Boolean = childHelper.isNestedScrollingEnabled

    override fun startNestedScroll(axes: Int): Boolean = childHelper.startNestedScroll(axes)

    override fun stopNestedScroll() {
        childHelper.stopNestedScroll()
    }

    override fun hasNestedScrollingParent(): Boolean = childHelper.hasNestedScrollingParent()

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed,
        dyConsumed,
        dxUnconsumed,
        dyUnconsumed,
        offsetInWindow,
    )

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
    ): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedFling(
        velocityX: Float,
        velocityY: Float,
        consumed: Boolean,
    ): Boolean = childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float): Boolean =
        childHelper.dispatchNestedPreFling(velocityX, velocityY)
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

    internal var contentView: GeckoView? = null

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
            rootScrollY = null
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
            engine.notifyFirstComposite(session)
        }

        override fun onPaintStatusReset(session: GeckoSession) {
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

        /** HTTP header names are case-insensitive. */
        fun Map<String, String>.headerValue(name: String): String? =
            entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}
