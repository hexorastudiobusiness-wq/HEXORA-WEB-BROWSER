package com.example.browser.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A download recorded for the Downloads page.
 *
 * New transfers are performed by Hexora's own download layer, not Android's
 * DownloadManager; [downloadManagerId] is therefore the transfer id assigned
 * by that layer (the column keeps its historical name). Terminal state and
 * the published file URI are persisted so completed downloads can still be
 * opened — and failed/paused ones truthfully displayed and resumed — after
 * the process has been restarted.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Transfer id used for live status checks while this process runs. */
    val downloadManagerId: Long,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val createdAt: Long,
    /** Published content/file URI of a completed transfer, when known. */
    val localUri: String? = null,
    /** How the transfer can be produced again: HTTP, STREAM or LEGACY. */
    val sourceKind: String = SOURCE_LEGACY,
    /** Last persisted transfer status (DownloadManager STATUS_* or -2). */
    val lastStatus: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
) {
    companion object {
        const val SOURCE_HTTP = "HTTP"
        const val SOURCE_STREAM = "STREAM"
        const val SOURCE_LEGACY = "LEGACY"

        /** Persisted marker for a user-cancelled transfer. */
        const val STATUS_CANCELLED = -2
    }
}
