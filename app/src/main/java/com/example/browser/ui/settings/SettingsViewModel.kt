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

    fun setCustomSearch(name: String, template: String) {
        viewModelScope.launch { repository.setCustomSearch(name, template) }
    }

    fun setPasswordManagerEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setPasswordManagerEnabled(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setTextZoomPercent(percent: Int) {
        viewModelScope.launch { repository.setTextZoomPercent(percent) }
    }

    fun setJavaScriptEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setJavaScriptEnabled(enabled) }
    }

    fun setTrackingProtectionEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setTrackingProtectionEnabled(enabled) }
    }

    fun setHttpsOnlyMode(enabled: Boolean) {
        viewModelScope.launch { repository.setHttpsOnlyMode(enabled) }
    }

    fun setDesktopMode(enabled: Boolean) {
        viewModelScope.launch { repository.setDesktopMode(enabled) }
    }

    fun setOpenLinksInNewTab(enabled: Boolean) {
        viewModelScope.launch { repository.setOpenLinksInNewTab(enabled) }
    }
}
