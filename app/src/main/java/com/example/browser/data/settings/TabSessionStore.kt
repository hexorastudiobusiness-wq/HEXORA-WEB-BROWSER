package com.example.browser.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
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
)

/** The persisted tab strip: open tabs plus which one was active. */
data class PersistedTabs(
    val tabs: List<PersistedTab> = emptyList(),
    val activeIndex: Int = 0,
)

private val Context.tabDataStore by preferencesDataStore(name = "tab_session")

/**
 * Persists the open-tab list (URLs/titles only, never page content or
 * private tabs) so a cold start after the system killed the app process
 * in the background restores the tabs the user had open, like every
 * mainstream browser. Only metadata is stored; pages themselves reload
 * from the network on restore.
 */
class TabSessionStore(context: Context) {

    private val dataStore = context.applicationContext.tabDataStore

    suspend fun load(): PersistedTabs {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_TABS] ?: return PersistedTabs()
        val tabs = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("u")
                    if (url.isBlank()) continue
                    add(PersistedTab(url = url, title = item.optString("t")))
                }
            }
        }.getOrDefault(emptyList())
        if (tabs.isEmpty()) return PersistedTabs()
        val active = (prefs[KEY_ACTIVE] ?: 0).coerceIn(0, tabs.lastIndex)
        return PersistedTabs(tabs = tabs, activeIndex = active)
    }

    suspend fun save(tabs: List<PersistedTab>, activeIndex: Int) {
        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(
                JSONObject()
                    .put("u", tab.url)
                    .put("t", tab.title),
            )
        }
        dataStore.edit { prefs ->
            prefs[KEY_TABS] = array.toString()
            prefs[KEY_ACTIVE] = if (tabs.isEmpty()) {
                0
            } else {
                activeIndex.coerceIn(0, tabs.lastIndex)
            }
        }
    }

    private companion object {
        val KEY_TABS = stringPreferencesKey("tabs_json")
        val KEY_ACTIVE = intPreferencesKey("active_index")
    }
}
