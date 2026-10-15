package com.example.browser.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.browser.BrowserEngine
import com.example.browser.browser.BrowserSession
import com.example.browser.browser.BrowserSessionConfig
import com.example.browser.browser.BrowserSessionEvent
import com.example.browser.browser.SessionPageState
import com.example.browser.data.repository.BookmarkRepository
import com.example.browser.data.repository.HistoryRepository
import com.example.browser.data.settings.BrowserSettingsState
import com.example.browser.data.settings.SettingsRepository
import com.example.browser.util.UrlUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns all tabs and coordinates between the [BrowserEngine], repositories and
 * the UI. Business logic lives here — never in Composables (ARCHITECTURE.md §14/§24).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModel(
    private val engineFactory: (javaScriptEnabled: Boolean) -> BrowserEngine,
    private val historyRepository: HistoryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private var engine: BrowserEngine? = null

    private data class TabEntry(val session: BrowserSession)

    private val _tabs = MutableStateFlow<List<TabEntry>>(emptyList())
    private val _activeTabId = MutableStateFlow<String?>(null)
    private val _pageStates = MutableStateFlow<Map<String, SessionPageState>>(emptyMap())
    private val _isCurrentBookmarked = MutableStateFlow(false)
    private val _engineError = MutableStateFlow<String?>(null)

    private val sessionJobs = mutableMapOf<String, Job>()

    /** Sub-pages such as Downloads are shown as one-time events, not state. */
    private val _downloadEvents =
        MutableSharedFlow<BrowserSessionEvent.DownloadRequested>(extraBufferCapacity = 16)
    val downloadEvents: SharedFlow<BrowserSessionEvent.DownloadRequested> =
        _downloadEvents.asSharedFlow()

    val settings: StateFlow<BrowserSettingsState> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        BrowserSettingsState(),
    )

    private val tabsSlice = combine(_tabs, _activeTabId, _pageStates) { tabs, activeId, states ->
        TabsSlice(tabs, activeId, states)
    }

    val uiState: StateFlow<BrowserUiState> = combine(
        tabsSlice,
        _isCurrentBookmarked,
        _engineError,
    ) { slice, bookmarked, error ->
        val activeEntry = slice.tabs.firstOrNull { it.session.id == slice.activeId }
        val activeState = slice.activeId?.let { slice.states[it] } ?: SessionPageState()
        BrowserUiState(
            tabs = slice.tabs.map { entry ->
                val state = slice.states[entry.session.id]
                TabUiItem(
                    id = entry.session.id,
                    title = state?.title?.takeIf { it.isNotBlank() } ?: state?.url ?: "",
                    url = state?.url ?: "",
                    isPrivate = entry.session.isPrivate,
                    isActive = entry.session.id == slice.activeId,
                )
            },
            activeTabId = slice.activeId,
            currentUrl = activeState.url,
            pageTitle = activeState.title,
            isLoading = activeState.isLoading,
            progress = activeState.progress,
            canGoBack = activeState.canGoBack,
            canGoForward = activeState.canGoForward,
            isSecure = activeState.isSecure,
            isPrivate = activeEntry?.session?.isPrivate ?: false,
            loadFailed = activeState.loadFailed,
            isBookmarked = bookmarked,
            errorMessage = error,
            activeSession = activeEntry?.session,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUiState())

    private var pendingExternalUrl: String? = null

    init {
        viewModelScope.launch {
            // The engine needs the persisted JavaScript setting, so it is
            // created from the first settings snapshot.
            val initialSettings = settingsRepository.settings.first()
            val createdEngine = engineFactory(initialSettings.javaScriptEnabled)
            engine = createdEngine
            createdEngine.newSessionCreatedListener = { session ->
                registerTab(session, makeActive = true)
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
        viewModelScope.launch {
            combine(_activeTabId, _pageStates) { activeId, states ->
                activeId?.let { states[it]?.url }
            }.flatMapLatest { url ->
                if (url.isNullOrBlank()) {
                    flowOf(false)
                } else {
                    bookmarkRepository.observeIsBookmarked(url)
                }
            }.collect { bookmarked -> _isCurrentBookmarked.value = bookmarked }
        }
    }

    // ---- Actions -----------------------------------------------------------

    fun onAction(action: BrowserAction) {
        when (action) {
            is BrowserAction.LoadInput -> {
                val session = activeSession() ?: return
                session.loadUrl(UrlUtils.resolveInput(action.input, settings.value.searchEngine))
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
        }
    }

    fun openNewTab(url: String? = null, isPrivate: Boolean = false) {
        createTab(
            url = url,
            isPrivate = isPrivate,
            desktopMode = settings.value.desktopMode,
        )
    }

    private fun createTab(url: String?, isPrivate: Boolean, desktopMode: Boolean) {
        val currentEngine = engine ?: return
        val session = try {
            currentEngine.createSession(
                BrowserSessionConfig(
                    isPrivate = isPrivate,
                    desktopMode = desktopMode,
                ),
            )
        } catch (e: Exception) {
            _engineError.value = e.message ?: e.javaClass.simpleName
            return
        }
        _engineError.value = null
        registerTab(session, makeActive = true)
        val target = url ?: settings.value.homepage
        if (target.isNotBlank()) {
            session.loadUrl(UrlUtils.resolveInput(target, settings.value.searchEngine))
        }
    }

    fun switchTab(tabId: String) {
        if (_activeTabId.value == tabId) return
        activeSession()?.setActive(false)
        _activeTabId.value = tabId
        activeSession()?.setActive(true)
    }

    fun closeTab(tabId: String) {
        val closingIndex = _tabs.value.indexOfFirst { it.session.id == tabId }
        if (closingIndex < 0) return
        val wasActive = _activeTabId.value == tabId

        sessionJobs.remove(tabId)?.cancel()
        _tabs.value[closingIndex].session.close()
        _tabs.update { tabs -> tabs.filterNot { it.session.id == tabId } }
        _pageStates.update { it - tabId }

        when {
            _tabs.value.isEmpty() -> {
                _activeTabId.value = null
                openNewTab(isPrivate = false)
            }
            wasActive -> {
                val next = _tabs.value[minOf(closingIndex, _tabs.value.lastIndex)]
                _activeTabId.value = next.session.id
                next.session.setActive(true)
            }
        }
    }

    /** Opens [url] (from bookmarks/history/another app) in the active tab. */
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

    private fun toggleBookmark() {
        val session = activeSession() ?: return
        val state = _pageStates.value[session.id] ?: return
        if (!UrlUtils.isHttpUrl(state.url)) return
        viewModelScope.launch {
            bookmarkRepository.toggle(state.url, state.title)
        }
    }

    fun clearBrowsingData() {
        engine?.clearBrowsingData()
        viewModelScope.launch { historyRepository.clearAll() }
    }

    // ---- Tab bookkeeping ---------------------------------------------------

    private fun registerTab(session: BrowserSession, makeActive: Boolean) {
        _tabs.update { it + TabEntry(session) }
        _pageStates.update { it + (session.id to session.state.value) }

        sessionJobs[session.id] = viewModelScope.launch {
            launch {
                var wasLoading = false
                session.state.collect { state ->
                    _pageStates.update { it + (session.id to state) }
                    val finishedLoad = wasLoading && !state.isLoading
                    wasLoading = state.isLoading
                    if (finishedLoad && !state.loadFailed && !session.isPrivate &&
                        UrlUtils.isHttpUrl(state.url)
                    ) {
                        historyRepository.recordVisit(state.url, state.title)
                    }
                }
            }
            launch {
                session.events.collect { event ->
                    when (event) {
                        is BrowserSessionEvent.DownloadRequested ->
                            _downloadEvents.tryEmit(event)
                        is BrowserSessionEvent.CloseRequested ->
                            closeTab(event.sessionId)
                    }
                }
            }
        }

        if (makeActive) {
            activeSession()?.setActive(false)
            _activeTabId.value = session.id
            session.setActive(true)
        }
    }

    private fun activeSession(): BrowserSession? {
        val activeId = _activeTabId.value ?: return null
        return _tabs.value.firstOrNull { it.session.id == activeId }?.session
    }

    override fun onCleared() {
        engine?.newSessionCreatedListener = null
        sessionJobs.values.forEach { it.cancel() }
        sessionJobs.clear()
        _tabs.value.forEach { it.session.close() }
        super.onCleared()
    }

    private data class TabsSlice(
        val tabs: List<TabEntry>,
        val activeId: String?,
        val states: Map<String, SessionPageState>,
    )
}
