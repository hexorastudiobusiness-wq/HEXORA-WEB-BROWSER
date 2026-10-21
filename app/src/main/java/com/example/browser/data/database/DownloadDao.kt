package com.example.browser.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Insert
    suspend fun insert(entry: DownloadEntity)

    @Query("UPDATE downloads SET downloadManagerId = :downloadManagerId WHERE id = :id")
    suspend fun updateDownloadManagerId(id: Long, downloadManagerId: Long)

    @Query(
        "UPDATE downloads SET localUri = :localUri, lastStatus = :lastStatus, " +
            "downloadedBytes = :downloadedBytes, totalBytes = :totalBytes " +
            "WHERE id = :id",
    )
    suspend fun updateState(
        id: Long,
        localUri: String?,
        lastStatus: Int?,
        downloadedBytes: Long,
        totalBytes: Long,
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clearAll()
}
