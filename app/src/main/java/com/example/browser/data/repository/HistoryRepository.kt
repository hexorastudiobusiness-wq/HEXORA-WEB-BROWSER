package com.example.browser.data.repository

import com.example.browser.data.database.HistoryDao
import com.example.browser.data.database.HistoryEntity
import kotlinx.coroutines.flow.Flow

/** Application-facing history API. Hides Room from ViewModels (ARCHITECTURE.md §20). */
class HistoryRepository(private val dao: HistoryDao) {

    val history: Flow<List<HistoryEntity>> = dao.observeAll()

    /** Records a finished page visit; re-visiting a URL moves it to the top. */
    suspend fun recordVisit(url: String, title: String) {
        dao.deleteByUrl(url)
        dao.insert(
            HistoryEntity(
                url = url,
                title = title.ifBlank { url },
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteEntry(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}
