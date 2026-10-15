package com.example.browser.ui.browser

import com.example.browser.browser.BrowserSession

/** UI-ready representation of one open tab (ARCHITECTURE.md §14). */
data class TabUiItem(
    val id: String,
    val title: String,
    val url: String,
    val isPrivate: Boolean,
    val isActive: Boolean,
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
    val isSecure: Boolean = false,
    val isPrivate: Boolean = false,
    val loadFailed: Boolean = false,
    val isBookmarked: Boolean = false,
    /** Engine bootstrap failure (e.g. GeckoView could not start). */
    val errorMessage: String? = null,
    /** Engine session of the active tab; used only to create its native view. */
    val activeSession: BrowserSession? = null,
)

/** Explicit user actions sent from the UI to [BrowserViewModel] (ARCHITECTURE.md §14). */
sealed interface BrowserAction {
    data class LoadInput(val input: String) : BrowserAction
    data object Reload : BrowserAction
    data object StopLoading : BrowserAction
    data object GoBack : BrowserAction
    data object GoForward : BrowserAction
    data object LoadHome : BrowserAction
    data object NewTab : BrowserAction
    data object NewPrivateTab : BrowserAction
    data class SwitchTab(val tabId: String) : BrowserAction
    data class CloseTab(val tabId: String) : BrowserAction
    data object ToggleBookmark : BrowserAction
    data object ClearBrowsingData : BrowserAction
}
