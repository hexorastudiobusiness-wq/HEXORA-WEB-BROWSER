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

    /** @return true when the download was enqueued successfully. */
    fun download(url: String, fileName: String, mimeType: String?): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) {
            // DownloadManager only supports http(s); anything else is rejected.
            Log.w(TAG, "Unsupported download URL scheme: $url")
            return false
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription(url)
            .setMimeType(mimeType)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        return runCatching { downloadManager.enqueue(request) }
            .onFailure { Log.e(TAG, "Failed to enqueue download for $url", it) }
            .isSuccess
    }

    private companion object {
        const val TAG = "DownloadHandler"
    }
}
