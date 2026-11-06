package com.example.browser.data.settings

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sitePreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "site_preferences",
)

/**
 * Per-site browser settings: page zoom and "desktop site", keyed by domain.
 *
 * Why a separate store: these belong to a site, not to the app, and they must
 * survive a full app restart (the owner reported zoom showing 75% in the menu
 * while pages were rendered at 100%). A small DataStore keeps them out of the
 * settings flow, so changing one site's zoom cannot re-emit the whole
 * settings object.
 *
 * Keying: the registrable host, lowercased, with a leading `www.` removed, so
 * `www.example.com` and `example.com` share one setting — the same behaviour
 * Chrome has. Nothing else is stored (no URLs, no titles, no paths), so the
 * file cannot be used to reconstruct browsing history.
 */
class SitePreferencesStore(private val context: Context) {

    /** host -> zoom percent, for every site the user ever adjusted. */
    val zoomByHost: Flow<Map<String, Int>> =
        context.sitePreferencesDataStore.data.map { prefs ->
            prefs.asMap().entries.mapNotNull { (key, value) ->
                val host = key.name.removePrefix(ZOOM_PREFIX).takeIf { it != key.name }
                val percent = value as? Int
                if (host != null && percent != null) host to percent else null
            }.toMap()
        }

    /** host -> "desktop site" preference. */
    val desktopByHost: Flow<Map<String, Boolean>> =
        context.sitePreferencesDataStore.data.map { prefs ->
            prefs.asMap().entries.mapNotNull { (key, value) ->
                val host = key.name.removePrefix(DESKTOP_PREFIX).takeIf { it != key.name }
                val enabled = value as? Boolean
                if (host != null && enabled != null) host to enabled else null
            }.toMap()
        }

    suspend fun setZoom(host: String, percent: Int) {
        val key = normalizeHost(host) ?: return
        val clamped = percent.coerceIn(MIN_ZOOM, MAX_ZOOM)
        context.sitePreferencesDataStore.edit { prefs ->
            if (clamped == 100) {
                // 100% is the default: storing it would only grow the file.
                prefs.remove(intPreferencesKey(ZOOM_PREFIX + key))
            } else {
                prefs[intPreferencesKey(ZOOM_PREFIX + key)] = clamped
            }
        }
    }

    suspend fun setDesktop(host: String, enabled: Boolean) {
        val key = normalizeHost(host) ?: return
        context.sitePreferencesDataStore.edit { prefs ->
            if (!enabled) {
                prefs.remove(booleanPreferencesKey(DESKTOP_PREFIX + key))
            } else {
                prefs[booleanPreferencesKey(DESKTOP_PREFIX + key)] = true
            }
        }
    }

    /** Forgets every per-site zoom/desktop choice (browsing data untouched). */
    suspend fun clearAll() {
        context.sitePreferencesDataStore.edit { prefs -> prefs.clear() }
    }

    companion object {
        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 200
        const val DEFAULT_ZOOM = 100

        private const val ZOOM_PREFIX = "zoom/"
        private const val DESKTOP_PREFIX = "desktop/"

        /**
         * Domain key for [url], or null when the URL has no usable host
         * (about:, data:, error pages...). A leading `www.` is dropped so the
         * two forms of the same site share one preference.
         */
        fun hostOf(url: String?): String? = normalizeHost(
            runCatching { Uri.parse(url.orEmpty()).host }.getOrNull(),
        )

        fun normalizeHost(host: String?): String? {
            val trimmed = host?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            return trimmed.removePrefix("www.").takeIf { it.isNotBlank() }
        }
    }
}
