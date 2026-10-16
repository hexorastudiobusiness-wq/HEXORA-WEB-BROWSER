package com.example.browser.data.repository

import com.example.browser.data.database.ShortcutDao
import com.example.browser.data.database.ShortcutEntity
import kotlinx.coroutines.flow.Flow

/** User-curated Start Page shortcuts. The Start Page never derives these from history. */
class StartShortcutRepository(private val dao: ShortcutDao) {

    val shortcuts: Flow<List<ShortcutEntity>> = dao.observeAll()

    /** @return true when the shortcut was added. */
    suspend fun add(title: String, url: String): Boolean {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return false
        if (dao.count() >= MAX_SHORTCUTS) return false
        if (dao.getByUrl(normalizedUrl) != null) return false
        dao.insert(
            ShortcutEntity(
                title = title.trim().ifBlank { normalizedUrl },
                url = normalizedUrl,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    companion object {
        const val MAX_SHORTCUTS = 10
    }
}
