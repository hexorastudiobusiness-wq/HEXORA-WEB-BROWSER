package com.example.browser.data.repository

import com.example.browser.data.database.HistoryDao
import com.example.browser.data.database.HistoryEntity
import kotlinx.coroutines.flow.Flow

/** Application-facing history API. Hides Room from ViewModels (ARCHITECTURE.md §20). */
class HistoryRepository(private val dao: HistoryDao) {

    val history: Flow<List<HistoryEntity>> = dao.observeAll()

    /** Records a finished page visit and increments its top-sites count. */
    suspend fun recordVisit(url: String, title: String) {
        val now = System.currentTimeMillis()
        val existing = dao.getByUrl(url)
        if (existing == null) {
            dao.insert(
                HistoryEntity(
                    url = url,
                    title = title.ifBlank { url },
                    timestamp = now,
                    visitCount = 1,
                ),
            )
        } else {
            dao.update(
                existing.copy(
                    title = title.ifBlank { existing.title },
                    timestamp = now,
                    visitCount = existing.visitCount + 1,
                ),
            )
        }
    }

    suspend fun deleteEntry(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}
