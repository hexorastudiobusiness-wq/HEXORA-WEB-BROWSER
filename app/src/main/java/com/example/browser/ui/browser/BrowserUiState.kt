package com.example.browser.ui.browser

import android.graphics.Bitmap
import com.example.browser.browser.BrowserSession
import com.example.browser.browser.FindResultUi
import com.example.browser.browser.PageMediaKind
import com.example.browser.browser.SitePermissionKind
import com.example.browser.browser.WebPromptKind

/** UI-ready representation of one open tab (ARCHITECTURE.md §14). */
data class TabUiItem(
    val id: String,
    val title: String,
    val url: String,
    val isPrivate: Boolean,
    val isActive: Boolean,
    /** Suspended tabs keep state + thumbnail but hold no live engine session. */
    val isSuspended: Boolean = false,
    /** Not selected for a long time; shown in the inactive-tabs section. */
    val isInactive: Boolean = false,
    /** Last selection time, used to refresh inactive grouping in the UI. */
    val lastAccessMs: Long = 0L,
)

/** One user-created shortcut shown on the Hexora Start Page. */
data class StartShortcut(
    val id: Long,
    val title: String,
    val url: String,
)

/** A pending "save this login?" prompt. The password never enters UI state. */
data class CredentialSavePrompt(
    val origin: String,
    val username: String,
    val isUpdate: Boolean,
)

/** One history/bookmark/open-tab entry offered while typing an address. */
data class AddressSuggestion(
    val title: String,
    val url: String,
    val isBookmark: Boolean = false,
    /** Set when selecting this suggestion should switch to an open tab. */
    val tabId: String? = null,
)

/** A closed tab that can be restored individually from Recently Closed. */
data class RecentlyClosedTabUi(
    val id: String,
    val title: String,
    val url: String,
)

/** A site permission waiting for Allow/Deny in the browser shell. */
data class SitePermissionRequestUi(
    val id: String,
    val origin: String,
    val kind: SitePermissionKind,
    val wantsVideo: Boolean = false,
    val wantsAudio: Boolean = false,
    /** The audio side needs Android's microphone runtime permission. */
    val wantsMicrophone: Boolean = false,
)

/** A JavaScript/Web Share/navigation prompt waiting for the user. */
data class WebPromptUi(
    val id: String,
    val kind: WebPromptKind,
    val title: String?,
    val message: String?,
    val defaultValue: String?,
    val options: List<String> = emptyList(),
    val optionIds: List<String> = emptyList(),
    val optionEnabled: List<Boolean> = emptyList(),
    val optionSelected: List<Boolean> = emptyList(),
    val allowMultiple: Boolean = false,
    val passwordOnly: Boolean = false,
)

/** Gecko is waiting for Android runtime permissions to be decided. */
data class AndroidPermissionRequestUi(
    val id: String,
    val permissions: List<String>,
)

/** A link/media long-press menu requested by the current page. */
data class PageContextMenu(
    val title: String = "",
    val linkUri: String? = null,
    val mediaUri: String? = null,
    val mediaKind: PageMediaKind = PageMediaKind.NONE,
)

/** Everything [BrowserScreen] needs to render. Produced by [BrowserViewModel]. */
data class BrowserUiState(
    val tabs: List<TabUiItem> = emptyList(),
    val activeTabId: String? = null,
    val currentUrl: String = "",
    val pageTitle: String = "",
    val isLoading: Boolean = false,
    /** 0f..1f */
    val progress: Float = 0f,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** True when system Back should close a page-opened tab to its opener. */
    val canBackCloseTab: Boolean = false,
    val isSecure: Boolean = false,
    val isSecurityException: Boolean = false,
    val hasMixedContent: Boolean = false,
    val certificateHost: String = "",
    val certificateSubject: String = "",
    val certificateIssuer: String = "",
    val isPrivate: Boolean = false,
    val loadFailed: Boolean = false,
    /** Gecko's error code/category for [loadFailed]; 0 when unknown. */
    val loadErrorCode: Int = 0,
    val loadErrorCategory: Int = 0,
    /**
     * The active tab's page crashed (Firefox-style crashed tab): show the
     * restore card instead of the page until the user restores it.
     */
    val activeTabCrashed: Boolean = false,
    val isBookmarked: Boolean = false,
    val isDesktopMode: Boolean = false,
    val isFullscreen: Boolean = false,
    val findVisible: Boolean = false,
    val findResult: FindResultUi = FindResultUi(),
    /** Current text zoom (50..200), mirrors the persisted setting. */
    /** Number of tabs available for "undo close all" (0 = nothing to undo). */
    val recentlyClosedCount: Int = 0,
    /** tabId -> counter bumped whenever a fresh thumbnail is stored. */
    val thumbnailVersions: Map<String, Int> = emptyMap(),
    /** Engine bootstrap failure (e.g. GeckoView could not start). */
    val errorMessage: String? = null,
    /** Engine session of the active tab; used only to create its native view. */
    val activeSession: BrowserSession? = null,
    /** True when the Chrome-like Hexora Start Page covers the web content. */
    val showStartPage: Boolean = true,
    /** True when a real web page exists underneath the Start Page. */
    val hasWebPage: Boolean = false,
    val startShortcuts: List<StartShortcut> = emptyList(),
    /** True when another user-created Start Page shortcut may be added. */
    val canAddStartShortcut: Boolean = false,
    /** Cached site icons by shortcut id; absent ids use the letter placeholder. */
    val startShortcutIcons: Map<Long, Bitmap> = emptyMap(),
    val pendingCredentialSave: CredentialSavePrompt? = null,
    /** A saved login the user may choose to fill on the current page. */
    val savedLoginSuggestion: SavedLoginSuggestion? = null,
    /** Pull-to-refresh drag progress (0..1.5) for the in-app indicator. */
    val pullRefreshProgress: Float = 0f,
    /** Floating per-site zoom panel (vertical slider) visibility. */
    val zoomPanelVisible: Boolean = false,
    /** Zoom of the site in front of the user, in percent. */
    val zoomPercent: Int = 100,
    /** Whether the diagnostics dialog is open (overflow menu). */
    val diagnosticsVisible: Boolean = false,
    /** Counters describing the content surface; no URLs, no titles. */
    val diagnosticsText: String = "",
    /** Long-press menu for a link/image/video/audio on the page, if open. */
    val pageContextMenu: PageContextMenu? = null,
    /** History matches for the text currently typed in the address bar. */
    val addressSuggestions: List<AddressSuggestion> = emptyList(),
)

/**
 * A stored login that could be filled into the page in front of the user.
 * Never applied automatically: the address and password are written only when
 * the user taps [BrowserAction.FillSavedLogin].
 */
data class SavedLoginSuggestion(
    val tabId: String,
    val host: String,
    val username: String,
)

/** Explicit user actions sent from the UI to [BrowserViewModel] (ARCHITECTURE.md §14). */
sealed interface BrowserAction {
    data class LoadInput(val input: String) : BrowserAction
    data object Reload : BrowserAction
    data object StopLoading : BrowserAction
    data object GoBack : BrowserAction
    data object GoForward : BrowserAction
    data object LoadHome : BrowserAction
    data class OpenUrl(val url: String) : BrowserAction
    data object HideStartPage : BrowserAction
    data object NewTab : BrowserAction
    data object NewPrivateTab : BrowserAction
    data class SwitchTab(val tabId: String) : BrowserAction
    /** Restore the crashed active tab (crash card button). */
    data object RestoreCrashedTab : BrowserAction
    data class CloseTab(val tabId: String) : BrowserAction
    data class MoveTab(val tabId: String, val delta: Int) : BrowserAction
    data class RestoreRecentlyClosedTab(val id: String) : BrowserAction
    data object ClearRecentlyClosedTabs : BrowserAction
    data class AllowSitePermission(val id: String) : BrowserAction
    data class DenySitePermission(val id: String) : BrowserAction
    data class ConfirmWebPrompt(
        val id: String,
        val text: String? = null,
        /** Selected CHOICE option indices; empty for other prompt kinds. */
        val selectedIndices: List<Int> = emptyList(),
    ) : BrowserAction
    data class DismissWebPrompt(val id: String) : BrowserAction
    data class CompleteAndroidPermissionRequest(
        val id: String,
        val granted: Boolean,
    ) : BrowserAction
    data object CloseTabAndReturnToOpener : BrowserAction
    data object ToggleBookmark : BrowserAction
    data object PrintPage : BrowserAction
    data object SavePageAsPdf : BrowserAction
    data object ClearBrowsingData : BrowserAction
    data object ClearSiteData : BrowserAction
    data object ToggleDesktopMode : BrowserAction
    data object OpenFind : BrowserAction
    data object CloseFind : BrowserAction
    data object ClearFind : BrowserAction
    data class FindNext(val query: String) : BrowserAction
    data class FindPrevious(val query: String) : BrowserAction
    data object ToggleFullscreen : BrowserAction
    data object ExitFullscreen : BrowserAction
    data class SwitchAdjacentTab(val delta: Int) : BrowserAction
    /** Jump to the next tab, wrapping from the last tab back to the first. */
    data object CycleNextTab : BrowserAction
    data class AddressQueryChanged(val query: String) : BrowserAction
    data object CloseAllTabs : BrowserAction
    data object UndoCloseAllTabs : BrowserAction
    data object SavePendingCredential : BrowserAction
    /** Fill the offered saved login into the current page (user tap only). */
    data object FillSavedLogin : BrowserAction
    data object DismissSavedLoginSuggestion : BrowserAction
    data object ShowZoomPanel : BrowserAction
    data object HideZoomPanel : BrowserAction
    data object ResetSiteZoom : BrowserAction
    data class SetSiteZoom(val percent: Int) : BrowserAction
    data object ShowDiagnostics : BrowserAction
    data object DismissDiagnostics : BrowserAction
    data object DismissPendingCredential : BrowserAction
    data object DismissPageContextMenu : BrowserAction
    data object OpenContextMenuLink : BrowserAction
    data object OpenContextMenuLinkInNewTab : BrowserAction
    data object OpenContextMenuLinkInPrivateTab : BrowserAction
    data object OpenContextMenuMedia : BrowserAction
    data object OpenContextMenuMediaInNewTab : BrowserAction
    data object DownloadContextMenuLink : BrowserAction
    data object DownloadContextMenuMedia : BrowserAction
    data class AddStartShortcut(val title: String, val url: String) : BrowserAction
    data class EditStartShortcut(val id: Long, val title: String, val url: String) : BrowserAction
    data class DeleteStartShortcut(val id: Long) : BrowserAction
}
