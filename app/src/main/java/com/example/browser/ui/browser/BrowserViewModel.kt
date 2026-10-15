package com.example.browser.ui.browser

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.browser.BrowserEngine
import com.example.browser.browser.BrowserSession
import com.example.browser.browser.BrowserSessionConfig
import com.example.browser.browser.BrowserSessionEvent
import com.example.browser.browser.SavedTabState
import com.example.browser.browser.SessionPageState
import com.example.browser.data.repository.BookmarkRepository
import com.example.browser.data.repository.HistoryRepository
import com.example.browser.data.settings.BrowserSettingsState
import com.example.browser.data.settings.SettingsRepository
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

/**
 * Owns all tabs and coordinates between the [BrowserEngine], repositories and
 * the UI. Business logic lives here — never in Composables.
 *
 * Tabs are identified by a stable tab id (not by their engine session):
 * inactive tabs beyond [MAX_LIVE_SESSIONS] are *suspended* — their engine
 * session is closed and only the saved navigation state + a thumbnail are
 * kept, so hundreds of tabs can stay "open" without holding memory. A
 * suspended tab is restored transparently when selected.
 */
class BrowserViewModel(
    private val engineFactory: (javaScriptEnabled: Boolean) -> BrowserEngine,
    private val historyRepository: HistoryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsRepository: SettingsRepository,
    private val thumbnailStore: TabThumbnailStore,
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

    /** Tab ids ordered by last activation; last element = most recently used. */
    private val lruOrder = mutableListOf<String>()

    private val sessionJobs = mutableMapOf<String, Job>()
    private val lastThumbnailUrl = mutableMapOf<String, String>()

    private val _downloadEvents =
        MutableSharedFlow<BrowserSessionEvent.DownloadRequested>(extraBufferCapacity = 16)
    val downloadEvents: SharedFlow<BrowserSessionEvent.DownloadRequested> =
        _downloadEvents.asSharedFlow()

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

    private val chromeSlice = combine(_findVisible, _userFullscreen, _recentlyClosed) { find, fs, closed ->
        ChromeSlice(find, fs, closed.size)
    }

    val uiState: StateFlow<BrowserUiState> = combine(
        tabsSlice,
        _isCurrentBookmarked,
        _engineError,
        chromeSlice,
        settings,
    ) { slice, bookmarked, error, chrome, currentSettings ->
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
            currentUrl = activeState.url.ifBlank { activeEntry?.lastUrl ?: "" },
            pageTitle = activeState.title,
            isLoading = activeState.isLoading,
            progress = activeState.progress,
            canGoBack = activeState.canGoBack,
            canGoForward = activeState.canGoForward,
            isSecure = activeState.isSecure,
            isPrivate = activeEntry?.isPrivate ?: false,
            loadFailed = activeState.loadFailed,
            isBookmarked = bookmarked,
            isDesktopMode = activeEntry?.desktopMode ?: false,
            isFullscreen = chrome.userFullscreen || activeState.isPageFullscreen,
            findVisible = chrome.findVisible,
            findResult = activeState.findResult,
            textZoomPercent = currentSettings.textZoomPercent,
            recentlyClosedCount = chrome.recentlyClosedCount,
            thumbnailVersions = slice.thumbnails,
            errorMessage = error,
            activeSession = activeEntry?.session,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUiState())

    private var pendingExternalUrl: String? = null

    init {
        viewModelScope.launch {
            val initialSettings = settingsRepository.settings.first()
            val createdEngine = engineFactory(initialSettings.javaScriptEnabled)
            engine = createdEngine
            createdEngine.setTextZoomPercent(initialSettings.textZoomPercent)
            createdEngine.newSessionCreatedListener = { session ->
                // A page opened a new window: adopt the engine-made session.
                val entry = TabEntry(
                    tabId = newTabId(),
                    session = session,
                    isPrivate = session.isPrivate,
                    desktopMode = initialSettings.desktopMode,
                )
                _tabs.update { it + entry }
                touchLru(entry.tabId)
                attachSession(entry, session)
                activateTab(entry.tabId)
            }
            createTab(
                url = initialSettings.homepage,
                isPrivate = false,
                desktopMode = initialSettings.desktopMode,
            )
            pendingExternalUrl?.let { url ->
                pendingExternalUrl = null
                openNewTab(url = url, isPrivate = false)
            }
        }
        // Keep the engine's text zoom in sync with the persisted setting.
        viewModelScope.launch {
            settings.collect { current ->
                engine?.setTextZoomPercent(current.textZoomPercent)
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
                activeSession()?.loadUrl(
                    UrlUtils.resolveInput(action.input, settings.value.searchEngine),
                )
            }
            BrowserAction.Reload -> activeSession()?.reload()
            BrowserAction.StopLoading -> activeSession()?.stopLoading()
            BrowserAction.GoBack -> activeSession()?.goBack()
            BrowserAction.GoForward -> activeSession()?.goForward()
            BrowserAction.LoadHome -> activeSession()?.loadUrl(
                UrlUtils.resolveInput(settings.value.homepage, settings.value.searchEngine),
            )
            BrowserAction.NewTab -> openNewTab(isPrivate = false)
            BrowserAction.NewPrivateTab -> openNewTab(isPrivate = true)
            is BrowserAction.SwitchTab -> switchTab(action.tabId)
            is BrowserAction.CloseTab -> closeTab(action.tabId)
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
            is BrowserAction.FindNext -> activeSession()?.findText(action.query, forward = true)
            is BrowserAction.FindPrevious ->
                activeSession()?.findText(action.query, forward = false)
            BrowserAction.ToggleFullscreen ->
                _userFullscreen.update { !it }
            BrowserAction.ExitFullscreen -> _userFullscreen.value = false
            is BrowserAction.SwitchAdjacentTab -> switchAdjacentTab(action.delta)
            BrowserAction.CloseAllTabs -> closeAllTabs()
            BrowserAction.UndoCloseAllTabs -> undoCloseAllTabs()
        }
    }

    fun openNewTab(url: String? = null, isPrivate: Boolean = false) {
        createTab(
            url = url,
            isPrivate = isPrivate,
            desktopMode = settings.value.desktopMode,
        )
    }

    fun switchTab(tabId: String) {
        val entry = entry(tabId) ?: return
        if (entry.session == null) restoreTab(entry)
        activateTab(tabId)
        enforceSuspension()
    }

    fun closeTab(tabId: String) {
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
            _tabs.value.isEmpty() -> {
                _activeTabId.value = null
                openNewTab(isPrivate = false)
            }
            wasActive -> {
                val next = _tabs.value.lastOrNull { it.tabId != tabId } ?: _tabs.value.first()
                switchTab(next.tabId)
            }
        }
    }

    /** Opens [url] (from bookmarks/history/downloads/another app) in the active tab. */
    fun openUrl(url: String) {
        val session = activeSession()
        if (session == null) {
            openNewTab(url = url, isPrivate = false)
        } else {
            session.loadUrl(url)
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

    /** Called by the Activity on memory pressure: free every inactive session. */
    fun suspendAllInactiveTabs() {
        _tabs.value
            .filter { it.session != null && it.tabId != _activeTabId.value }
            .forEach { suspendTab(it) }
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
        viewModelScope.launch(Dispatchers.IO) { thumbnailStore.clearAll() }
        createTab(
            url = settings.value.homepage,
            isPrivate = false,
            desktopMode = settings.value.desktopMode,
        )
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
        )
        _tabs.update { it + entry }
        touchLru(entry.tabId)
        attachSession(entry, session)
        if (makeActive) activateTab(entry.tabId)
        val target = url ?: settings.value.homepage
        if (target.isNotBlank()) {
            session.loadUrl(UrlUtils.resolveInput(target, settings.value.searchEngine))
        }
        enforceSuspension()
        return entry.tabId
    }

    private fun restoreTab(entry: TabEntry) {
        val currentEngine = engine ?: return
        val config = BrowserSessionConfig(
            isPrivate = entry.isPrivate,
            desktopMode = entry.desktopMode,
        )
        val session = try {
            entry.savedState?.let { saved ->
                runCatching { currentEngine.restoreSession(config, saved) }.getOrNull()
            } ?: currentEngine.createSession(config)
        } catch (e: Exception) {
            _engineError.value = e.message ?: e.javaClass.simpleName
            return
        }
        entry.session = session
        attachSession(entry, session)
        if (entry.savedState == null && entry.lastUrl.isNotBlank()) {
            // No saved navigation state (should be rare): reopen the page.
            session.loadUrl(entry.lastUrl)
        }
        entry.savedState = null
    }

    private fun activateTab(tabId: String) {
        val previousId = _activeTabId.value
        if (previousId == tabId) return
        if (previousId != null) {
            entry(previousId)?.session?.setActive(false)
            captureThumbnail(previousId)
        }
        _findVisible.value = false
        _activeTabId.value = tabId
        touchLru(tabId)
        entry(tabId)?.session?.setActive(true)
    }

    /** Suspends least-recently-used inactive tabs beyond the live budget. */
    private fun enforceSuspension() {
        val liveCount = _tabs.value.count { it.session != null }
        if (liveCount <= MAX_LIVE_SESSIONS) return
        val activeId = _activeTabId.value
        val candidates = _tabs.value
            .filter { it.session != null && it.tabId != activeId }
            .sortedBy { lruOrder.indexOf(it.tabId) }
        var remaining = liveCount
        for (entry in candidates) {
            if (remaining <= MAX_LIVE_SESSIONS) break
            suspendTab(entry)
            remaining--
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

    private fun attachSession(entry: TabEntry, session: BrowserSession) {
        _pageStates.update { it + (entry.tabId to session.state.value) }
        sessionJobs[entry.tabId] = viewModelScope.launch {
            launch {
                var wasLoading = false
                session.state.collect { state ->
                    _pageStates.update { it + (entry.tabId to state) }
                    if (state.url.isNotBlank()) entry.lastUrl = state.url
                    if (state.title.isNotBlank()) entry.lastTitle = state.title
                    val finishedLoad = wasLoading && !state.isLoading
                    wasLoading = state.isLoading
                    if (finishedLoad && !state.loadFailed) {
                        if (!entry.isPrivate && UrlUtils.isHttpUrl(state.url)) {
                            historyRepository.recordVisit(state.url, state.title)
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
                        is BrowserSessionEvent.CloseRequested ->
                            closeTab(event.sessionId.toTabId())
                    }
                }
            }
        }
    }

    private fun captureThumbnail(tabId: String) {
        val session = entry(tabId)?.session ?: return
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
    )

    companion object {
        /** Live engine sessions kept warm; older inactive tabs are suspended. */
        const val MAX_LIVE_SESSIONS = 6
    }
}
