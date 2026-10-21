package com.example.browser.ui.browser

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.browser.BrowserEngine
import com.example.browser.browser.BrowserSession
import com.example.browser.browser.BrowserSessionConfig
import com.example.browser.browser.BrowserSessionEvent
import com.example.browser.browser.AutofillCredential
import com.example.browser.browser.PageMediaKind
import com.example.browser.browser.SitePermissionDecision
import com.example.browser.browser.SitePermissionEntry
import com.example.browser.browser.SitePermissionKind
import com.example.browser.browser.WebColorScheme
import com.example.browser.browser.SavedTabState
import com.example.browser.browser.SessionPageState
import com.example.browser.data.database.BookmarkEntity
import com.example.browser.data.database.HistoryEntity
import com.example.browser.data.repository.BookmarkRepository
import com.example.browser.data.repository.CredentialRepository
import com.example.browser.data.repository.FaviconFetcher
import com.example.browser.data.repository.HistoryRepository
import com.example.browser.data.repository.StartShortcutRepository
import com.example.browser.data.settings.BrowserSettingsState
import com.example.browser.data.settings.PersistedTab
import com.example.browser.data.settings.SettingsRepository
import com.example.browser.data.settings.TabSessionStore
import com.example.browser.data.settings.ThemeMode
import com.example.browser.download.DownloadFileName
import com.example.browser.util.CredentialOrigin
import com.example.browser.util.ShortcutIconStore
import com.example.browser.util.TabThumbnailStore
import com.example.browser.util.UrlUtils
import java.io.InputStream
import java.util.Collections
import java.util.UUID
import kotlin.math.absoluteValue
import kotlin.math.sign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns all tabs and coordinates between the [BrowserEngine], repositories and
 * the UI. Business logic lives here — never in Composables.
 *
 * Tabs are identified by a stable tab id (not by their engine session).
 * Following Mozilla's Android Components model, a tab's state is kept
 * independently of its engine session: sessions are created lazily when a
 * tab is rendered, only the selected tab's session is high priority, and
 * sessions are only suspended on genuine low-memory signals (keeping the
 * selected tab plus the [SUSPEND_KEEP_INACTIVE_TABS] most recently used
 * inactive ones). A suspended tab keeps its saved engine state +
 * thumbnail and is restored when selected.
 */
@OptIn(FlowPreview::class)
class BrowserViewModel(
    private val engineFactory: (javaScriptEnabled: Boolean) -> BrowserEngine,
    private val historyRepository: HistoryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsRepository: SettingsRepository,
    private val thumbnailStore: TabThumbnailStore,
    private val credentialRepository: CredentialRepository,
    private val shortcutRepository: StartShortcutRepository,
    private val shortcutIconStore: ShortcutIconStore,
    private val faviconFetcher: FaviconFetcher,
    private val tabSessionStore: TabSessionStore,
) : ViewModel() {

    private var engine: BrowserEngine? = null

    private class TabEntry(
        val tabId: String,
        var session: BrowserSession?,
        val isPrivate: Boolean,
        var desktopMode: Boolean,
        var lastUrl: String = "",
        var lastTitle: String = "",
        var savedState: SavedTabState? = null,
        var showStartPage: Boolean = false,
        /**
         * The tab's content process crashed while the app was in the
         * foreground (Firefox-style "crashed tab"): its session is
         * suspended and is only recreated when the user explicitly
         * restores the tab, so a crashing page cannot loop us.
         */
        var crashed: Boolean = false,
        /** Tab that caused this page-opened tab to be created, if any. */
        val openerTabId: String? = null,
        /** Last time this tab was selected; drives inactive-tab grouping. */
        var lastAccessMs: Long = System.currentTimeMillis(),
    )

    private data class ClosedTabInfo(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val title: String,
        val isPrivate: Boolean,
        val desktopMode: Boolean,
        val closedAtMs: Long = System.currentTimeMillis(),
    )

    private val _tabs = MutableStateFlow<List<TabEntry>>(emptyList())
    private val _activeTabId = MutableStateFlow<String?>(null)
    private val _pageStates = MutableStateFlow<Map<String, SessionPageState>>(emptyMap())
    private val _thumbnailVersions = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _isCurrentBookmarked = MutableStateFlow(false)
    private val _engineError = MutableStateFlow<String?>(null)
    private val _findVisible = MutableStateFlow(false)
    private val _userFullscreen = MutableStateFlow(false)
    private val _recentlyClosed = MutableStateFlow<List<ClosedTabInfo>>(emptyList())

    /**
     * Ids of the tabs closed by the most recent Close All, so Undo Close
     * All restores exactly that batch — never tabs the user had closed
     * individually earlier (restoring those too, and wiping the whole
     * Recently Closed list, was the previous behaviour).
     */
    private var lastCloseAllIds: Set<String> = emptySet()
    private val _pendingCredentialPrompt = MutableStateFlow<CredentialSavePrompt?>(null)
    private val _shortcutIcons = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    private val shortcutIconFetchesInFlight = mutableSetOf<Long>()
    private val shortcutIconLastAttemptAt = mutableMapOf<Long, Long>()

    private data class PendingCredential(
        val origin: String,
        val username: String,
        val password: String,
        val isUpdate: Boolean,
    )

    private var pendingCredential: PendingCredential? = null

    /** Tab ids ordered by last activation; last element = most recently used. */
    private val lruOrder = mutableListOf<String>()

    private val sessionJobs = mutableMapOf<String, Job>()
    private val lastThumbnailUrl = mutableMapOf<String, String>()

    private val _downloadEvents =
        MutableSharedFlow<BrowserSessionEvent.DownloadRequested>(extraBufferCapacity = 16)
    val downloadEvents: SharedFlow<BrowserSessionEvent.DownloadRequested> =
        _downloadEvents.asSharedFlow()

    /** A generated PDF is ready for the Android print flow. */
    data class PdfPrintReady(
        val fileName: String,
        val bodySource: () -> InputStream?,
    )

    private val _pdfPrintEvents =
        MutableSharedFlow<PdfPrintReady>(extraBufferCapacity = 2)
    val pdfPrintEvents: SharedFlow<PdfPrintReady> = _pdfPrintEvents.asSharedFlow()

    private val _externalLinkEvents =
        MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Non-web URIs (tel:, mailto:, intent:, …) for the Android intent layer. */
    val externalLinkEvents: SharedFlow<String> = _externalLinkEvents.asSharedFlow()

    /** A page is waiting for the system file picker. */
    data class FilePickerRequest(
        val requestId: String,
        val allowMultiple: Boolean,
        val isFolder: Boolean,
        val mimeTypes: List<String>,
        /** Gecko capture mode: 0 none, 1 any camera, 2 front, 3 rear. */
        val captureMode: Int = 0,
    )

    private val _filePickerRequests =
        MutableSharedFlow<FilePickerRequest>(extraBufferCapacity = 4)
    val filePickerRequests: SharedFlow<FilePickerRequest> =
        _filePickerRequests.asSharedFlow()

    private val pendingFilePickerSessions =
        mutableMapOf<String, BrowserSession>()

    private val _pageContextMenu = MutableStateFlow<PageContextMenu?>(null)

    /** Individually restorable closed tabs (private tabs are never kept). */
    val recentlyClosedTabs: StateFlow<List<RecentlyClosedTabUi>> =
        _recentlyClosed.map { closed ->
            closed.map {
                RecentlyClosedTabUi(it.id, it.title.ifBlank { it.url }, it.url)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private data class PendingDecision(
        val tabId: String,
        val allow: () -> Unit,
        val deny: () -> Unit,
    )

    private val _sitePermissionRequest =
        MutableStateFlow<SitePermissionRequestUi?>(null)
    val sitePermissionRequest: StateFlow<SitePermissionRequestUi?> =
        _sitePermissionRequest.asStateFlow()
    private val pendingSitePermissions = mutableMapOf<String, PendingDecision>()

    private val _webPromptRequest = MutableStateFlow<WebPromptUi?>(null)
    val webPromptRequest: StateFlow<WebPromptUi?> = _webPromptRequest.asStateFlow()
    private data class PendingWebPrompt(
        val tabId: String,
        val confirm: (String?, List<Int>) -> Unit,
        val dismiss: () -> Unit,
    )
    private val pendingWebPrompts = mutableMapOf<String, PendingWebPrompt>()

    private val _androidPermissionRequest =
        MutableStateFlow<AndroidPermissionRequestUi?>(null)
    val androidPermissionRequest: StateFlow<AndroidPermissionRequestUi?> =
        _androidPermissionRequest.asStateFlow()
    private val pendingAndroidPermissions = mutableMapOf<String, PendingDecision>()

    /** Text currently typed in the address bar (drives history suggestions). */
    private val _addressQuery = MutableStateFlow("")

    val settings: StateFlow<BrowserSettingsState> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        BrowserSettingsState(),
    )

    private val tabsSlice = combine(
        _tabs,
        _activeTabId,
        _pageStates,
        _thumbnailVersions,
    ) { tabs, activeId, states, thumbs ->
        TabsSlice(tabs, activeId, states, thumbs)
    }

    private val chromeSlice = combine(
        _findVisible,
        _userFullscreen,
        _recentlyClosed,
        _pendingCredentialPrompt,
        _pageContextMenu,
    ) { find, fs, closed, prompt, contextMenu ->
        ChromeSlice(find, fs, closed.size, prompt, contextMenu)
    }

    private val historyAndBookmarks = combine(
        historyRepository.history,
        bookmarkRepository.bookmarks,
    ) { history, bookmarks -> history to bookmarks }

    private val startSlice = combine(
        chromeSlice,
        shortcutRepository.shortcuts,
        _shortcutIcons,
        historyAndBookmarks,
        _addressQuery,
    ) { chrome, shortcuts, icons, historyAndBookmarks, addressQuery ->
        StartSlice(
            chrome = chrome,
            shortcuts = shortcuts
                .take(StartShortcutRepository.MAX_SHORTCUTS)
                .map { StartShortcut(it.id, it.title, it.url) },
            icons = icons,
            addressSuggestions = buildAddressSuggestions(
                historyAndBookmarks.first,
                historyAndBookmarks.second,
                addressQuery,
            ),
        )
    }

    val uiState: StateFlow<BrowserUiState> = combine(
        tabsSlice,
        _isCurrentBookmarked,
        _engineError,
        startSlice,
        settings,
    ) { slice, bookmarked, error, start, currentSettings ->
        val activeEntry = slice.tabs.firstOrNull { it.tabId == slice.activeId }
        val activeState = slice.activeId?.let { slice.states[it] }
            ?: SessionPageState()
        BrowserUiState(
            tabs = slice.tabs.map { entry ->
                val state = slice.states[entry.tabId]
                TabUiItem(
                    id = entry.tabId,
                    title = (state?.title?.takeIf { it.isNotBlank() }
                        ?: entry.lastTitle.takeIf { it.isNotBlank() }
                        ?: state?.url?.takeIf { it.isNotBlank() }
                        ?: entry.lastUrl),
                    url = state?.url?.takeIf { it.isNotBlank() } ?: entry.lastUrl,
                    isPrivate = entry.isPrivate,
                    isActive = entry.tabId == slice.activeId,
                    isSuspended = entry.session == null,
                    isInactive = entry.tabId != slice.activeId &&
                        System.currentTimeMillis() - entry.lastAccessMs >
                        INACTIVE_TAB_AFTER_MS,
                    lastAccessMs = entry.lastAccessMs,
                )
            },
            activeTabId = slice.activeId,
            currentUrl = if (activeEntry?.showStartPage == true) {
                ""
            } else {
                activeState.url.ifBlank { activeEntry?.lastUrl ?: "" }
            },
            pageTitle = if (activeEntry?.showStartPage == true) {
                "Hexora"
            } else {
                activeState.title
            },
            isLoading = activeState.isLoading,
            progress = activeState.progress,
            canGoBack = activeState.canGoBack,
            canGoForward = activeState.canGoForward,
            canBackCloseTab = activeEntry?.openerTabId?.let { openerId ->
                slice.tabs.any { it.tabId == openerId }
            } ?: false,
            isSecure = activeState.isSecure,
            isSecurityException = activeState.isSecurityException,
            hasMixedContent = activeState.hasMixedContent,
            certificateHost = activeState.certificateHost,
            certificateSubject = activeState.certificateSubject,
            certificateIssuer = activeState.certificateIssuer,
            isPrivate = activeEntry?.isPrivate ?: false,
            loadFailed = activeState.loadFailed,
            isBookmarked = bookmarked && activeEntry?.isPrivate != true,
            isDesktopMode = activeEntry?.desktopMode ?: false,
            isFullscreen = start.chrome.userFullscreen || activeState.isPageFullscreen,
            findVisible = start.chrome.findVisible,
            findResult = activeState.findResult,
            textZoomPercent = currentSettings.textZoomPercent,
            recentlyClosedCount = start.chrome.recentlyClosedCount,
            thumbnailVersions = slice.thumbnails,
            errorMessage = error,
            activeSession = activeEntry?.session,
            showStartPage = activeEntry?.showStartPage ?: slice.tabs.isEmpty(),
            activeTabCrashed = activeEntry?.crashed == true,
            hasWebPage = activeState.url.isNotBlank() ||
                (activeEntry?.lastUrl?.isNotBlank() == true),
            startShortcuts = if (activeEntry?.isPrivate == true) {
                emptyList()
            } else {
                start.shortcuts
            },
            canAddStartShortcut = activeEntry?.isPrivate != true &&
                start.shortcuts.size < StartShortcutRepository.MAX_SHORTCUTS,
            startShortcutIcons = if (activeEntry?.isPrivate == true) {
                emptyMap()
            } else {
                start.icons
            },
            pendingCredentialSave = start.chrome.pendingCredentialSave,
            pageContextMenu = start.chrome.pageContextMenu,
            // Private tabs never surface the normal browsing history.
            addressSuggestions = if (activeEntry?.isPrivate == true) {
                emptyList()
            } else {
                val query = _addressQuery.value.trim().lowercase()
                val openTabSuggestions = if (query.length < 2) {
                    emptyList()
                } else {
                    slice.tabs.filter { entry ->
                        entry.tabId != slice.activeId && !entry.isPrivate &&
                            entry.lastUrl.isNotBlank() &&
                            (
                                entry.lastUrl.lowercase().contains(query) ||
                                    entry.lastTitle.lowercase().contains(query)
                                )
                    }.map { entry ->
                        AddressSuggestion(
                            title = entry.lastTitle.ifBlank { entry.lastUrl },
                            url = entry.lastUrl,
                            tabId = entry.tabId,
                        )
                    }
                }
                (start.addressSuggestions + openTabSuggestions)
                    .filter { it.url != activeState.url || it.tabId != null }
                    .distinctBy { it.tabId ?: it.url }
                    .take(MAX_ADDRESS_SUGGESTIONS)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUiState())

    private var pendingExternalUrl: String? = null

    /**
     * Tab-strip writes stay disabled until the first restore has read the
     * persisted strip. The persist collectors start immediately and would
     * otherwise race a slow cold start, overwriting the saved tabs with the
     * ViewModel's initial empty list before [restorePersistedTabs] reads it.
     */
    @Volatile
    private var tabRestoreComplete: Boolean = false

    /**
     * Whether the app is currently in the foreground. Distinguishes a
     * crash the user witnessed (mark the tab crashed, Firefox-style) from
     * a content process the system quietly killed in the background
     * (restore it silently when the tab is needed again).
     */
    @Volatile
    private var appInForeground: Boolean = false

    /** Delivers the system picker's result to the page that requested it. */
    fun completeFilePicker(requestId: String, uris: List<Uri>) {
        val session = pendingFilePickerSessions.remove(requestId) ?: return
        if (uris.isEmpty()) {
            session.cancelFilePicker(requestId)
        } else {
            session.completeFilePicker(requestId, uris)
        }
    }

    /** Cancels the page's pending file-picker request. */
    fun cancelFilePicker(requestId: String) {
        pendingFilePickerSessions.remove(requestId)?.cancelFilePicker(requestId)
    }

    /** Loads stored site permissions for the settings manager. */
    fun loadSitePermissions(onResult: (List<SitePermissionEntry>) -> Unit) {
        val currentEngine = engine
        if (currentEngine == null) {
            onResult(emptyList())
        } else {
            currentEngine.loadSitePermissions(onResult)
        }
    }

    /** Changes one stored site permission from the settings manager. */
    fun setSitePermission(entry: SitePermissionEntry, decision: SitePermissionDecision) {
        engine?.setSitePermission(
            uri = entry.uri,
            kind = entry.kind,
            decision = decision,
            isPrivate = entry.isPrivate,
            permissionType = entry.permissionType,
        )
    }

    private fun moveTab(tabId: String, delta: Int) {
        if (delta == 0) return
        val tabs = _tabs.value.toMutableList()
        var index = tabs.indexOfFirst { it.tabId == tabId }
        if (index < 0) return
        // The tab grid shows current and inactive tabs as separate
        // sections; a drag must stay inside the section the tab is shown
        // in, never swap with a tab from the other section (which reads
        // as the tile jumping across the grid).
        val moving = tabs[index]
        fun inSameSection(other: TabEntry): Boolean {
            fun inactive(entry: TabEntry): Boolean =
                entry.tabId != _activeTabId.value &&
                    System.currentTimeMillis() - entry.lastAccessMs >
                    INACTIVE_TAB_AFTER_MS
            return inactive(other) == inactive(moving)
        }
        repeat(delta.absoluteValue) {
            val target = (index + delta.sign).coerceIn(0, tabs.lastIndex)
            if (target == index || !inSameSection(tabs[target])) return@repeat
            Collections.swap(tabs, index, target)
            index = target
        }
        _tabs.value = tabs
        requestPersistTabs()
    }

    private fun restoreRecentlyClosedTab(id: String) {
        val closed = _recentlyClosed.value
        val item = closed.firstOrNull { it.id == id } ?: return
        lastCloseAllIds = lastCloseAllIds - id
        _recentlyClosed.value = closed.filterNot { it.id == id }
        requestPersistTabs()
        createTab(
            url = item.url,
            isPrivate = false,
            desktopMode = item.desktopMode,
            makeActive = true,
        )
    }

    private fun resolveSitePermission(id: String, allowed: Boolean) {
        val pending = pendingSitePermissions.remove(id) ?: return
        if (_sitePermissionRequest.value?.id == id) {
            _sitePermissionRequest.value = null
        }
        if (allowed) pending.allow() else pending.deny()
    }

    private fun resolveWebPrompt(
        id: String,
        text: String?,
        selectedIndices: List<Int> = emptyList(),
        confirmed: Boolean,
    ) {
        val pending = pendingWebPrompts.remove(id) ?: return
        if (_webPromptRequest.value?.id == id) _webPromptRequest.value = null
        if (confirmed) pending.confirm(text, selectedIndices) else pending.dismiss()
    }

    private fun resolveAndroidPermission(id: String, granted: Boolean) {
        val pending = pendingAndroidPermissions.remove(id) ?: return
        if (_androidPermissionRequest.value?.id == id) {
            _androidPermissionRequest.value = null
        }
        if (granted) pending.allow() else pending.deny()
    }

    /** Denies/dismisses shell prompts owned by a tab that is going away. */
    private fun clearPendingForTab(tabId: String) {
        val siteIds = pendingSitePermissions
            .filterValues { it.tabId == tabId }
            .keys
            .toList()
        siteIds.forEach { id ->
            pendingSitePermissions.remove(id)?.deny()
            if (_sitePermissionRequest.value?.id == id) {
                _sitePermissionRequest.value = null
            }
        }
        val androidIds = pendingAndroidPermissions
            .filterValues { it.tabId == tabId }
            .keys
            .toList()
        androidIds.forEach { id ->
            pendingAndroidPermissions.remove(id)?.deny()
            if (_androidPermissionRequest.value?.id == id) {
                _androidPermissionRequest.value = null
            }
        }
        val promptIds = pendingWebPrompts
            .filterValues { it.tabId == tabId }
            .keys
            .toList()
        promptIds.forEach { id ->
            pendingWebPrompts.remove(id)?.dismiss()
            if (_webPromptRequest.value?.id == id) {
                _webPromptRequest.value = null
            }
        }
    }

    private fun handleSitePermission(
        tabId: String,
        event: BrowserSessionEvent.SitePermissionRequested,
    ) {
        // Shell dialogs belong to the foreground tab only; a background tab
        // must never ask for a device decision over another page.
        if (tabId != _activeTabId.value) {
            event.deny()
            return
        }
        if (_sitePermissionRequest.value != null) {
            event.deny()
            return
        }
        val id = UUID.randomUUID().toString()
        pendingSitePermissions[id] = PendingDecision(tabId, event.allow, event.deny)
        _sitePermissionRequest.value = SitePermissionRequestUi(
            id = id,
            origin = event.origin,
            kind = event.kind,
        )
    }

    private fun handleMediaPermission(
        tabId: String,
        event: BrowserSessionEvent.MediaPermissionRequested,
    ) {
        if (tabId != _activeTabId.value) {
            event.reject()
            return
        }
        if (_sitePermissionRequest.value != null) {
            event.reject()
            return
        }
        val id = UUID.randomUUID().toString()
        pendingSitePermissions[id] = PendingDecision(tabId, event.grant, event.reject)
        _sitePermissionRequest.value = SitePermissionRequestUi(
            id = id,
            origin = event.origin,
            // Classify from Gecko's MediaSource category, never from the
            // human-readable device label: labels may be empty, localized,
            // or an opaque id, which used to turn screen sharing into a
            // camera request (and asked Android for CAMERA needlessly).
            kind = when {
                event.wantsVideo && event.videoFromScreen ->
                    SitePermissionKind.SCREEN_SHARE
                event.wantsVideo && event.wantsAudio ->
                    SitePermissionKind.CAMERA_AND_MICROPHONE
                event.wantsVideo -> SitePermissionKind.CAMERA
                else -> SitePermissionKind.MICROPHONE
            },
            wantsVideo = event.wantsVideo,
            wantsAudio = event.wantsAudio,
            wantsMicrophone = event.wantsMicrophone,
        )
    }

    private fun handleAndroidPermission(
        tabId: String,
        event: BrowserSessionEvent.AndroidPermissionRequested,
    ) {
        if (tabId != _activeTabId.value) {
            event.deny()
            return
        }
        if (_androidPermissionRequest.value != null) {
            event.deny()
            return
        }
        val id = UUID.randomUUID().toString()
        pendingAndroidPermissions[id] = PendingDecision(tabId, event.grant, event.deny)
        _androidPermissionRequest.value = AndroidPermissionRequestUi(
            id = id,
            permissions = event.permissions,
        )
    }

    private fun handleWebPrompt(
        tabId: String,
        event: BrowserSessionEvent.WebPromptRequested,
    ) {
        if (tabId != _activeTabId.value) {
            event.dismiss()
            return
        }
        if (_webPromptRequest.value != null) {
            event.dismiss()
            return
        }
        val id = UUID.randomUUID().toString()
        pendingWebPrompts[id] = PendingWebPrompt(tabId, event.confirm, event.dismiss)
        _webPromptRequest.value = WebPromptUi(
            id = id,
            kind = event.kind,
            title = event.title,
            message = event.message,
            defaultValue = event.defaultValue,
            options = event.options,
            optionIds = event.optionIds,
            optionEnabled = event.optionEnabled,
            optionSelected = event.optionSelected,
            allowMultiple = event.allowMultiple,
            passwordOnly = event.passwordOnly,
        )
    }

    init {
        // Tab thumbnails are not restored after process death, so screenshots
        // from a previous process would only be orphaned page images. (The
        // tab strip itself — URLs/titles — is restored via TabSessionStore.)
        viewModelScope.launch(Dispatchers.IO) { thumbnailStore.clearAll() }

        viewModelScope.launch {
            // Engine bootstrap must degrade to the error state, never crash
            // the whole app: GeckoView can refuse to start on an unsupported
            // or damaged installation, and the user should see that instead
            // of a process death before the first frame.
            try {
            val initialSettings = settingsRepository.settings.first()
            val createdEngine = engineFactory(initialSettings.javaScriptEnabled)
            engine = createdEngine
            createdEngine.setTextZoomPercent(initialSettings.textZoomPercent)
            createdEngine.setPreferredColorScheme(initialSettings.themeMode.toWebColorScheme())
            createdEngine.applyContentControls(
                javaScriptEnabled = initialSettings.javaScriptEnabled,
                trackingProtectionEnabled = initialSettings.trackingProtectionEnabled,
                httpsOnlyMode = initialSettings.httpsOnlyMode,
            )
            createdEngine.newSessionCreatedListener = { session, openerSessionId ->
                // A page opened a new window: adopt the engine-made session.
                // Remember its real opener so hardware Back at the new tab's
                // history root can close it and return to that tab.
                val openerEntry = openerSessionId?.let { sessionId ->
                    _tabs.value.firstOrNull { it.session?.id == sessionId }
                } ?: _activeTabId.value?.let { activeId -> entry(activeId) }
                val entry = TabEntry(
                    tabId = newTabId(),
                    session = session,
                    isPrivate = session.isPrivate,
                    // A page-opened tab inherits its opener's rendering mode;
                    // the global setting is only the fallback for an
                    // opener this process no longer knows.
                    desktopMode = openerEntry?.desktopMode
                        ?: initialSettings.desktopMode,
                    openerTabId = openerEntry?.tabId,
                )
                _tabs.update { it + entry }
                touchLru(entry.tabId)
                attachSession(entry, session)
                activateTab(entry.tabId)
            }
            createdEngine.onTabSwipe = { delta -> handleFullscreenTabSwipe(delta) }
            createdEngine.onPullToRefresh = { handlePullToRefresh() }
            restorePersistedTabs(initialSettings.desktopMode)
            pendingExternalUrl?.let { url ->
                pendingExternalUrl = null
                if (initialSettings.openLinksInNewTab) {
                    openNewTab(url = url, isPrivate = false)
                } else {
                    openUrl(url)
                }
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _engineError.value = error.message ?: error.javaClass.simpleName
            }
        }
        // Keep the persisted tab strip in sync so a cold start (e.g. after
        // the system killed the backgrounded process) restores the tabs the
        // user had open. Private tabs are never persisted.
        viewModelScope.launch {
            combine(_tabs, _activeTabId) { tabs, activeId -> tabs to activeId }
                .collect { requestPersistTabs() }
        }
        // Coalesce tab-strip writes: navigation bursts emit many persist
        // requests, but one write after a short quiet period is enough.
        viewModelScope.launch {
            persistRequests.debounce(PERSIST_DEBOUNCE_MS).collect {
                persistTabsSnapshot()
            }
        }
        // Load any favicons cached from earlier visits to shortcut sites.
        viewModelScope.launch {
            shortcutRepository.shortcuts.collect { shortcuts ->
                val ids = shortcuts.map { it.id }.toSet()
                _shortcutIcons.update { icons -> icons.filterKeys { it in ids } }
                shortcuts.forEach { shortcut ->
                    if (!_shortcutIcons.value.containsKey(shortcut.id)) {
                        val icon = withContext(Dispatchers.IO) {
                            shortcutIconStore.load(shortcut.id)
                        }
                        if (icon != null) {
                            _shortcutIcons.update { icons ->
                                icons + (shortcut.id to icon)
                            }
                        }
                    }
                }
            }
        }
        // Keep the engine's text zoom in sync with the persisted setting.
        // Also react immediately when the local password vault is toggled:
        // disabling it must remove credentials already offered to pages.
        viewModelScope.launch {
            var lastPasswordManagerEnabled: Boolean? = null
            settings.collect { current ->
                engine?.setTextZoomPercent(current.textZoomPercent)
                engine?.setPreferredColorScheme(current.themeMode.toWebColorScheme())
                engine?.applyContentControls(
                    javaScriptEnabled = current.javaScriptEnabled,
                    trackingProtectionEnabled = current.trackingProtectionEnabled,
                    httpsOnlyMode = current.httpsOnlyMode,
                )
                if (lastPasswordManagerEnabled != current.passwordManagerEnabled) {
                    lastPasswordManagerEnabled = current.passwordManagerEnabled
                    if (!current.passwordManagerEnabled) {
                        dismissPendingCredential()
                        _tabs.value.forEach { entry ->
                            entry.session?.setAutofillCredential(null)
                        }
                    } else {
                        _tabs.value.filterNot { it.isPrivate }.forEach { entry ->
                            val session = entry.session ?: return@forEach
                            val origin = CredentialOrigin.fromUrl(entry.lastUrl)
                                ?: return@forEach
                            launch {
                                loadSavedCredentialFor(entry, origin, session)
                            }
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            var bookmarkJob: Job? = null
            combine(_activeTabId, _pageStates) { activeId, states ->
                activeId?.let { states[it]?.url }
            }.collect { url ->
                bookmarkJob?.cancel()
                bookmarkJob = if (url.isNullOrBlank()) {
                    _isCurrentBookmarked.value = false
                    null
                } else {
                    launch {
                        bookmarkRepository.observeIsBookmarked(url)
                            .collect { bookmarked -> _isCurrentBookmarked.value = bookmarked }
                    }
                }
            }
        }
    }

    // ---- Actions -----------------------------------------------------------

    fun onAction(action: BrowserAction) {
        when (action) {
            is BrowserAction.LoadInput -> {
                val entry = activeEntry()
                if (entry == null) {
                    createTab(
                        url = action.input,
                        isPrivate = false,
                        desktopMode = settings.value.desktopMode,
                    )
                } else {
                    setShowStartPage(entry, false)
                    restoreForExplicitNavigation(entry)
                    entry.session?.loadUrl(resolveUserInput(action.input))
                }
            }
            BrowserAction.Reload -> activeSession()?.reload()
            BrowserAction.StopLoading -> activeSession()?.stopLoading()
            BrowserAction.GoBack -> activeSession()?.goBack()
            BrowserAction.GoForward -> activeSession()?.goForward()
            BrowserAction.LoadHome -> showStartPage()
            is BrowserAction.OpenUrl -> openUrl(action.url)
            BrowserAction.HideStartPage -> {
                activeEntry()?.let { entry ->
                    if (entry.lastUrl.isNotBlank()) setShowStartPage(entry, false)
                }
            }
            BrowserAction.NewTab -> openNewTab(isPrivate = false)
            BrowserAction.NewPrivateTab -> openNewTab(isPrivate = true)
            is BrowserAction.SwitchTab -> switchTab(action.tabId)
            BrowserAction.RestoreCrashedTab -> restoreCrashedTab()
            is BrowserAction.CloseTab -> closeTab(action.tabId)
            is BrowserAction.MoveTab -> moveTab(action.tabId, action.delta)
            is BrowserAction.RestoreRecentlyClosedTab ->
                restoreRecentlyClosedTab(action.id)
            BrowserAction.ClearRecentlyClosedTabs -> {
                lastCloseAllIds = emptySet()
                _recentlyClosed.value = emptyList()
                requestPersistTabs()
            }
            is BrowserAction.AllowSitePermission ->
                resolveSitePermission(action.id, allowed = true)
            is BrowserAction.DenySitePermission ->
                resolveSitePermission(action.id, allowed = false)
            is BrowserAction.ConfirmWebPrompt ->
                resolveWebPrompt(
                    action.id,
                    action.text,
                    action.selectedIndices,
                    confirmed = true,
                )
            is BrowserAction.DismissWebPrompt ->
                resolveWebPrompt(action.id, null, confirmed = false)
            is BrowserAction.CompleteAndroidPermissionRequest ->
                resolveAndroidPermission(action.id, action.granted)
            BrowserAction.CloseTabAndReturnToOpener -> closeCurrentTabToOpener()
            BrowserAction.ToggleBookmark -> toggleBookmark()
            BrowserAction.PrintPage -> printActivePage()
            BrowserAction.SavePageAsPdf -> saveActivePageAsPdf()
            BrowserAction.ClearBrowsingData -> clearBrowsingData()
            BrowserAction.ClearSiteData -> clearSiteData()
            BrowserAction.ToggleDesktopMode -> toggleDesktopMode()
            is BrowserAction.SetTextZoom ->
                viewModelScope.launch { settingsRepository.setTextZoomPercent(action.percent) }
            BrowserAction.OpenFind -> _findVisible.value = true
            BrowserAction.CloseFind -> {
                _findVisible.value = false
                activeSession()?.clearFind()
            }
            BrowserAction.ClearFind -> activeSession()?.clearFind()
            is BrowserAction.FindNext -> activeSession()?.findText(action.query, forward = true)
            is BrowserAction.FindPrevious ->
                activeSession()?.findText(action.query, forward = false)
            BrowserAction.ToggleFullscreen ->
                _userFullscreen.update { !it }
            BrowserAction.ExitFullscreen -> {
                _userFullscreen.value = false
                activeSession()?.exitFullscreen()
            }
            is BrowserAction.SwitchAdjacentTab -> switchAdjacentTab(action.delta)
            BrowserAction.CycleNextTab -> cycleToNextTab()
            is BrowserAction.AddressQueryChanged ->
                _addressQuery.value = action.query
            BrowserAction.CloseAllTabs -> closeAllTabs()
            BrowserAction.UndoCloseAllTabs -> undoCloseAllTabs()
            BrowserAction.SavePendingCredential -> savePendingCredential()
            BrowserAction.DismissPendingCredential -> dismissPendingCredential()
            BrowserAction.DismissPageContextMenu ->
                _pageContextMenu.value = null
            BrowserAction.OpenContextMenuLink -> openContextMenuLink(newTab = false)
            BrowserAction.OpenContextMenuLinkInNewTab ->
                openContextMenuLink(newTab = true)
            BrowserAction.OpenContextMenuLinkInPrivateTab ->
                openContextMenuLink(newTab = true, forcePrivate = true)
            BrowserAction.OpenContextMenuMedia -> openContextMenuMedia(newTab = false)
            BrowserAction.OpenContextMenuMediaInNewTab ->
                openContextMenuMedia(newTab = true)
            BrowserAction.DownloadContextMenuLink -> downloadContextMenuTarget(link = true)
            BrowserAction.DownloadContextMenuMedia -> downloadContextMenuTarget(link = false)
            is BrowserAction.AddStartShortcut -> {
                viewModelScope.launch {
                    val resolvedUrl = resolveUserInput(action.url)
                    val shortcutId = shortcutRepository.add(
                        title = action.title,
                        url = resolvedUrl,
                    ) ?: return@launch
                    // If this site is already open and loaded, its real icon
                    // can replace the letter placeholder immediately.
                    loadedPageUrlForShortcut(resolvedUrl)?.let { pageUrl ->
                        fetchShortcutIcon(listOf(shortcutId), pageUrl)
                    }
                }
            }
            is BrowserAction.EditStartShortcut -> {
                viewModelScope.launch {
                    val resolvedUrl = resolveUserInput(action.url)
                    val previous = shortcutRepository.shortcuts.first()
                        .firstOrNull { it.id == action.id }
                    val updated = shortcutRepository.update(
                        id = action.id,
                        title = action.title,
                        url = resolvedUrl,
                    )
                    if (updated && previous != null && previous.url != resolvedUrl) {
                        withContext(Dispatchers.IO) {
                            shortcutIconStore.delete(action.id)
                        }
                        _shortcutIcons.update { icons -> icons - action.id }
                        loadedPageUrlForShortcut(resolvedUrl)?.let { pageUrl ->
                            fetchShortcutIcon(listOf(action.id), pageUrl)
                        }
                    }
                }
            }
            is BrowserAction.DeleteStartShortcut -> {
                viewModelScope.launch {
                    shortcutRepository.delete(action.id)
                    withContext(Dispatchers.IO) {
                        shortcutIconStore.delete(action.id)
                    }
                    _shortcutIcons.update { icons -> icons - action.id }
                    shortcutIconLastAttemptAt.remove(action.id)
                    shortcutIconFetchesInFlight.remove(action.id)
                }
            }
        }
    }

    private fun resolveUserInput(input: String): String =
        UrlUtils.resolveInput(
            input = input,
            searchEngine = settings.value.searchEngine,
            customSearchTemplate = settings.value.customSearchTemplate,
        )

    fun openNewTab(url: String? = null, isPrivate: Boolean = false) {
        createTab(
            url = url,
            isPrivate = isPrivate,
            desktopMode = settings.value.desktopMode,
            startPage = url.isNullOrBlank(),
        )
    }

    fun switchTab(tabId: String) {
        val entry = entry(tabId) ?: return
        val session = entry.session
        if (session != null && !session.isUsable()) {
            // The content process died while this tab sat in the background
            // and no death event reached us (or it was missed): suspend it
            // instead of binding a dead session, which rendered a white
            // page even Refresh could not revive.
            handleDeadSession(entry, session)
        }
        if (entry.session == null) {
            // Selecting a suspended/crashed tab is the user's explicit
            // "restore this tab" (Firefox RestoreUseCase semantics).
            entry.crashed = false
            restoreTab(entry)
        }
        activateTab(tabId)
        updateWarmPriorities()
    }

    /** Explicit restore of a crashed active tab (crash card button). */
    private fun restoreCrashedTab() {
        val entry = activeEntry() ?: return
        if (!entry.crashed) return
        entry.crashed = false
        if (entry.session == null) restoreTab(entry)
    }

    /**
     * Recreates a missing session for an explicit navigation. Typing a new
     * address or opening a link is itself the user's restore decision, so a
     * stale crashed flag must not keep the crash card over the new page.
     */
    private fun restoreForExplicitNavigation(entry: TabEntry) {
        if (entry.session != null) return
        entry.crashed = false
        restoreTab(entry)
    }

    fun closeTab(tabId: String) = closeTab(tabId, preferredNextTabId = null)

    private fun closeCurrentTabToOpener() {
        val current = activeEntry() ?: return
        val openerId = current.openerTabId ?: return
        if (entry(openerId) == null) return
        closeTab(current.tabId, preferredNextTabId = openerId)
    }

    private fun closeTab(tabId: String, preferredNextTabId: String?) {
        val entry = entry(tabId) ?: return
        val wasActive = _activeTabId.value == tabId

        // Firefox-style Recently Closed Tabs: individual, restorable, and
        // never populated from private browsing or blank start-page tabs.
        if (!entry.isPrivate && !entry.showStartPage && entry.lastUrl.isNotBlank()) {
            val closed = ClosedTabInfo(
                url = entry.lastUrl,
                title = entry.lastTitle,
                isPrivate = false,
                desktopMode = entry.desktopMode,
            )
            _recentlyClosed.update { existing ->
                (listOf(closed) + existing).take(MAX_RECENTLY_CLOSED_TABS)
            }
        }

        clearPendingForTab(tabId)
        // The closing session cancels its Gecko file prompt itself; dropping
        // the routing entry keeps a late picker result from reaching it.
        pendingFilePickerSessions.entries.removeIf { pending ->
            pending.value === entry.session
        }
        if (wasActive) {
            // Transient chrome belongs to the page that is going away. In
            // particular, app-level fullscreen must not leak onto the Start
            // Page (or the next tab) just because a tab was closed.
            _findVisible.value = false
            _pageContextMenu.value = null
            _userFullscreen.value = false
        }
        sessionJobs.remove(tabId)?.cancel()
        entry.session?.close()
        _tabs.update { tabs -> tabs.filterNot { it.tabId == tabId } }
        _pageStates.update { it - tabId }
        lruOrder.remove(tabId)
        lastThumbnailUrl.remove(tabId)
        viewModelScope.launch(Dispatchers.IO) { thumbnailStore.delete(tabId) }

        when {
            // Chrome-like behavior: the last tab really closes. The app
            // then shows the Hexora Start Page with no live tab selected.
            _tabs.value.isEmpty() -> _activeTabId.value = null
            wasActive -> {
                val next = preferredNextTabId?.let { entry(it) }
                    ?: _tabs.value.lastOrNull { it.tabId != tabId }
                    ?: _tabs.value.first()
                switchTab(next.tabId)
            }
        }
        updateWarmPriorities()
    }

    /** Opens [url] (from Start Page/bookmarks/history/another app). */
    fun openUrl(url: String) {
        val entry = activeEntry()
        if (entry == null) {
            createTab(
                url = url,
                isPrivate = false,
                desktopMode = settings.value.desktopMode,
            )
            return
        }
        setShowStartPage(entry, false)
        restoreForExplicitNavigation(entry)
        entry.session?.loadUrl(url)
    }

    private fun openContextMenuLink(newTab: Boolean, forcePrivate: Boolean = false) {
        val menu = _pageContextMenu.value ?: return
        val url = menu.linkUri ?: return
        _pageContextMenu.value = null
        val entry = activeEntry() ?: return
        if (newTab) {
            createTab(
                url = url,
                isPrivate = entry.isPrivate || forcePrivate,
                desktopMode = entry.desktopMode,
                openerTabId = entry.tabId,
            )
        } else {
            setShowStartPage(entry, false)
            restoreForExplicitNavigation(entry)
            entry.session?.loadUrl(url)
        }
    }

    private fun openContextMenuMedia(newTab: Boolean) {
        val menu = _pageContextMenu.value ?: return
        val url = menu.mediaUri ?: return
        _pageContextMenu.value = null
        val entry = activeEntry() ?: return
        if (newTab) {
            createTab(
                url = url,
                isPrivate = entry.isPrivate,
                desktopMode = entry.desktopMode,
                openerTabId = entry.tabId,
            )
        } else {
            setShowStartPage(entry, false)
            restoreForExplicitNavigation(entry)
            entry.session?.loadUrl(url)
        }
    }

    private fun downloadContextMenuTarget(link: Boolean) {
        val menu = _pageContextMenu.value ?: return
        val entry = activeEntry() ?: return
        val url = if (link) menu.linkUri else menu.mediaUri
        _pageContextMenu.value = null
        if (url.isNullOrBlank() || !UrlUtils.isHttpUrl(url)) return
        val kind = if (link) PageMediaKind.NONE else menu.mediaKind
        _downloadEvents.tryEmit(
            BrowserSessionEvent.DownloadRequested(
                sessionId = entry.session?.id ?: return,
                url = url,
                fileName = downloadFileName(url, kind),
                mimeType = downloadMimeType(url, kind),
                isPrivate = entry.isPrivate,
                pageUrl = entry.lastUrl.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun downloadFileName(url: String, kind: PageMediaKind): String {
        val fromUrl = DownloadFileName.fromUrl(url)
        if (fromUrl.contains('.')) return fromUrl
        val extension = when (kind) {
            PageMediaKind.IMAGE -> ".jpg"
            PageMediaKind.VIDEO -> ".mp4"
            PageMediaKind.AUDIO -> ".mp3"
            PageMediaKind.NONE -> ""
        }
        return fromUrl + extension
    }

    private fun downloadMimeType(url: String, kind: PageMediaKind): String? {
        val extension = url.substringBefore('?').substringAfterLast('.', "")
            .lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> when (kind) {
                PageMediaKind.IMAGE -> "image/jpeg"
                PageMediaKind.VIDEO -> "video/mp4"
                PageMediaKind.AUDIO -> "audio/mpeg"
                PageMediaKind.NONE -> null
            }
        }
    }

    /** Handles ACTION_VIEW intents; safe to call before the engine is ready. */
    fun handleExternalUrl(url: String) {
        if (engine == null || !tabRestoreComplete) {
            // Also queue while the persisted tab strip is still being
            // restored: opening the link immediately would race the restore,
            // which then activates the old persisted tab over the new one.
            // The bootstrap consumes this right after restore completes.
            pendingExternalUrl = url
        } else if (settings.value.openLinksInNewTab) {
            openNewTab(url = url, isPrivate = false)
        } else {
            openUrl(url)
        }
    }

    fun clearBrowsingData() {
        engine?.clearBrowsingData()
        viewModelScope.launch { historyRepository.clearAll() }
    }

    /** Clears only web cache; [onComplete] runs even if the engine is cold. */
    fun clearCache(onComplete: () -> Unit) {
        val currentEngine = engine
        if (currentEngine == null) {
            onComplete()
        } else {
            currentEngine.clearCache(onComplete)
        }
    }

    private fun showStartPage() {
        val entry = activeEntry() ?: return
        setShowStartPage(entry, true)
        _findVisible.value = false
    }

    private fun setShowStartPage(entry: TabEntry, show: Boolean) {
        if (entry.showStartPage == show) return
        entry.showStartPage = show
        _tabs.update { it.toList() }
    }

    /**
     * Loads the saved login for [origin] and offers it to [entry]'s session.
     * Callers invoke this only after a page load has finished (or when the
     * password manager is re-enabled), never during an in-flight navigation,
     * and the result is re-checked against the tab's current URL so a slow
     * database read can never install a credential onto a page the user has
     * already navigated away from.
     */
    private suspend fun loadSavedCredentialFor(
        entry: TabEntry,
        origin: String,
        expectedSession: BrowserSession? = entry.session,
    ) {
        val session = expectedSession ?: return
        if (entry.isPrivate || !settings.value.passwordManagerEnabled) {
            session.setAutofillCredential(null)
            return
        }
        val saved = runCatching {
            credentialRepository.findForOrigin(origin)
        }.getOrDefault(emptyList()).firstOrNull()
        if (entry.session !== session) return
        if (CredentialOrigin.fromUrl(entry.lastUrl) != origin) return
        session.setAutofillCredential(
            saved?.let {
                AutofillCredential(
                    username = it.username,
                    password = it.password,
                    origin = origin,
                )
            },
        )
    }

    private fun savePendingCredential() {
        val pending = pendingCredential ?: return
        pendingCredential = null
        _pendingCredentialPrompt.value = null
        viewModelScope.launch {
            // A Keystore failure (for example an invalidated vault key
            // after the device credential changed) must never crash the
            // browser from a background save; the login simply is not
            // stored, and must not be offered as if it had been.
            val saved = runCatching {
                credentialRepository.save(
                    origin = pending.origin,
                    username = pending.username,
                    password = pending.password,
                )
            }.isSuccess
            if (!saved) return@launch
            // Offer the just-saved login to the still-open login page.
            val entry = activeEntry() ?: return@launch
            if (CredentialOrigin.fromUrl(entry.lastUrl) == pending.origin) {
                entry.session?.setAutofillCredential(
                    AutofillCredential(
                        pending.username,
                        pending.password,
                        pending.origin,
                    ),
                )
            }
        }
    }

    private fun dismissPendingCredential() {
        pendingCredential = null
        _pendingCredentialPrompt.value = null
    }

    private fun handleCredentialSubmitted(
        entry: TabEntry,
        event: BrowserSessionEvent.CredentialSubmitted,
    ) {
        if (entry.isPrivate || !settings.value.passwordManagerEnabled) return
        val origin = CredentialOrigin.fromUrl(event.pageUrl)
            ?: CredentialOrigin.fromUrl(entry.lastUrl)
            ?: return
        viewModelScope.launch {
            val existing = runCatching {
                credentialRepository.findForOrigin(origin)
            }.getOrDefault(emptyList())
                .firstOrNull { it.username == event.username }
            if (existing?.password == event.password) return@launch
            pendingCredential = PendingCredential(
                origin = origin,
                username = event.username,
                password = event.password,
                isUpdate = existing != null,
            )
            _pendingCredentialPrompt.value = CredentialSavePrompt(
                origin = origin,
                username = event.username,
                isUpdate = existing != null,
            )
        }
    }

    /**
     * Called on genuine low-memory signals. Mirrors Mozilla's
     * TrimMemoryMiddleware: the selected tab and the
     * [SUSPEND_KEEP_INACTIVE_TABS] most recently used inactive tabs are
     * never suspended; only colder sessions are closed (their saved state
     * and thumbnail are kept, so they restore when selected again).
     */
    fun suspendTabsOutsideWarmSet() {
        val activeId = _activeTabId.value
        val warmInactiveIds = lruOrder
            .filter { it != activeId }
            .takeLast(SUSPEND_KEEP_INACTIVE_TABS)
            .toSet()
        _tabs.value
            .filter { entry ->
                entry.session != null &&
                    entry.tabId != activeId &&
                    entry.tabId !in warmInactiveIds
            }
            .forEach { suspendTab(it) }
    }

    /**
     * Called by the Activity every time the app returns to the foreground.
     * Any session whose content process died while backgrounded — whether
     * or not Gecko delivered the death event — is recreated here: the
     * active tab immediately, inactive ones lazily (they restore when
     * selected). This is the foreground half of the crash-recovery story;
     * without it the tab that was on screen at minimise time came back as
     * an empty tab even though the tab strip still showed its site.
     */
    fun onAppForegrounded() {
        // Check for quietly-killed sessions *before* flipping the flag:
        // handleDeadSession() classifies a death as user-witnessed from it,
        // and a process the system killed while we were backgrounded must
        // be restored silently below, never presented as a crashed tab.
        _tabs.value.forEach { entry ->
            val session = entry.session ?: return@forEach
            if (!session.isUsable()) {
                handleDeadSession(entry, session, witnessed = false)
            }
        }
        appInForeground = true
        // Returning to the app is the explicit "this tab is needed again"
        // moment: a tab whose process died quietly in the background is
        // restored now. A tab that crashed in front of the user stays
        // crashed until they restore it (see handleDeadSession).
        val active = activeEntry()
        if (active != null && active.session == null && !active.crashed) {
            restoreTab(active)
        }
    }

    /** Called by the Activity when the app leaves the foreground. */
    fun onAppBackgrounded() {
        appInForeground = false
    }

    /**
     * Central low-memory policy (the tab layer owns it, like Mozilla's
     * middleware chain — the Activity only forwards the raw signal).
     * Exact levels only: RUNNING_CRITICAL and COMPLETE.
     */
    fun onLowMemory(level: Int) {
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            suspendTabsOutsideWarmSet()
        }
    }

    /**
     * Called once the Activity is fully resumed (posted past the first
     * frame, so the window surface exists again). After a long stay in the
     * background the sessions can all be alive while the native rendering
     * surface is dead: pages keep loading yet paint white, Refresh cannot
     * revive them, and tab cycling cannot either — only recreating the
     * surface (what a round trip to the tab switcher incidentally did)
     * helps. Doing this at `onStart` proved too early (it still failed in
     * about one minimise in ten), so it runs here, after resume.
     */
    fun onBrowserResumed(backgroundDurationMs: Long) {
        if (backgroundDurationMs < REBIND_AFTER_BACKGROUND_MS) return
        val active = activeEntry() ?: return
        if (active.session == null) {
            if (!active.crashed) restoreTab(active)
            return
        }
        if (active.session?.isUsable() == true) {
            engine?.rebindRenderedContentView()
        }
    }

    /**
     * Pull-to-refresh from the content view: reloads the active page.
     * Presentation must not change: when the *page* had put itself into
     * fullscreen, the reload would drop it out, so fullscreen is taken
     * over at the app level first and the user stays in fullscreen.
     */
    private fun handlePullToRefresh() {
        val entry = activeEntry() ?: return
        val session = entry.session ?: return
        if (_pageStates.value[entry.tabId]?.isPageFullscreen == true) {
            _userFullscreen.value = true
        }
        session.reload()
    }

    /** Fullscreen tab swipes are detected by the engine's content view. */
    fun setTabSwipeEnabled(enabled: Boolean) {
        engine?.tabSwipeEnabled = enabled
    }

    /**
     * A left/right swipe over fullscreen web content: move to the adjacent
     * tab and *stay* in fullscreen (app-level, so the newly shown tab is
     * fullscreen too) until the user leaves with Back.
     */
    private fun handleFullscreenTabSwipe(delta: Int) {
        if (_tabs.value.size < 2) return
        val activeId = _activeTabId.value ?: return
        val pageFullscreen = _pageStates.value[activeId]?.isPageFullscreen == true
        if (!_userFullscreen.value && !pageFullscreen) return
        _userFullscreen.value = true
        switchAdjacentTab(delta)
    }

    /** Recreates tab entries (no sessions yet) from the persisted tab strip. */
    private suspend fun restorePersistedTabs(desktopMode: Boolean) {
        try {
            restorePersistedTabsInternal(desktopMode)
        } finally {
            // Whatever happened above, the initial restore is over: tab
            // persistence must never stay blocked for the whole process.
            tabRestoreComplete = true
        }
    }

    private suspend fun restorePersistedTabsInternal(desktopMode: Boolean) {
        val persisted = runCatching { tabSessionStore.load() }.getOrNull()
        // Recently Closed survives process death too (non-private only,
        // exactly like the tab strip itself).
        _recentlyClosed.value = persisted?.recentlyClosed.orEmpty().map { saved ->
            ClosedTabInfo(
                url = saved.url,
                title = saved.title,
                isPrivate = false,
                desktopMode = saved.desktopMode ?: desktopMode,
            )
        }
        if (persisted == null || persisted.tabs.isEmpty()) {
            createTab(
                url = null,
                isPrivate = false,
                desktopMode = desktopMode,
                startPage = true,
            )
            tabRestoreComplete = true
            requestPersistTabs()
            return
        }
        val entries = persisted.tabs.map { saved ->
            TabEntry(
                tabId = newTabId(),
                session = null,
                isPrivate = false,
                desktopMode = saved.desktopMode ?: desktopMode,
                lastUrl = saved.url,
                lastTitle = saved.title,
                savedState = saved.engineState?.let { SavedTabState(it) },
                showStartPage = false,
            )
        }
        _tabs.update { it + entries }
        entries.forEach { touchLru(it.tabId) }
        val active = entries[persisted.activeIndex.coerceIn(entries.indices)]
        _activeTabId.value = active.tabId
        restoreTab(active)
        updateWarmPriorities()
        tabRestoreComplete = true
        requestPersistTabs()
    }

    /**
     * Writes the current (non-private) tab strip to [tabSessionStore],
     * including each tab's latest engine session state (Firefox-style
     * per-tab engine-state persistence). Writes are requested through
     * [requestPersistTabs] and debounced, so bursts of navigation events
     * coalesce into a single store write.
     */
    private suspend fun persistTabsSnapshot() {
        // Defense in depth: never let a collector that started before the
        // first restore turn the initial empty tab list into stored state.
        if (!tabRestoreComplete) return
        val entries = _tabs.value.filterNot { it.isPrivate || it.lastUrl.isBlank() }
        val activeIndex = entries.indexOfFirst { it.tabId == _activeTabId.value }
        runCatching {
            tabSessionStore.save(
                tabs = entries.map { entry ->
                    PersistedTab(
                        url = entry.lastUrl,
                        title = entry.lastTitle,
                        engineState = (
                            entry.session?.latestSavedState()
                                ?: entry.savedState
                            )?.serialized,
                        desktopMode = entry.desktopMode,
                    )
                },
                activeIndex = if (activeIndex >= 0) activeIndex else 0,
                recentlyClosed = _recentlyClosed.value
                    .filterNot { it.isPrivate }
                    .map { closed ->
                        PersistedTab(
                            url = closed.url,
                            title = closed.title,
                            desktopMode = closed.desktopMode,
                        )
                    },
            )
        }
    }

    private val persistRequests =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Asks for a (debounced) tab-strip write; cheap to call from anywhere. */
    private fun requestPersistTabs() {
        if (tabRestoreComplete) persistRequests.tryEmit(Unit)
    }

    // ---- Action helpers ------------------------------------------------------

    private fun printActivePage() {
        val entry = activeEntry() ?: return
        val session = entry.session ?: return
        session.requestPrint { stream ->
            if (stream != null) {
                val emitted = _pdfPrintEvents.tryEmit(
                    PdfPrintReady(
                        fileName = pdfFileName(entry),
                        bodySource = { stream },
                    ),
                )
                // Nobody took ownership of the PDF stream; close it rather
                // than leaking the underlying engine connection.
                if (!emitted) runCatching { stream.close() }
            }
        }
    }

    private fun saveActivePageAsPdf() {
        val entry = activeEntry() ?: return
        val session = entry.session ?: return
        session.saveAsPdf { stream ->
            if (stream != null) {
                val emitted = _downloadEvents.tryEmit(
                    BrowserSessionEvent.DownloadRequested(
                        sessionId = session.id,
                        url = entry.lastUrl,
                        fileName = pdfFileName(entry),
                        mimeType = "application/pdf",
                        isPrivate = entry.isPrivate,
                        pageUrl = entry.lastUrl.takeIf { it.isNotBlank() },
                        bodySource = { stream },
                    ),
                )
                if (!emitted) runCatching { stream.close() }
            }
        }
    }

    private fun pdfFileName(entry: TabEntry): String {
        val base = entry.lastTitle.ifBlank {
            runCatching { Uri.parse(entry.lastUrl).host }
                .getOrNull()
                ?.ifBlank { null }
                ?: "page"
        }
        return DownloadFileName.sanitize(
            if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf",
        )
    }

    private fun toggleBookmark() {
        val entry = activeEntry() ?: return
        // Bookmarks are persistent normal-browsing data; a private tab's
        // URL/title must never be written there.
        if (entry.isPrivate) return
        val state = _pageStates.value[entry.tabId] ?: return
        if (!UrlUtils.isHttpUrl(state.url)) return
        viewModelScope.launch {
            bookmarkRepository.toggle(state.url, state.title)
        }
    }

    private fun clearSiteData() {
        val entry = activeEntry() ?: return
        val url = _pageStates.value[entry.tabId]?.url?.ifBlank { entry.lastUrl } ?: return
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        engine?.clearSiteData(host)
        entry.session?.reload()
    }

    private fun toggleDesktopMode() {
        val entry = activeEntry() ?: return
        entry.desktopMode = !entry.desktopMode
        entry.session?.setDesktopMode(entry.desktopMode)
        // Refresh derived UI (tab item state) even though session state also updates.
        _tabs.update { it.toList() }
    }

    private fun switchAdjacentTab(delta: Int) {
        val tabs = _tabs.value
        if (tabs.size < 2) return
        val currentIndex = tabs.indexOfFirst { it.tabId == _activeTabId.value }
        if (currentIndex < 0) return
        val target = (currentIndex + delta).coerceIn(0, tabs.lastIndex)
        if (target != currentIndex) switchTab(tabs[target].tabId)
    }

    /** One-tap quick tab cycling: next tab, wrapping last -> first. */
    private fun cycleToNextTab() {
        val tabs = _tabs.value
        if (tabs.size < 2) return
        val currentIndex = tabs.indexOfFirst { it.tabId == _activeTabId.value }
        val target = if (currentIndex < 0) 0 else (currentIndex + 1) % tabs.size
        switchTab(tabs[target].tabId)
    }

    private fun closeAllTabs() {
        val closed = _tabs.value.mapNotNull { entry ->
            val url = entry.lastUrl
            if (url.isBlank() || entry.isPrivate || entry.showStartPage) {
                null
            } else {
                ClosedTabInfo(
                    url = url,
                    title = entry.lastTitle,
                    isPrivate = false,
                    desktopMode = entry.desktopMode,
                )
            }
        }
        _tabs.value.forEach { entry ->
            clearPendingForTab(entry.tabId)
            sessionJobs.remove(entry.tabId)?.cancel()
            entry.session?.close()
        }
        _tabs.value = emptyList()
        _pageStates.value = emptyMap()
        lruOrder.clear()
        lastThumbnailUrl.clear()
        _activeTabId.value = null
        lastCloseAllIds = closed.map { it.id }.toSet()
        _recentlyClosed.value = (closed + _recentlyClosed.value)
            .take(MAX_RECENTLY_CLOSED_TABS)
        _findVisible.value = false
        _userFullscreen.value = false
        _pageContextMenu.value = null
        _addressQuery.value = ""
        dismissPendingCredential()
        pendingFilePickerSessions.clear()
        viewModelScope.launch(Dispatchers.IO) { thumbnailStore.clearAll() }
        // No replacement tab: zero tabs is a valid state. BrowserScreen
        // shows the Start Page until the user opens a site or a new tab.
    }

    private fun undoCloseAllTabs() {
        if (lastCloseAllIds.isEmpty()) return
        val batchIds = lastCloseAllIds
        lastCloseAllIds = emptySet()
        // Only the Close All batch comes back, in its original tab order;
        // anything closed individually stays in Recently Closed.
        val batch = _recentlyClosed.value.filter { it.id in batchIds }
        if (batch.isEmpty()) return
        _recentlyClosed.value = _recentlyClosed.value.filterNot { it.id in batchIds }
        requestPersistTabs()
        var lastTabId: String? = null
        batch.forEach { info ->
            lastTabId = createTab(
                url = info.url,
                isPrivate = info.isPrivate,
                desktopMode = info.desktopMode,
                makeActive = false,
            )
        }
        lastTabId?.let { switchTab(it) }
    }

    // ---- Tab lifecycle -------------------------------------------------------

    private fun createTab(
        url: String?,
        isPrivate: Boolean,
        desktopMode: Boolean,
        makeActive: Boolean = true,
        startPage: Boolean = false,
        openerTabId: String? = null,
    ): String? {
        val currentEngine = engine ?: return null
        val showStart = startPage || url.isNullOrBlank()
        val target = if (showStart) {
            ""
        } else {
            resolveUserInput(url.orEmpty())
        }
        // Firefox-style lazy sessions: tab *state* exists immediately, but
        // an engine session is only created when the tab is actually going
        // to be rendered. A background tab therefore costs no content
        // process until the user first selects it.
        val session = if (makeActive) {
            try {
                currentEngine.createSession(
                    BrowserSessionConfig(
                        isPrivate = isPrivate,
                        desktopMode = desktopMode,
                    ),
                )
            } catch (e: Exception) {
                _engineError.value = e.message ?: e.javaClass.simpleName
                return null
            }
        } else {
            null
        }
        _engineError.value = null
        val entry = TabEntry(
            tabId = newTabId(),
            session = session,
            isPrivate = isPrivate,
            desktopMode = desktopMode,
            lastUrl = target,
            showStartPage = showStart,
            openerTabId = openerTabId,
        )
        _tabs.update { it + entry }
        touchLru(entry.tabId)
        if (session != null) {
            attachSession(entry, session)
            if (target.isNotBlank()) session.loadUrl(target)
        }
        if (makeActive) activateTab(entry.tabId)
        updateWarmPriorities()
        return entry.tabId
    }

    private fun restoreTab(entry: TabEntry) {
        val currentEngine = engine ?: return
        val config = BrowserSessionConfig(
            isPrivate = entry.isPrivate,
            desktopMode = entry.desktopMode,
        )
        val saved = entry.savedState
        var restoredFromState = false
        val session = try {
            if (saved != null) {
                currentEngine.restoreSession(config, saved)
                    .also { restoredFromState = true }
            } else {
                currentEngine.createSession(config)
            }
        } catch (e: Exception) {
            // Restore failed (e.g. unreadable saved state): fall back to a
            // fresh session and reopen the tab's last URL below. Silently
            // binding a blank session here is what turned restored tabs
            // into empty pages.
            try {
                currentEngine.createSession(config)
            } catch (e2: Exception) {
                _engineError.value = e2.message ?: e2.javaClass.simpleName
                return
            }
        }
        entry.session = session
        attachSession(entry, session)
        if (!restoredFromState && entry.lastUrl.isNotBlank()) {
            // No (usable) saved navigation state: reopen the page.
            session.loadUrl(entry.lastUrl)
        }
        entry.savedState = null
        updateWarmPriorities()
    }

    private fun activateTab(tabId: String) {
        val previousId = _activeTabId.value
        if (previousId == tabId) return
        // Snapshot the outgoing tab for the tab switcher while it is still
        // the rendered one (Firefox captures tab thumbnails on switch,
        // not after every page load).
        if (previousId != null) captureThumbnail(previousId)
        // GeckoView itself marks the rendered session active/inactive as its
        // surface is acquired/released during bindContentView(). Toggling
        // sessions manually here raced that surface lifecycle and could leave
        // a previously shown tab with a dead compositor (black until reload).
        _findVisible.value = false
        _pageContextMenu.value = null
        _activeTabId.value = tabId
        touchLru(tabId)
        updateWarmPriorities()
    }

    /**
     * Gecko prioritisation, exactly Mozilla's
     * SessionPrioritizationMiddleware: only the *selected* tab is high
     * priority; every other session stays default priority. Keeping a
     * whole warm set high (the old behaviour) boosts several content
     * processes at once, which makes aggressive OEM memory managers more
     * likely to kill the app process itself. Inactive tabs are cheap to
     * bring back from their saved engine state instead.
     */
    private fun updateWarmPriorities() {
        val activeId = _activeTabId.value
        _tabs.value.forEach { entry ->
            entry.session?.setPriorityHigh(entry.tabId == activeId)
        }
    }

    private fun suspendTab(entry: TabEntry) {
        val session = entry.session ?: return
        if (entry.tabId == _activeTabId.value) return
        captureThumbnail(entry.tabId)
        entry.savedState = session.latestSavedState() ?: entry.savedState
        sessionJobs.remove(entry.tabId)?.cancel()
        session.close()
        entry.session = null
    }

    /**
     * The tab's content process crashed or was killed (Gecko
     * `onCrash`/`onKill`), or was found closed although no event arrived.
     * Following Firefox's CrashMiddleware, the dead session is *suspended*
     * (saved state kept, session dropped) and never blindly recreated:
     *  - a death the user witnessed (app in the foreground, real page)
     *    marks the tab crashed; it is restored only when the user
     *    explicitly restores or selects it, so a crashing page cannot
     *    loop the browser;
     *  - a death that happened quietly in the background (system killed
     *    the process while minimised) is restored silently at the moment
     *    the tab is needed again — on foreground return or selection.
     */
    private fun handleDeadSession(
        entry: TabEntry,
        deadSession: BrowserSession,
        witnessed: Boolean = appInForeground,
    ) {
        if (entry.session !== deadSession) return
        clearPendingForTab(entry.tabId)
        entry.savedState = deadSession.latestSavedState() ?: entry.savedState
        sessionJobs.remove(entry.tabId)?.cancel()
        deadSession.close()
        entry.session = null
        entry.crashed = witnessed && entry.lastUrl.isNotBlank()
        if (!entry.crashed && appInForeground &&
            entry.tabId == _activeTabId.value
        ) {
            // Nothing worth showing a crash state for (e.g. a start-page
            // tab): recreate it right away.
            restoreTab(entry)
        }
    }

    private fun attachSession(entry: TabEntry, session: BrowserSession) {
        _pageStates.update { it + (entry.tabId to session.state.value) }
        sessionJobs[entry.tabId] = viewModelScope.launch {
            launch {
                var wasLoading = false
                var autofillOrigin: String? = null
                session.state.collect { state ->
                    _pageStates.update { it + (entry.tabId to state) }
                    if (state.url.isNotBlank()) entry.lastUrl = state.url
                    if (state.title.isNotBlank()) entry.lastTitle = state.title
                    val newOrigin = CredentialOrigin.fromUrl(state.url)
                    if (newOrigin != autofillOrigin) {
                        autofillOrigin = newOrigin
                        // Never leave the previous origin's credential
                        // offered while a navigation is in flight; the new
                        // origin's login is installed only after this load
                        // finishes (see finishedLoad below).
                        session.setAutofillCredential(null)
                    }
                    val finishedLoad = wasLoading && !state.isLoading
                    wasLoading = state.isLoading
                    if (finishedLoad && !state.loadFailed) {
                        if (!entry.isPrivate && UrlUtils.isHttpUrl(state.url)) {
                            historyRepository.recordVisit(state.url, state.title)
                            refreshShortcutIconsForLoadedPage(state.url)
                            requestPersistTabs()
                        }
                        if (!entry.isPrivate &&
                            settings.value.passwordManagerEnabled &&
                            newOrigin != null
                        ) {
                            viewModelScope.launch {
                                loadSavedCredentialFor(entry, newOrigin, session)
                            }
                        }
                        if (state.url.isNotBlank() &&
                            lastThumbnailUrl[entry.tabId] != state.url
                        ) {
                            lastThumbnailUrl[entry.tabId] = state.url
                            captureThumbnail(entry.tabId)
                        }
                    }
                }
            }
            launch {
                session.events.collect { event ->
                    when (event) {
                        is BrowserSessionEvent.DownloadRequested -> {
                            val emitted = _downloadEvents.tryEmit(event)
                            if (!emitted) {
                                // The one-shot response body has no other
                                // owner; close it instead of leaking the
                                // connection until garbage collection.
                                runCatching { event.bodySource?.invoke()?.close() }
                            }
                        }
                        is BrowserSessionEvent.FilePickerRequested -> {
                            if (entry.tabId != _activeTabId.value) {
                                session.cancelFilePicker(event.requestId)
                            } else {
                                pendingFilePickerSessions[event.requestId] = session
                                _filePickerRequests.tryEmit(
                                    FilePickerRequest(
                                        requestId = event.requestId,
                                        allowMultiple = event.allowMultiple,
                                        isFolder = event.isFolder,
                                        mimeTypes = event.mimeTypes,
                                        captureMode = event.captureMode,
                                    ),
                                )
                            }
                        }
                        is BrowserSessionEvent.ContextMenuRequested -> {
                            if (entry.tabId == _activeTabId.value) {
                                _pageContextMenu.value = PageContextMenu(
                                    title = event.label.orEmpty(),
                                    linkUri = event.linkUri,
                                    mediaUri = event.mediaUri,
                                    mediaKind = event.mediaKind,
                                )
                            }
                        }
                        is BrowserSessionEvent.SitePermissionRequested ->
                            handleSitePermission(entry.tabId, event)
                        is BrowserSessionEvent.MediaPermissionRequested ->
                            handleMediaPermission(entry.tabId, event)
                        is BrowserSessionEvent.AndroidPermissionRequested ->
                            handleAndroidPermission(entry.tabId, event)
                        is BrowserSessionEvent.WebPromptRequested ->
                            handleWebPrompt(entry.tabId, event)
                        is BrowserSessionEvent.CredentialSubmitted ->
                            handleCredentialSubmitted(entry, event)
                        is BrowserSessionEvent.PrintRequested -> {
                            val emitted = _pdfPrintEvents.tryEmit(
                                PdfPrintReady(
                                    fileName = pdfFileName(entry),
                                    bodySource = { event.bodySource },
                                ),
                            )
                            if (!emitted) {
                                runCatching { event.bodySource.close() }
                            }
                        }
                        is BrowserSessionEvent.ExternalLinkRequested ->
                            _externalLinkEvents.tryEmit(event.uri)
                        is BrowserSessionEvent.CloseRequested ->
                            closeTab(event.sessionId.toTabId())
                        is BrowserSessionEvent.SessionDied ->
                            handleDeadSession(entry, session)
                    }
                }
            }
        }
    }

    /** After a site finishes loading, cache its icon for matching shortcuts. */
    private fun refreshShortcutIconsForLoadedPage(pageUrl: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val matchingIds = shortcutRepository.shortcuts.first()
                .filter { shortcut ->
                    val lastAttempt = shortcutIconLastAttemptAt[shortcut.id] ?: 0L
                    !_shortcutIcons.value.containsKey(shortcut.id) &&
                        shortcut.id !in shortcutIconFetchesInFlight &&
                        now - lastAttempt >= SHORTCUT_ICON_RETRY_MILLIS &&
                        hostsRelated(shortcut.url, pageUrl)
                }
                .map { it.id }
            if (matchingIds.isEmpty()) return@launch

            // One network fetch serves every shortcut for this site; failed
            // lookups are throttled so ordinary navigation cannot repeatedly
            // download the same page/icon candidates in the background.
            shortcutIconFetchesInFlight += matchingIds
            try {
                fetchShortcutIcon(matchingIds, pageUrl)
            } finally {
                shortcutIconFetchesInFlight -= matchingIds.toSet()
            }
        }
    }

    private suspend fun fetchShortcutIcon(shortcutIds: Collection<Long>, pageUrl: String) {
        if (shortcutIds.isEmpty()) return
        val attemptedAt = System.currentTimeMillis()
        shortcutIds.forEach { shortcutIconLastAttemptAt[it] = attemptedAt }
        val icon = faviconFetcher.fetch(pageUrl) ?: return
        withContext(Dispatchers.IO) {
            shortcutIds.forEach { shortcutIconStore.save(it, icon) }
        }
        _shortcutIcons.update { icons ->
            icons + shortcutIds.associateWith { icon }
        }
    }

    /** The already-loaded page URL when it belongs to [shortcutUrl]'s site. */
    private fun loadedPageUrlForShortcut(shortcutUrl: String): String? {
        val entry = activeEntry() ?: return null
        if (entry.isPrivate) return null
        val state = _pageStates.value[entry.tabId] ?: return null
        val pageUrl = state.url.ifBlank { entry.lastUrl }
        if (pageUrl.isBlank() || state.isLoading || state.loadFailed) return null
        return pageUrl.takeIf { hostsRelated(shortcutUrl, it) }
    }

    private fun hostsRelated(firstUrl: String, secondUrl: String): Boolean {
        val firstHost = runCatching { Uri.parse(firstUrl).host }
            .getOrNull()?.lowercase()?.removePrefix("www.") ?: return false
        val secondHost = runCatching { Uri.parse(secondUrl).host }
            .getOrNull()?.lowercase()?.removePrefix("www.") ?: return false
        return firstHost == secondHost ||
            firstHost.endsWith(".$secondHost") ||
            secondHost.endsWith(".$firstHost")
    }

    /** Snapshots the visible tab; called as the user opens the tab tray. */
    fun captureActiveThumbnail() {
        val activeId = _activeTabId.value ?: return
        // A Start Page tab has no rendered page worth previewing, and a
        // crashed tab's session is already gone.
        val entry = entry(activeId) ?: return
        if (entry.showStartPage || entry.crashed) return
        captureThumbnail(activeId)
    }

    private fun captureThumbnail(tabId: String) {
        val tabEntry = entry(tabId) ?: return
        // Private tabs never get a persisted screenshot: a thumbnail would
        // leak page content into the tab switcher and onto disk.
        if (tabEntry.isPrivate) return
        val session = tabEntry.session ?: return
        session.captureThumbnail { bitmap ->
            if (bitmap == null) return@captureThumbnail
            // The capture is asynchronous: by the time it lands, the tab
            // may have been closed or suspended (its thumbnail delete is
            // racing us on the IO dispatcher). Never resurrect an orphaned
            // preview or a stale version counter for a session that is no
            // longer this tab's live one.
            if (entry(tabId)?.session !== session) return@captureThumbnail
            viewModelScope.launch(Dispatchers.IO) {
                if (entry(tabId)?.session !== session) return@launch
                thumbnailStore.save(tabId, bitmap)
                _thumbnailVersions.update { versions ->
                    versions + (tabId to ((versions[tabId] ?: 0) + 1))
                }
            }
        }
    }

    // ---- Small helpers ---------------------------------------------------------

    private fun ThemeMode.toWebColorScheme(): WebColorScheme = when (this) {
        ThemeMode.SYSTEM -> WebColorScheme.SYSTEM
        ThemeMode.LIGHT -> WebColorScheme.LIGHT
        ThemeMode.DARK, ThemeMode.HEXORA -> WebColorScheme.DARK
    }

    /**
     * Ranks browsing-history matches for [query]. Host/URL prefix matches
     * come first, then URL and title substring matches; Room already returns
     * entries newest-first, and the sort is stable, so equal ranks stay
     * recency-ordered.
     */
    private fun buildAddressSuggestions(
        history: List<HistoryEntity>,
        bookmarks: List<BookmarkEntity>,
        query: String,
    ): List<AddressSuggestion> {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return emptyList()
        val bookmarkUrls = bookmarks.map { it.url }.toSet()
        val bookmarkSuggestions = bookmarks.mapNotNull { bookmark ->
            val text = (bookmark.title + " " + bookmark.url).lowercase()
            if (!text.contains(needle)) {
                null
            } else {
                AddressSuggestion(
                    title = bookmark.title.ifBlank { bookmark.url },
                    url = bookmark.url,
                    isBookmark = true,
                )
            }
        }
        val historySuggestions = history
            .mapNotNull { entry ->
                val url = entry.url
                val host = runCatching { Uri.parse(url).host }
                    .getOrNull()
                    ?.removePrefix("www.")
                    .orEmpty()
                val urlText = url.lowercase()
                    .removePrefix("https://")
                    .removePrefix("http://")
                val title = entry.title.lowercase()
                val rank = when {
                    host.startsWith(needle) -> 0
                    urlText.startsWith(needle) -> 1
                    title.startsWith(needle) -> 2
                    urlText.contains(needle) -> 3
                    title.contains(needle) -> 4
                    else -> return@mapNotNull null
                }
                rank to AddressSuggestion(
                    title = entry.title.ifBlank { host.ifBlank { url } },
                    url = url,
                    isBookmark = url in bookmarkUrls,
                )
            }
            .sortedBy { it.first }
            .map { it.second }
        return (bookmarkSuggestions + historySuggestions)
            .distinctBy { it.url }
            .take(MAX_ADDRESS_SUGGESTIONS)
    }

    private fun newTabId(): String = UUID.randomUUID().toString()

    private fun entry(tabId: String): TabEntry? =
        _tabs.value.firstOrNull { it.tabId == tabId }

    private fun activeEntry(): TabEntry? =
        _activeTabId.value?.let { entry(it) }

    private fun activeSession(): BrowserSession? = activeEntry()?.session

    private fun touchLru(tabId: String) {
        lruOrder.remove(tabId)
        lruOrder.add(tabId)
        entry(tabId)?.lastAccessMs = System.currentTimeMillis()
    }

    /** Download/close events carry the *session* id; map it back to a tab id. */
    private fun String.toTabId(): String =
        _tabs.value.firstOrNull { it.session?.id == this }?.tabId ?: this

    override fun onCleared() {
        engine?.newSessionCreatedListener = null
        pendingSitePermissions.values.forEach { it.deny() }
        pendingSitePermissions.clear()
        pendingAndroidPermissions.values.forEach { it.deny() }
        pendingAndroidPermissions.clear()
        pendingWebPrompts.values.forEach { it.dismiss() }
        pendingWebPrompts.clear()
        pendingFilePickerSessions.keys.toList().forEach { requestId ->
            pendingFilePickerSessions.remove(requestId)?.cancelFilePicker(requestId)
        }
        sessionJobs.values.forEach { it.cancel() }
        sessionJobs.clear()
        _tabs.value.forEach { it.session?.close() }
        super.onCleared()
    }

    private data class TabsSlice(
        val tabs: List<TabEntry>,
        val activeId: String?,
        val states: Map<String, SessionPageState>,
        val thumbnails: Map<String, Int>,
    )

    private data class ChromeSlice(
        val findVisible: Boolean,
        val userFullscreen: Boolean,
        val recentlyClosedCount: Int,
        val pendingCredentialSave: CredentialSavePrompt?,
        val pageContextMenu: PageContextMenu?,
    )

    private data class StartSlice(
        val chrome: ChromeSlice,
        val shortcuts: List<StartShortcut>,
        val icons: Map<Long, Bitmap>,
        val addressSuggestions: List<AddressSuggestion>,
    )

    companion object {
        /**
         * Inactive tabs (besides the selected one) whose sessions are kept
         * alive under memory pressure — Mozilla's MIN_ACTIVE_TABS.
         */
        const val SUSPEND_KEEP_INACTIVE_TABS = 3

        /**
         * After this long in the background the rendered surface is
         * recreated on return even when all sessions are still alive.
         */
        const val REBIND_AFTER_BACKGROUND_MS = 30_000L

        /** Quiet period after which requested tab-strip writes are flushed. */
        const val PERSIST_DEBOUNCE_MS = 400L
        const val MAX_ADDRESS_SUGGESTIONS = 6
        const val INACTIVE_TAB_AFTER_MS = 4 * 60 * 60 * 1000L
        const val MAX_RECENTLY_CLOSED_TABS = 20
        const val SHORTCUT_ICON_RETRY_MILLIS = 6 * 60 * 60 * 1000L
    }
}
