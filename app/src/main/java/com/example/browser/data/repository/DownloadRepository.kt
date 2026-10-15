package com.example.browser.data.repository

import com.example.browser.data.database.DownloadDao
import com.example.browser.data.database.DownloadEntity
import kotlinx.coroutines.flow.Flow

/** Application-facing downloads history API (files themselves live in DownloadManager). */
class DownloadRepository(private val dao: DownloadDao) {

    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()

    suspend fun record(downloadManagerId: Long, url: String, fileName: String, mimeType: String?) {
        dao.insert(
            DownloadEntity(
                downloadManagerId = downloadManagerId,
                url = url,
                fileName = fileName,
                mimeType = mimeType,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}
