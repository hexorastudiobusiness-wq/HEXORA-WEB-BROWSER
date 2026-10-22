package com.example.browser.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** One persisted open tab (normal browsing only; private tabs never persist). */
data class PersistedTab(
    val url: String,
    val title: String,
    /**
     * The tab's serialized engine session state (navigation history, scroll
     * position, form data), exactly as Firefox persists per-tab engine
     * state — restoring it brings the page back where the user left it
     * instead of merely reloading the URL. Null when no state was captured.
     */
    val engineState: String? = null,
    /**
     * This tab's own desktop/mobile rendering choice. Null only for strips
     * written before per-tab persistence existed; the caller then falls
     * back to the global default.
     */
    val desktopMode: Boolean? = null,
)

/** The persisted tab strip: open tabs plus which one was active. */
data class PersistedTabs(
    val tabs: List<PersistedTab> = emptyList(),
    val activeIndex: Int = 0,
    /**
     * Recently closed tabs (URL/title only, never private), so the
     * Recently Closed list also survives a process restart.
     */
    val recentlyClosed: List<PersistedTab> = emptyList(),
)

private val Context.tabDataStore by preferencesDataStore(
    name = "tab_session",
    // Corrupt persisted tabs degrade to an empty strip (and a Start Page),
    // never to a cold-start crash.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Persists the open-tab list (URL/title plus the tab's serialized engine
 * session state, never private tabs) so a cold start after the system
 * killed the app process in the background restores the tabs the user had
 * open — with their navigation history and scroll position, the way
 * Firefox persists per-tab engine state. Engine state strings are opaque
 * and version-bound: an unreadable one simply falls back to loading the
 * tab's URL on restore.
 */
class TabSessionStore(context: Context) {

    private val dataStore = context.applicationContext.tabDataStore

    private val guardPrefs = context.applicationContext
        .getSharedPreferences("hexora_startup_guard", Context.MODE_PRIVATE)

    /**
     * Crash-loop circuit breaker. Marks a launch as "in progress" and
     * reports whether the PREVIOUS launch ended while still in progress
     * (crashed or froze before the browser became usable). The caller can
     * then degrade gracefully — e.g. restore tab URLs without their saved
     * engine states — instead of dying on the same persisted state on
     * every launch, a loop only "Clear data" used to break.
     */
    fun beginLaunch(): Boolean {
        val previousLaunchDiedEarly =
            guardPrefs.getBoolean(KEY_LAUNCH_IN_PROGRESS, false)
        guardPrefs.edit().putBoolean(KEY_LAUNCH_IN_PROGRESS, true).apply()
        return previousLaunchDiedEarly
    }

    /** The browser reached a usable state; the next launch starts clean. */
    fun markLaunchStable() {
        guardPrefs.edit().putBoolean(KEY_LAUNCH_IN_PROGRESS, false).apply()
    }

    suspend fun load(): PersistedTabs {
        val prefs = dataStore.data.first()
        val recentlyClosed = parseTabs(prefs[KEY_RECENTLY_CLOSED])
        val raw = prefs[KEY_TABS]
            ?: return PersistedTabs(recentlyClosed = recentlyClosed)
        val tabs = parseTabs(raw)
        if (tabs.isEmpty()) {
            return PersistedTabs(recentlyClosed = recentlyClosed)
        }
        val active = (prefs[KEY_ACTIVE] ?: 0).coerceIn(0, tabs.lastIndex)
        return PersistedTabs(
            tabs = tabs,
            activeIndex = active,
            recentlyClosed = recentlyClosed,
        )
    }

    private fun parseTabs(raw: String?): List<PersistedTab> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("u")
                    if (url.isBlank()) continue
                    add(
                        PersistedTab(
                            url = url,
                            title = item.optString("t"),
                            engineState = item.optString("s")
                                .takeIf { it.isNotBlank() },
                            desktopMode = if (item.has("d")) {
                                item.optBoolean("d")
                            } else {
                                null
                            },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(
        tabs: List<PersistedTab>,
        activeIndex: Int,
        recentlyClosed: List<PersistedTab> = emptyList(),
    ) {
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(
                JSONObject()
                    .put("u", tab.url)
                    .put("t", tab.title)
                    .apply {
                        tab.engineState?.let { put("s", it) }
                        tab.desktopMode?.let { put("d", it) }
                    },
            )
        }
        val closedArray = JSONArray()
        recentlyClosed.forEach { tab ->
            closedArray.put(
                JSONObject()
                    .put("u", tab.url)
                    .put("t", tab.title)
                    .apply {
                        tab.desktopMode?.let { put("d", it) }
                    },
            )
        }
        dataStore.edit { prefs ->
            prefs[KEY_TABS] = array.toString()
            prefs[KEY_RECENTLY_CLOSED] = closedArray.toString()
            prefs[KEY_ACTIVE] = if (tabs.isEmpty()) {
                0
            } else {
                activeIndex.coerceIn(0, tabs.lastIndex)
            }
        }
    }

    private companion object {
        const val KEY_LAUNCH_IN_PROGRESS = "launch_in_progress"
        val KEY_TABS = stringPreferencesKey("tabs_json")
        val KEY_ACTIVE = intPreferencesKey("active_index")
        val KEY_RECENTLY_CLOSED = stringPreferencesKey("recently_closed_json")
    }
}
