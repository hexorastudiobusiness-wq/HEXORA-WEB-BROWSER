package com.example.browser.data.repository

import com.example.browser.data.database.DownloadDao
import com.example.browser.data.database.DownloadEntity
import kotlinx.coroutines.flow.Flow

/**
 * Application-facing downloads history API. Files themselves live in the
 * public Downloads folder; each row remembers how its transfer was started
 * and the last terminal state reported for it, so the Downloads page stays
 * correct after the process restarts.
 */
class DownloadRepository(private val dao: DownloadDao) {

    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()

    suspend fun record(
        downloadManagerId: Long,
        url: String,
        fileName: String,
        mimeType: String?,
        sourceKind: String = DownloadEntity.SOURCE_HTTP,
    ) {
        dao.insert(
            DownloadEntity(
                downloadManagerId = downloadManagerId,
                url = url,
                fileName = fileName,
                mimeType = mimeType,
                createdAt = System.currentTimeMillis(),
                sourceKind = sourceKind,
            ),
        )
    }

    suspend fun updateDownloadManagerId(id: Long, downloadManagerId: Long) =
        dao.updateDownloadManagerId(id, downloadManagerId)

    /** Persists the latest known state of a transfer (see DownloadEntity). */
    suspend fun updateState(
        id: Long,
        localUri: String?,
        lastStatus: Int?,
        downloadedBytes: Long,
        totalBytes: Long,
    ) = dao.updateState(id, localUri, lastStatus, downloadedBytes, totalBytes)

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}
