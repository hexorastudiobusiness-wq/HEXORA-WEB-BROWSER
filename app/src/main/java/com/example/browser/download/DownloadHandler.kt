package com.example.browser.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log

/**
 * Download layer (ARCHITECTURE.md §11).
 *
 * Receives download requests coming out of the engine and delegates the
 * actual transfer to Android's [DownloadManager], which saves to the
 * user-visible Downloads folder and shows system notifications.
 * No storage permission is required for this on Android 10+.
 */
class DownloadHandler(context: Context) {

    private val appContext = context.applicationContext
    private val downloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** @return the DownloadManager id, or -1 when enqueueing failed. */
    fun download(url: String, fileName: String, mimeType: String?): Long {
        if (!url.startsWith("https://", ignoreCase = true) &&
            !url.startsWith("http://", ignoreCase = true)
        ) {
            // DownloadManager only supports http(s); anything else is rejected.
            // Do not log the URL: it can contain private query parameters.
            Log.w(TAG, "Unsupported download URL scheme")
            return -1L
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            // Do not expose the (possibly signed/private) URL in the system
            // notification's description.
            .setDescription(fileName)
            .setMimeType(mimeType)
            .setAllowedOverMetered(true)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        return runCatching { downloadManager.enqueue(request) }
            .onFailure { Log.e(TAG, "Failed to enqueue download", it) }
            .getOrDefault(-1L)
    }

    data class Progress(
        val status: Int?,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val reason: Int? = null,
    ) {
        /** 0f..1f when the server reported a total size, otherwise null. */
        val fraction: Float?
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    /** Current DownloadManager status (STATUS_*) for [downloadId], if known. */
    fun statusFor(downloadId: Long): Int? = progressFor(downloadId).status

    /** Live DownloadManager progress for [downloadId]. */
    fun progressFor(downloadId: Long): Progress {
        if (downloadId < 0) return Progress(status = null)
        return runCatching {
            downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use Progress(status = null)
                    Progress(
                        status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                        ),
                        downloadedBytes = cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                            ),
                        ),
                        totalBytes = cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES,
                            ),
                        ),
                        reason = runCatching {
                            cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                            )
                        }.getOrNull(),
                    )
                } ?: Progress(status = null)
        }.getOrDefault(Progress(status = null))
    }

    /** Content URI of the finished file so it can be opened with other apps. */
    fun uriFor(downloadId: Long): Uri? =
        runCatching { downloadManager.getUriForDownloadedFile(downloadId) }.getOrNull()

    private companion object {
        const val TAG = "DownloadHandler"
    }
}
