package com.example.browser.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.data.settings.BrowserSettingsState
import com.example.browser.data.settings.SettingsRepository
import com.example.browser.data.settings.ThemeMode
import com.example.browser.util.SearchEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<BrowserSettingsState> = repository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        BrowserSettingsState(),
    )

    fun setSearchEngine(engine: SearchEngine) {
        viewModelScope.launch { repository.setSearchEngine(engine) }
    }

    fun setHomepage(homepage: String) {
        viewModelScope.launch { repository.setHomepage(homepage) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setJavaScriptEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setJavaScriptEnabled(enabled) }
    }

    fun setCookiesEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setCookiesEnabled(enabled) }
    }

    fun setDesktopMode(enabled: Boolean) {
        viewModelScope.launch { repository.setDesktopMode(enabled) }
    }

    fun setOpenLinksInNewTab(enabled: Boolean) {
        viewModelScope.launch { repository.setOpenLinksInNewTab(enabled) }
    }
}
