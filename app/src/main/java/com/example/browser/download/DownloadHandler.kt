package com.example.browser.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File

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
    fun download(
        url: String,
        fileName: String,
        mimeType: String?,
        pageUrl: String? = null,
    ): Long {
        if (!url.startsWith("https://", ignoreCase = true) &&
            !url.startsWith("http://", ignoreCase = true)
        ) {
            // DownloadManager only supports http(s); anything else is rejected.
            // Do not log the URL: it can contain private query parameters.
            Log.w(TAG, "Unsupported download URL scheme")
            return -1L
        }
        val destinationName = uniqueDestinationName(DownloadFileName.sanitize(fileName))
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(destinationName)
            // Do not expose the (possibly signed/private) URL in the system
            // notification's description.
            .setDescription(destinationName)
            .setMimeType(mimeType)
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE,
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                destinationName,
            )
        // Several real servers reject DownloadManager's bare default request
        // or require the page as referer. These ordinary browser headers make
        // the re-fetch behave much more like the tap that initiated it.
        System.getProperty("http.agent")
            ?.takeIf { it.isNotBlank() }
            ?.let { request.addRequestHeader("User-Agent", it) }
        pageUrl
            ?.takeIf {
                it.startsWith("https://", true) || it.startsWith("http://", true)
            }
            ?.let { request.addRequestHeader("Referer", it) }
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

    /** Cancels and removes a DownloadManager transfer, if it exists. */
    fun cancel(downloadId: Long) {
        if (downloadId < 0) return
        runCatching { downloadManager.remove(downloadId) }
    }

    /**
     * DownloadManager refuses destinations that already exist. Pick the
     * first free "name (n).ext" variant in the public Downloads folder.
     */
    private fun uniqueDestinationName(fileName: String): String {
        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS,
        )
        if (!File(dir, fileName).exists()) return fileName
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        val base = if (extension.isBlank()) {
            fileName
        } else {
            fileName.removeSuffix(".$extension")
        }
        for (index in 1..99) {
            val candidate = if (extension.isBlank()) {
                "$base ($index)"
            } else {
                "$base ($index).$extension"
            }
            if (!File(dir, candidate).exists()) return candidate
        }
        return fileName
    }

    /** Content URI of the finished file so it can be opened with other apps. */
    fun uriFor(downloadId: Long): Uri? =
        runCatching { downloadManager.getUriForDownloadedFile(downloadId) }.getOrNull()

    private companion object {
        const val TAG = "DownloadHandler"
    }
}
