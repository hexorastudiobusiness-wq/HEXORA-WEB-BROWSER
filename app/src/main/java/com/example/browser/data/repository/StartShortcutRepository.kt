package com.example.browser.data.repository

import com.example.browser.data.database.ShortcutDao
import com.example.browser.data.database.ShortcutEntity
import kotlinx.coroutines.flow.Flow

/** User-curated Start Page shortcuts. The Start Page never derives these from history. */
class StartShortcutRepository(private val dao: ShortcutDao) {

    val shortcuts: Flow<List<ShortcutEntity>> = dao.observeAll()

    /** @return the new shortcut id, or null when it was not added. */
    suspend fun add(title: String, url: String): Long? {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return null
        if (dao.count() >= MAX_SHORTCUTS) return null
        if (dao.getByUrl(normalizedUrl) != null) return null
        return dao.insert(
            ShortcutEntity(
                title = title.trim().ifBlank { normalizedUrl },
                url = normalizedUrl,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** @return true when an existing shortcut was updated. */
    suspend fun update(id: Long, title: String, url: String): Boolean {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return false
        val existing = dao.getById(id) ?: return false
        val duplicate = dao.getByUrl(normalizedUrl)
        if (duplicate != null && duplicate.id != id) return false
        dao.update(
            existing.copy(
                title = title.trim().ifBlank { normalizedUrl },
                url = normalizedUrl,
            ),
        )
        return true
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    companion object {
        const val MAX_SHORTCUTS = 10
    }
}
