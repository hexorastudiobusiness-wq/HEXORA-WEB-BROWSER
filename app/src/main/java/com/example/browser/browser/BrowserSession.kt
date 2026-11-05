package com.example.browser.browser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import java.io.InputStream
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
    /** True when the user previously accepted a certificate exception. */
    val isSecurityException: Boolean = false,
    /** True when active/passive mixed content was loaded on an HTTPS page. */
    val hasMixedContent: Boolean = false,
    /** Host covered by the current TLS identity, when known. */
    val certificateHost: String = "",
    /** X.509 subject / issuer summaries for the connection dialog. */
    val certificateSubject: String = "",
    val certificateIssuer: String = "",
    val loadFailed: Boolean = false,
    /**
     * Gecko error code/category for the failed load, when the engine reported
     * one (`WebRequestError`). 0 means "failure without a specific error",
     * e.g. a cancelled or superseded navigation. Shown to the user so a
     * screenshot is enough to diagnose a failure.
     */
    val loadErrorCode: Int = 0,
    val loadErrorCategory: Int = 0,
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
 * history, scroll position and form data). The ViewModel may persist it in
 * private app storage for non-private tabs so a cold start can restore the
 * tab; it is never created for private tabs.
 */
class SavedTabState internal constructor(internal val serialized: String)

/** A locally saved login offered to a page by the in-app password vault. */
data class AutofillCredential(
    val username: String,
    val password: String,
    /**
     * Exact origin this credential belongs to. The engine must re-check it
     * at fill time: a credential loaded for one origin must never be filled
     * after the same session has navigated to another scheme/host/port.
     */
    val origin: String? = null,
)

/** Kind of media found under a long press, if any. */
enum class PageMediaKind { NONE, IMAGE, VIDEO, AUDIO }

/** Web permissions that require an explicit in-browser decision. */
enum class SitePermissionKind {
    GEOLOCATION,
    NOTIFICATIONS,
    CAMERA,
    MICROPHONE,
    CAMERA_AND_MICROPHONE,
    SCREEN_SHARE,
    PERSISTENT_STORAGE,
    AUTOPLAY,
    MEDIA_KEY_SYSTEM,
    XR,
    STORAGE_ACCESS,
    LOCAL_NETWORK,
    UNKNOWN,
}

/** Stored state of one site permission (mirrors the engine's values). */
enum class SitePermissionState { ALLOW, DENY, PROMPT }

/** A stored site permission shown in the site-permissions manager. */
data class SitePermissionEntry(
    val uri: String,
    val kind: SitePermissionKind,
    val state: SitePermissionState,
    val isPrivate: Boolean = false,
    /** Raw engine permission type; distinguishes autoplay variants. */
    val permissionType: Int = -1,
) {
    val allowed: Boolean get() = state == SitePermissionState.ALLOW
}

/** Decision for a stored site permission. */
enum class SitePermissionDecision { ASK, ALLOW, DENY }

/** Standard JavaScript / Web Share / navigation prompts shown by the shell. */
enum class WebPromptKind {
    ALERT,
    CONFIRM,
    TEXT,
    BEFORE_UNLOAD,
    REPOST_CONFIRM,
    FOLDER_UPLOAD,
    POPUP,
    SHARE,
    CHOICE,
    COLOR,
    DATE_TIME,
    AUTH,
}

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
        /** Page that initiated the download; used only as a referer hint. */
        val pageUrl: String? = null,
        /** Total body size in bytes when the engine reported one, else -1. */
        val contentLength: Long = -1L,
        /**
         * Opens the response body the engine already received, when it handed
         * one over. Consuming this stream is the only way to download
         * blob/data/POST/authenticated responses: they cannot be replayed as
         * a fresh GET. The stream may be opened at most once and must be
         * closed by the consumer. Null means "re-fetch [url] instead".
         */
        val bodySource: (() -> InputStream?)? = null,
    ) : BrowserSessionEvent

    /**
     * The session's content process crashed or was killed by the system
     * (Gecko `onCrash`/`onKill`). The session is closed and unusable; the
     * tab itself must survive and be restored from its saved state, the way
     * Firefox's crash handling recreates a dead tab's engine session.
     */
    data class SessionDied(
        override val sessionId: String,
    ) : BrowserSessionEvent

    /** The page requested a file picker (for example an upload input). */
    data class FilePickerRequested(
        override val sessionId: String,
        val requestId: String,
        val allowMultiple: Boolean,
        val isFolder: Boolean,
        val mimeTypes: List<String>,
        /** Gecko capture mode: 0 none, 1 any camera, 2 front, 3 rear. */
        val captureMode: Int = 0,
    ) : BrowserSessionEvent

    /** A site asked for a content permission (location, notifications, …). */
    data class SitePermissionRequested(
        override val sessionId: String,
        val origin: String,
        val kind: SitePermissionKind,
        val allow: () -> Unit,
        val deny: () -> Unit,
    ) : BrowserSessionEvent

    /** A site asked to capture camera and/or microphone media. */
    data class MediaPermissionRequested(
        override val sessionId: String,
        val origin: String,
        val wantsVideo: Boolean,
        val wantsAudio: Boolean,
        val videoLabels: List<String>,
        val audioLabels: List<String>,
        /** True when Gecko classified a video source as screen/window. */
        val videoFromScreen: Boolean = false,
        /** True when an audio source is a microphone (not system audio). */
        val wantsMicrophone: Boolean = false,
        val grant: () -> Unit,
        val reject: () -> Unit,
    ) : BrowserSessionEvent

    /** Gecko needs Android runtime permissions before a device can be used. */
    data class AndroidPermissionRequested(
        override val sessionId: String,
        val permissions: List<String>,
        val grant: () -> Unit,
        val deny: () -> Unit,
    ) : BrowserSessionEvent

    /** A JavaScript/Web Share/navigation prompt needs a shell dialog. */
    data class WebPromptRequested(
        override val sessionId: String,
        val kind: WebPromptKind,
        val title: String?,
        val message: String?,
        val defaultValue: String?,
        /**
         * Confirm with optional text (text prompts / share result). CHOICE
         * prompts also pass selected option *indices*: option ids are
         * page-controlled strings and must not be packed into a delimited
         * string, where commas or spaces inside an id would corrupt them.
         */
        val confirm: (String?, List<Int>) -> Unit,
        /** Dismiss/cancel the prompt. */
        val dismiss: () -> Unit,
        /** Labels for CHOICE/COLOR prompts, aligned with [optionIds]. */
        val options: List<String> = emptyList(),
        val optionIds: List<String> = emptyList(),
        val optionEnabled: List<Boolean> = emptyList(),
        val optionSelected: List<Boolean> = emptyList(),
        val allowMultiple: Boolean = false,
        /** AUTH prompts that ask only for a password. */
        val passwordOnly: Boolean = false,
    ) : BrowserSessionEvent

    /** The user long-pressed a link or media element on the page. */
    data class ContextMenuRequested(
        override val sessionId: String,
        val linkUri: String?,
        val mediaUri: String?,
        val mediaKind: PageMediaKind,
        val label: String?,
    ) : BrowserSessionEvent

    /**
     * A login field was focused and the password vault has a saved login for
     * the current page. Nothing has been filled: the user decides, and the
     * shell calls [BrowserSession.fillSavedCredentialNow] only on an explicit
     * tap.
     */
    data class SavedLoginOffered(
        override val sessionId: String,
        val host: String,
        val username: String,
    ) : BrowserSessionEvent

    /** A login form was submitted; the app may ask whether to save it. */
    data class CredentialSubmitted(
        override val sessionId: String,
        val pageUrl: String,
        val username: String,
        val password: String,
    ) : BrowserSessionEvent

    /** A page tried to open a non-web URI in an external application. */
    data class ExternalLinkRequested(
        override val sessionId: String,
        val uri: String,
    ) : BrowserSessionEvent

    /** The page itself asked to print (window.print()); caller owns stream. */
    data class PrintRequested(
        override val sessionId: String,
        val bodySource: InputStream,
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

    /**
     * True while the underlying engine session is open and can render/load.
     * A session whose content process was killed in the background reports
     * false here even when no crash event was delivered, so the tab layer
     * can recreate it instead of binding a dead session.
     */
    fun isUsable(): Boolean

    /**
     * Switches desktop/mobile rendering for this tab.
     *
     * [reload] must be false when the caller is about to navigate anyway:
     * reloading the old document first was a wasted load (and a visible
     * flash) on every link that opened in a site with a stored mode.
     */
    fun setDesktopMode(enabled: Boolean, reload: Boolean = true)

    /** Finds [query] in the page; [forward] = false searches backwards. */
    fun findText(query: String, forward: Boolean)

    /** Clears the current in-page search highlights. */
    fun clearFind()

    /** Delivers files selected in the system picker to a pending prompt. */
    fun completeFilePicker(requestId: String, uris: List<Uri>)

    /** Cancels a pending system file-picker prompt. */
    fun cancelFilePicker(requestId: String)

    /** Captures a small snapshot of the visible page for tab previews. */
    fun captureThumbnail(onCaptured: (Bitmap?) -> Unit)

    /** Asks the page to leave HTML fullscreen (no-op when not fullscreen). */
    fun exitFullscreen()

    /** Generates a PDF of the current page; the caller owns the stream. */
    fun saveAsPdf(onResult: (InputStream?) -> Unit)

    /** Generates the current page PDF for Android printing. */
    fun requestPrint(onResult: (InputStream?) -> Unit)

    fun loadUrl(url: String)
    fun reload()
    fun stopLoading()
    fun goBack()
    fun goForward()

    /**
     * Marks this session high priority (Gecko should keep its content
     * process alive as long as possible) or returns it to default
     * priority. Only the selected tab is high priority at any time,
     * mirroring Mozilla's SessionPrioritizationMiddleware; GeckoView
     * itself manages active/inactive from the surface.
     */
    fun setPriorityHigh(high: Boolean)

    /**
     * Returns the app-wide content container for [context]: a plain view group
     * that holds one surface per tab. Typed as a plain [View] so no engine type
     * leaks into the UI layer.
     */
    fun contentContainer(context: Context): View

    /**
     * Shows this session's own surface inside [container] and hides the others.
     * Every tab owns its GeckoView (Mozilla's model), so this never re-binds
     * another tab's page onto a shared view.
     */
    fun presentContentView(container: View)

    /**
     * Enables or disables touch delivery to this tab's web surface. While an
     * overlay covers the content (Start Page, crash card, error card) the
     * native surface must not receive the gesture at all: a real View would
     * consume the MotionEvent before the Compose overlay drawn above it ever
     * sees it, which is what made Start Page buttons look dead.
     */
    fun setContentTouchEnabled(enabled: Boolean)

    /** Supplies (or clears) the locally saved login to offer on this page. */
    fun setAutofillCredential(credential: AutofillCredential?)

    /**
     * Fills the page's current login fields with the credential installed by
     * [setAutofillCredential]. Never called automatically: filling a password
     * without the user asking is exactly what this API exists to prevent.
     * Returns false when there is nothing to fill.
     */
    fun fillSavedCredentialNow(): Boolean

    /** Releases the session and its view. The session cannot be reused afterwards. */
    fun close()
}
