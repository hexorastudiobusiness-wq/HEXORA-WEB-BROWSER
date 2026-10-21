package com.example.browser.data.repository

import com.example.browser.data.database.HistoryDao
import com.example.browser.data.database.HistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-facing history API. Hides Room from ViewModels (ARCHITECTURE.md §20). */
class HistoryRepository(private val dao: HistoryDao) {

    val history: Flow<List<HistoryEntity>> = dao.observeAll()

    /**
     * Serializes the check-then-insert/update in [recordVisit]. The history
     * table has no unique URL index, so two tabs finishing a load of the
     * same URL at the same moment could otherwise insert duplicate rows.
     * All Room access happens in this single app process, so a mutex is
     * sufficient (and avoids a schema migration just for the index).
     */
    private val writeMutex = Mutex()

    /** Records a finished page visit; revisiting a URL refreshes its title/time. */
    suspend fun recordVisit(url: String, title: String) = writeMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = dao.getByUrl(url)
        if (existing == null) {
            dao.insert(
                HistoryEntity(
                    url = url,
                    title = title.ifBlank { url },
                    timestamp = now,
                ),
            )
        } else {
            dao.update(
                existing.copy(
                    title = title.ifBlank { existing.title },
                    timestamp = now,
                ),
            )
        }
    }

    suspend fun deleteEntry(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}
