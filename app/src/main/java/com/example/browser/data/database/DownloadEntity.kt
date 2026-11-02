package com.example.browser.data.database

import androidx.room.ColumnInfo
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
    // The @ColumnInfo defaultValue values must mirror the DEFAULT clauses
    // in DatabaseMigrations' ALTER TABLE statements exactly: Room compares
    // the migrated table's definition against the entity schema, and a
    // default declared only in SQL (or only in Kotlin) makes validation
    // fail with "Migration didn't properly handle" — a crash on first DB
    // access right after an app update.
    @ColumnInfo(defaultValue = "LEGACY")
    val sourceKind: String = SOURCE_LEGACY,
    /** Last persisted transfer status (DownloadManager STATUS_* or -2). */
    val lastStatus: Int? = null,
    @ColumnInfo(defaultValue = "0")
    val downloadedBytes: Long = 0L,
    @ColumnInfo(defaultValue = "-1")
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
