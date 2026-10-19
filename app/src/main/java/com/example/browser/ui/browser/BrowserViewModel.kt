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
import com.example.browser.browser.WebColorScheme
import com.example.browser.browser.SavedTabState
import com.example.browser.browser.SessionPageState
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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns all tabs and coordinates between the [BrowserEngine], repositories and
 * the UI. Business logic lives here — never in Composables.
 *
 * Tabs are identified by a stable tab id (not by their engine session).
 * Following Mozilla's Android Components model, tab sessions are *not*
 * suspended during ordinary tab switching or while the app is merely in
 * the background. Only genuine low-memory signals may suspend sessions
 * outside the warm set (the selected tab plus the [WARM_INACTIVE_TABS]
 * most recently used inactive tabs); a suspended tab keeps its saved
 * navigation state + thumbnail and is restored when selected.
 */
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
        /** Tab that caused this page-opened tab to be created, if any. */
        val openerTabId: String? = null,
    )

    private data class ClosedTabInfo(
        val url: String,
        val title: String,
        val isPrivate: Boolean,
        val desktopMode: Boolean,
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

    /** A page is waiting for the system file picker. */
    data class FilePickerRequest(
        val requestId: String,
        val allowMultiple: Boolean,
        val isFolder: Boolean,
        val mimeTypes: List<String>,
    )

    private val _filePickerRequests =
        MutableSharedFlow<FilePickerRequest>(extraBufferCapacity = 4)
    val filePickerRequests: SharedFlow<FilePickerRequest> =
        _filePickerRequests.asSharedFlow()

    private val pendingFilePickerSessions =
        mutableMapOf<String, BrowserSession>()

    private val _pageContextMenu = MutableStateFlow<PageContextMenu?>(null)

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

    private val startSlice = combine(
        chromeSlice,
        shortcutRepository.shortcuts,
        _shortcutIcons,
        historyRepository.history,
        _addressQuery,
    ) { chrome, shortcuts, icons, history, addressQuery ->
        StartSlice(
            chrome = chrome,
            shortcuts = shortcuts
                .take(StartShortcutRepository.MAX_SHORTCUTS)
                .map { StartShortcut(it.id, it.title, it.url) },
            icons = icons,
            addressSuggestions = buildAddressSuggestions(history, addressQuery),
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
            isPrivate = activeEntry?.isPrivate ?: false,
            loadFailed = activeState.loadFailed,
            isBookmarked = bookmarked,
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
                start.addressSuggestions.filter { it.url != activeState.url }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUiState())

    private var pendingExternalUrl: String? = null

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

    init {
        // Tab thumbnails are not restored after process death, so screenshots
        // from a previous process would only be orphaned page images. (The
        // tab strip itself — URLs/titles — is restored via TabSessionStore.)
        viewModelScope.launch(Dispatchers.IO) { thumbnailStore.clearAll() }

        viewModelScope.launch {
            val initialSettings = settingsRepository.settings.first()
            val createdEngine = engineFactory(initialSettings.javaScriptEnabled)
            engine = createdEngine
            createdEngine.setTextZoomPercent(initialSettings.textZoomPercent)
            createdEngine.setPreferredColorScheme(initialSettings.themeMode.toWebColorScheme())
            createdEngine.newSessionCreatedListener = { session, openerSessionId ->
                // A page opened a new window: adopt the engine-made session.
                // Remember its real opener so hardware Back at the new tab's
                // history root can close it and return to that tab.
                val openerTabId = openerSessionId?.let { sessionId ->
                    _tabs.value.firstOrNull { it.session?.id == sessionId }?.tabId
                } ?: _activeTabId.value
                val entry = TabEntry(
                    tabId = newTabId(),
                    session = session,
                    isPrivate = session.isPrivate,
                    desktopMode = initialSettings.desktopMode,
                    openerTabId = openerTabId,
                )
                _tabs.update { it + entry }
                touchLru(entry.tabId)
                attachSession(entry, session)
                activateTab(entry.tabId)
            }
            createdEngine.onTabSwipe = { delta -> handleFullscreenTabSwipe(delta) }
            restorePersistedTabs(initialSettings.desktopMode)
            pendingExternalUrl?.let { url ->
                pendingExternalUrl = null
                openNewTab(url = url, isPrivate = false)
            }
        }
        // Keep the persisted tab strip in sync so a cold start (e.g. after
        // the system killed the backgrounded process) restores the tabs the
        // user had open. Private tabs are never persisted.
        viewModelScope.launch {
            combine(_tabs, _activeTabId) { tabs, activeId -> tabs to activeId }
                .collect { persistTabsSnapshot() }
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
                                val saved = credentialRepository.findForOrigin(origin)
                                    .firstOrNull()
                                if (entry.session === session) {
                                    session.setAutofillCredential(
                                        saved?.let {
                                            AutofillCredential(it.username, it.password)
                                        },
                                    )
                                }
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
                    if (entry.session == null) restoreTab(entry)
                    entry.session?.loadUrl(
                        UrlUtils.resolveInput(action.input, settings.value.searchEngine),
                    )
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
            is BrowserAction.CloseTab -> closeTab(action.tabId)
            BrowserAction.CloseTabAndReturnToOpener -> closeCurrentTabToOpener()
            BrowserAction.ToggleBookmark -> toggleBookmark()
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
                    val resolvedUrl = UrlUtils.resolveInput(
                        action.url,
                        settings.value.searchEngine,
                    )
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
                    val resolvedUrl = UrlUtils.resolveInput(
                        action.url,
                        settings.value.searchEngine,
                    )
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
            // and no death event reached us (or it was missed): recreate
            // the session from saved state / last URL instead of binding a
            // dead one, which rendered a white page even Refresh could not
            // revive.
            recoverDeadSession(entry, session)
        }
        if (entry.session == null) restoreTab(entry)
        activateTab(tabId)
        updateWarmPriorities()
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
        if (entry.session == null) restoreTab(entry)
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
            if (entry.session == null) restoreTab(entry)
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
            if (entry.session == null) restoreTab(entry)
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
        if (engine == null) {
            pendingExternalUrl = url
        } else {
            openNewTab(url = url, isPrivate = false)
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

    private fun savePendingCredential() {
        val pending = pendingCredential ?: return
        pendingCredential = null
        _pendingCredentialPrompt.value = null
        viewModelScope.launch {
            credentialRepository.save(
                origin = pending.origin,
                username = pending.username,
                password = pending.password,
            )
            // Offer the just-saved login to the still-open login page.
            val entry = activeEntry() ?: return@launch
            if (CredentialOrigin.fromUrl(entry.lastUrl) == pending.origin) {
                entry.session?.setAutofillCredential(
                    AutofillCredential(pending.username, pending.password),
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
            val existing = credentialRepository.findForOrigin(origin)
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
     * Called by the Activity only on genuine low-memory signals. Mirrors
     * Mozilla's TrimMemoryMiddleware: the selected tab and the
     * [WARM_INACTIVE_TABS] most recently used inactive tabs are never
     * suspended; only colder sessions are closed (their saved state and
     * thumbnail are kept, so they restore when selected again).
     */
    fun suspendTabsOutsideWarmSet() {
        val activeId = _activeTabId.value
        val warmInactiveIds = lruOrder
            .filter { it != activeId }
            .takeLast(WARM_INACTIVE_TABS)
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
    fun onAppForegrounded(backgroundDurationMs: Long = 0L) {
        var activeRecovered = false
        _tabs.value.forEach { entry ->
            val session = entry.session ?: return@forEach
            if (!session.isUsable()) {
                if (entry.tabId == _activeTabId.value) activeRecovered = true
                recoverDeadSession(entry, session)
            }
        }
        val active = activeEntry() ?: return
        if (active.session == null) {
            restoreTab(active)
            return
        }
        if (!activeRecovered &&
            backgroundDurationMs >= REBIND_AFTER_BACKGROUND_MS
        ) {
            // The sessions survived the background stay but the native
            // surface did not: pages keep loading yet paint white, and
            // even Refresh cannot revive them (only a round trip to the
            // tab switcher, which recreates the surface, used to help).
            // Recreate the surface proactively after a long backgrounding.
            engine?.rebindRenderedContentView()
        }
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
        val persisted = runCatching { tabSessionStore.load() }.getOrNull()
        if (persisted == null || persisted.tabs.isEmpty()) {
            createTab(
                url = null,
                isPrivate = false,
                desktopMode = desktopMode,
                startPage = true,
            )
            return
        }
        val entries = persisted.tabs.map { saved ->
            TabEntry(
                tabId = newTabId(),
                session = null,
                isPrivate = false,
                desktopMode = desktopMode,
                lastUrl = saved.url,
                lastTitle = saved.title,
                showStartPage = false,
            )
        }
        _tabs.update { it + entries }
        entries.forEach { touchLru(it.tabId) }
        val active = entries[persisted.activeIndex.coerceIn(entries.indices)]
        _activeTabId.value = active.tabId
        restoreTab(active)
        updateWarmPriorities()
    }

    /** Writes the current (non-private) tab strip to [tabSessionStore]. */
    private suspend fun persistTabsSnapshot() {
        val entries = _tabs.value.filterNot { it.isPrivate || it.lastUrl.isBlank() }
        val activeIndex = entries.indexOfFirst { it.tabId == _activeTabId.value }
        runCatching {
            tabSessionStore.save(
                tabs = entries.map { entry ->
                    PersistedTab(url = entry.lastUrl, title = entry.lastTitle)
                },
                activeIndex = if (activeIndex >= 0) activeIndex else 0,
            )
        }
    }

    // ---- Action helpers ------------------------------------------------------

    private fun toggleBookmark() {
        val entry = activeEntry() ?: return
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
            if (url.isBlank()) {
                null
            } else {
                ClosedTabInfo(url, entry.lastTitle, entry.isPrivate, entry.desktopMode)
            }
        }
        _tabs.value.forEach { entry ->
            sessionJobs.remove(entry.tabId)?.cancel()
            entry.session?.close()
        }
        _tabs.value = emptyList()
        _pageStates.value = emptyMap()
        lruOrder.clear()
        lastThumbnailUrl.clear()
        _activeTabId.value = null
        _recentlyClosed.value = closed
        _findVisible.value = false
        _userFullscreen.value = false
        viewModelScope.launch(Dispatchers.IO) { thumbnailStore.clearAll() }
        // No replacement tab: zero tabs is a valid state. BrowserScreen
        // shows the Start Page until the user opens a site or a new tab.
    }

    private fun undoCloseAllTabs() {
        val closed = _recentlyClosed.value
        if (closed.isEmpty()) return
        _recentlyClosed.value = emptyList()
        var lastTabId: String? = null
        closed.forEach { info ->
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
        val session = try {
            currentEngine.createSession(
                BrowserSessionConfig(isPrivate = isPrivate, desktopMode = desktopMode),
            )
        } catch (e: Exception) {
            _engineError.value = e.message ?: e.javaClass.simpleName
            return null
        }
        _engineError.value = null
        val entry = TabEntry(
            tabId = newTabId(),
            session = session,
            isPrivate = isPrivate,
            desktopMode = desktopMode,
            showStartPage = startPage || url.isNullOrBlank(),
            openerTabId = openerTabId,
        )
        _tabs.update { it + entry }
        touchLru(entry.tabId)
        attachSession(entry, session)
        if (makeActive) activateTab(entry.tabId)
        if (!entry.showStartPage) {
            val target = url.orEmpty()
            if (target.isNotBlank()) {
                session.loadUrl(UrlUtils.resolveInput(target, settings.value.searchEngine))
            }
        }
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
     * Gecko prioritisation, following Mozilla's
     * SessionPrioritizationMiddleware: only a small warm set — the selected
     * tab plus the [WARM_INACTIVE_TABS] most recently used tabs — is marked
     * high priority, so Gecko/Android keep those pages alive instead of
     * discarding and reloading them. Marking *every* session high (the old
     * behaviour) boosts every content process at once and makes the system
     * kill them under memory pressure.
     */
    private fun updateWarmPriorities() {
        val activeId = _activeTabId.value
        val warmIds = buildSet {
            activeId?.let { add(it) }
            addAll(
                lruOrder
                    .filter { it != activeId }
                    .takeLast(WARM_INACTIVE_TABS),
            )
        }
        _tabs.value.forEach { entry ->
            entry.session?.setPriorityHigh(entry.tabId in warmIds)
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
     * The tab's content process crashed or was killed while backgrounded
     * (Gecko `onCrash`/`onKill`): the session is closed and unusable, but
     * the *tab* must survive. Following Firefox's crash handling, drop the
     * dead session and restore the tab from its latest saved state (or its
     * last URL). The active tab is recreated immediately so returning to
     * the app repaints the page instead of showing the dead session's
     * black/empty surface; an inactive tab is simply left suspended and
     * restores when selected.
     */
    private fun recoverDeadSession(entry: TabEntry, deadSession: BrowserSession) {
        if (entry.session !== deadSession) return
        entry.savedState = deadSession.latestSavedState() ?: entry.savedState
        sessionJobs.remove(entry.tabId)?.cancel()
        deadSession.close()
        entry.session = null
        if (entry.tabId == _activeTabId.value) {
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
                        if (newOrigin == null || entry.isPrivate ||
                            !settings.value.passwordManagerEnabled
                        ) {
                            session.setAutofillCredential(null)
                        } else {
                            viewModelScope.launch {
                                val saved = credentialRepository.findForOrigin(newOrigin)
                                    .firstOrNull()
                                if (entry.session === session) {
                                    session.setAutofillCredential(
                                        saved?.let {
                                            AutofillCredential(it.username, it.password)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    val finishedLoad = wasLoading && !state.isLoading
                    wasLoading = state.isLoading
                    if (finishedLoad && !state.loadFailed) {
                        if (!entry.isPrivate && UrlUtils.isHttpUrl(state.url)) {
                            historyRepository.recordVisit(state.url, state.title)
                            refreshShortcutIconsForLoadedPage(state.url)
                            persistTabsSnapshot()
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
                        is BrowserSessionEvent.DownloadRequested ->
                            _downloadEvents.tryEmit(event)
                        is BrowserSessionEvent.FilePickerRequested -> {
                            pendingFilePickerSessions[event.requestId] = session
                            _filePickerRequests.tryEmit(
                                FilePickerRequest(
                                    requestId = event.requestId,
                                    allowMultiple = event.allowMultiple,
                                    isFolder = event.isFolder,
                                    mimeTypes = event.mimeTypes,
                                ),
                            )
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
                        is BrowserSessionEvent.CredentialSubmitted ->
                            handleCredentialSubmitted(entry, event)
                        is BrowserSessionEvent.CloseRequested ->
                            closeTab(event.sessionId.toTabId())
                        is BrowserSessionEvent.SessionDied ->
                            recoverDeadSession(entry, session)
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

    private fun captureThumbnail(tabId: String) {
        val tabEntry = entry(tabId) ?: return
        // Private tabs never get a persisted screenshot: a thumbnail would
        // leak page content into the tab switcher and onto disk.
        if (tabEntry.isPrivate) return
        val session = tabEntry.session ?: return
        session.captureThumbnail { bitmap ->
            if (bitmap == null) return@captureThumbnail
            viewModelScope.launch(Dispatchers.IO) {
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
        query: String,
    ): List<AddressSuggestion> {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return emptyList()
        return history
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
                )
            }
            .sortedBy { it.first }
            .take(MAX_ADDRESS_SUGGESTIONS)
            .map { it.second }
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
    }

    /** Download/close events carry the *session* id; map it back to a tab id. */
    private fun String.toTabId(): String =
        _tabs.value.firstOrNull { it.session?.id == this }?.tabId ?: this

    override fun onCleared() {
        engine?.newSessionCreatedListener = null
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
        /** Inactive tabs (besides the selected one) kept at high priority. */
        const val WARM_INACTIVE_TABS = 3

        /**
         * After this long in the background the rendered surface is
         * recreated on return even when all sessions are still alive.
         */
        const val REBIND_AFTER_BACKGROUND_MS = 30_000L
        const val MAX_ADDRESS_SUGGESTIONS = 6
        const val SHORTCUT_ICON_RETRY_MILLIS = 6 * 60 * 60 * 1000L
    }
}
