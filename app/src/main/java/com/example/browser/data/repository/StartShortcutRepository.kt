package com.example.browser.data.repository

import com.example.browser.data.database.ShortcutDao
import com.example.browser.data.database.ShortcutEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** User-curated Start Page shortcuts. The Start Page never derives these from history. */
class StartShortcutRepository(private val dao: ShortcutDao) {

    val shortcuts: Flow<List<ShortcutEntity>> = dao.observeAll()

    /**
     * Serializes check-then-act writes: the count/duplicate checks in [add]
     * and the duplicate check in [update] must be atomic, otherwise two
     * rapid operations could exceed the cap or fight over the unique URL
     * index (which would surface as a SQLite constraint crash).
     */
    private val writeMutex = Mutex()

    /** @return the new shortcut id, or null when it was not added. */
    suspend fun add(title: String, url: String): Long? = writeMutex.withLock {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return@withLock null
        if (dao.count() >= MAX_SHORTCUTS) return@withLock null
        if (dao.getByUrl(normalizedUrl) != null) return@withLock null
        dao.insert(
            ShortcutEntity(
                title = title.trim().ifBlank { normalizedUrl },
                url = normalizedUrl,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** @return true when an existing shortcut was updated. */
    suspend fun update(id: Long, title: String, url: String): Boolean =
        writeMutex.withLock {
            val normalizedUrl = url.trim()
            if (normalizedUrl.isBlank()) return@withLock false
            val existing = dao.getById(id) ?: return@withLock false
            val duplicate = dao.getByUrl(normalizedUrl)
            if (duplicate != null && duplicate.id != id) return@withLock false
            dao.update(
                existing.copy(
                    title = title.trim().ifBlank { normalizedUrl },
                    url = normalizedUrl,
                ),
            )
            true
        }

    suspend fun delete(id: Long) = dao.deleteById(id)

    companion object {
        const val MAX_SHORTCUTS = 10
    }
}
