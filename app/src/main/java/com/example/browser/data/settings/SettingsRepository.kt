package com.example.browser.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.browser.util.SearchEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,

    /** Studio theme: the logo's blue and amber on near-black navy. */
    HEXORA,

    /** Ink on warm stock, no accent colour, serif text. */
    PAPER,

    /** Northern-lights palette: deep pine, mint and violet. */
    AURORA,
    ;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Persisted user settings (ARCHITECTURE.md §10). */
data class BrowserSettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    /** User-supplied search URL template containing `%s`, for CUSTOM. */
    val customSearchTemplate: String = "",
    val customSearchName: String = "",
    val javaScriptEnabled: Boolean = true,
    /** Gecko Enhanced Tracking Protection for every session. */
    val trackingProtectionEnabled: Boolean = true,
    /** Upgrade/block plain HTTP navigations through Gecko HTTPS-Only Mode. */
    val httpsOnlyMode: Boolean = false,
    val desktopMode: Boolean = false,
    val openLinksInNewTab: Boolean = true,
    /** Global text zoom in percent (50..200). */
    /** Master switch for Hexora's local, offline saved-login vault. */
    val passwordManagerEnabled: Boolean = true,
) {
}

private val Context.dataStore by preferencesDataStore(
    name = "browser_settings",
    // A corrupt settings file must degrade to defaults, never crash every
    // collector (including the one that creates the browsing engine).
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * DataStore-backed settings. UI talks to this class, never to DataStore
 * keys directly.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val settings: Flow<BrowserSettingsState> = dataStore.data.map { prefs ->
        BrowserSettingsState(
            themeMode = ThemeMode.fromName(prefs[KEY_THEME]),
            searchEngine = SearchEngine.fromId(prefs[KEY_SEARCH_ENGINE]),
            customSearchTemplate = prefs[KEY_CUSTOM_SEARCH_TEMPLATE] ?: "",
            customSearchName = prefs[KEY_CUSTOM_SEARCH_NAME] ?: "",
            javaScriptEnabled = prefs[KEY_JAVASCRIPT] ?: true,
            trackingProtectionEnabled = prefs[KEY_TRACKING_PROTECTION] ?: true,
            httpsOnlyMode = prefs[KEY_HTTPS_ONLY] ?: false,
            desktopMode = prefs[KEY_DESKTOP_MODE] ?: false,
            openLinksInNewTab = prefs[KEY_OPEN_LINKS_NEW_TAB] ?: true,
            passwordManagerEnabled = prefs[KEY_PASSWORD_MANAGER] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setSearchEngine(engine: SearchEngine) {
        dataStore.edit { it[KEY_SEARCH_ENGINE] = engine.id }
    }

    suspend fun setCustomSearch(name: String, template: String) {
        dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_SEARCH_NAME] = name.trim()
            prefs[KEY_CUSTOM_SEARCH_TEMPLATE] = template.trim()
        }
    }

    suspend fun setJavaScriptEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_JAVASCRIPT] = enabled }
    }

    suspend fun setTrackingProtectionEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_TRACKING_PROTECTION] = enabled }
    }

    suspend fun setHttpsOnlyMode(enabled: Boolean) {
        dataStore.edit { it[KEY_HTTPS_ONLY] = enabled }
    }

    suspend fun setDesktopMode(enabled: Boolean) {
        dataStore.edit { it[KEY_DESKTOP_MODE] = enabled }
    }


    suspend fun setPasswordManagerEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PASSWORD_MANAGER] = enabled }
    }

    suspend fun setOpenLinksInNewTab(enabled: Boolean) {
        dataStore.edit { it[KEY_OPEN_LINKS_NEW_TAB] = enabled }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val KEY_CUSTOM_SEARCH_TEMPLATE = stringPreferencesKey("custom_search_template")
        val KEY_CUSTOM_SEARCH_NAME = stringPreferencesKey("custom_search_name")
        val KEY_JAVASCRIPT = booleanPreferencesKey("javascript_enabled")
        val KEY_TRACKING_PROTECTION = booleanPreferencesKey("tracking_protection_enabled")
        val KEY_HTTPS_ONLY = booleanPreferencesKey("https_only_mode")
        val KEY_DESKTOP_MODE = booleanPreferencesKey("desktop_mode")
        val KEY_OPEN_LINKS_NEW_TAB = booleanPreferencesKey("open_links_in_new_tab")
        val KEY_PASSWORD_MANAGER = booleanPreferencesKey("password_manager_enabled")
    }
}
