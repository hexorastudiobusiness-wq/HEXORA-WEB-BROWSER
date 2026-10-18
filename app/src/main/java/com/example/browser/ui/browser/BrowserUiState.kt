package com.example.browser.ui.browser

import android.graphics.Bitmap
import com.example.browser.browser.BrowserSession
import com.example.browser.browser.FindResultUi
import com.example.browser.browser.PageMediaKind

/** UI-ready representation of one open tab (ARCHITECTURE.md §14). */
data class TabUiItem(
    val id: String,
    val title: String,
    val url: String,
    val isPrivate: Boolean,
    val isActive: Boolean,
    /** Suspended tabs keep state + thumbnail but hold no live engine session. */
    val isSuspended: Boolean = false,
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
    val isPrivate: Boolean = false,
    val loadFailed: Boolean = false,
    val isBookmarked: Boolean = false,
    val isDesktopMode: Boolean = false,
    val isFullscreen: Boolean = false,
    val findVisible: Boolean = false,
    val findResult: FindResultUi = FindResultUi(),
    /** Current text zoom (50..200), mirrors the persisted setting. */
    val textZoomPercent: Int = 100,
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
    /** Long-press menu for a link/image/video/audio on the page, if open. */
    val pageContextMenu: PageContextMenu? = null,
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
    data class CloseTab(val tabId: String) : BrowserAction
    data object CloseTabAndReturnToOpener : BrowserAction
    data object ToggleBookmark : BrowserAction
    data object ClearBrowsingData : BrowserAction
    data object ClearSiteData : BrowserAction
    data object ToggleDesktopMode : BrowserAction
    data class SetTextZoom(val percent: Int) : BrowserAction
    data object OpenFind : BrowserAction
    data object CloseFind : BrowserAction
    data object ClearFind : BrowserAction
    data class FindNext(val query: String) : BrowserAction
    data class FindPrevious(val query: String) : BrowserAction
    data object ToggleFullscreen : BrowserAction
    data object ExitFullscreen : BrowserAction
    data class SwitchAdjacentTab(val delta: Int) : BrowserAction
    data object CloseAllTabs : BrowserAction
    data object UndoCloseAllTabs : BrowserAction
    data object SavePendingCredential : BrowserAction
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
