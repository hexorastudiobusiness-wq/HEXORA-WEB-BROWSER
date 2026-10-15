package com.example.browser.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.browser.util.SearchEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Persisted user settings (ARCHITECTURE.md §10). */
data class BrowserSettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val homepage: String = DEFAULT_HOMEPAGE,
    val javaScriptEnabled: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val desktopMode: Boolean = false,
    val openLinksInNewTab: Boolean = true,
) {
    companion object {
        const val DEFAULT_HOMEPAGE = "https://www.google.com"
    }
}

private val Context.dataStore by preferencesDataStore(name = "browser_settings")

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
            homepage = prefs[KEY_HOMEPAGE]?.takeIf { it.isNotBlank() }
                ?: BrowserSettingsState.DEFAULT_HOMEPAGE,
            javaScriptEnabled = prefs[KEY_JAVASCRIPT] ?: true,
            cookiesEnabled = prefs[KEY_COOKIES] ?: true,
            desktopMode = prefs[KEY_DESKTOP_MODE] ?: false,
            openLinksInNewTab = prefs[KEY_OPEN_LINKS_NEW_TAB] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setSearchEngine(engine: SearchEngine) {
        dataStore.edit { it[KEY_SEARCH_ENGINE] = engine.id }
    }

    suspend fun setHomepage(homepage: String) {
        val value = homepage.trim()
        if (value.isNotBlank()) {
            dataStore.edit { it[KEY_HOMEPAGE] = value }
        }
    }

    suspend fun setJavaScriptEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_JAVASCRIPT] = enabled }
    }

    suspend fun setCookiesEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_COOKIES] = enabled }
    }

    suspend fun setDesktopMode(enabled: Boolean) {
        dataStore.edit { it[KEY_DESKTOP_MODE] = enabled }
    }

    suspend fun setOpenLinksInNewTab(enabled: Boolean) {
        dataStore.edit { it[KEY_OPEN_LINKS_NEW_TAB] = enabled }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val KEY_HOMEPAGE = stringPreferencesKey("homepage")
        val KEY_JAVASCRIPT = booleanPreferencesKey("javascript_enabled")
        val KEY_COOKIES = booleanPreferencesKey("cookies_enabled")
        val KEY_DESKTOP_MODE = booleanPreferencesKey("desktop_mode")
        val KEY_OPEN_LINKS_NEW_TAB = booleanPreferencesKey("open_links_in_new_tab")
    }
}
