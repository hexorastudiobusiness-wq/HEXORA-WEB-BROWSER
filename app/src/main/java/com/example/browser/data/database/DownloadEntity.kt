package com.example.browser.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A download we handed to Android's DownloadManager (kept for the Downloads page). */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Id assigned by DownloadManager; used for status checks and opening the file. */
    val downloadManagerId: Long,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val createdAt: Long,
)
