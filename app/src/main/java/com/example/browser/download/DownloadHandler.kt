package com.example.browser.download

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Download layer.
 *
 * New downloads are performed by Hexora itself and published to the public
 * Downloads folder through MediaStore. The previous implementation handed
 * every transfer to Android's DownloadManager; on the owner's device those
 * transfers stayed in STATUS_PENDING forever (even a 10 KB image), so the
 * browser now owns the transfer and reports real progress. Progress/status
 * lookups still fall back to DownloadManager for entries recorded by older
 * versions.
 */
class DownloadHandler(context: Context) {

    private val appContext = context.applicationContext
    private val downloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(System.currentTimeMillis() * 1000L)
    private val progressById = ConcurrentHashMap<Long, Progress>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val connections = ConcurrentHashMap<Long, HttpURLConnection>()
    private val completedUris = ConcurrentHashMap<Long, Uri>()
    private val fileNames = ConcurrentHashMap<Long, String>()

    /** Starts a transfer and returns its id, or -1 when it cannot start. */
    fun download(
        url: String,
        fileName: String,
        mimeType: String?,
        pageUrl: String? = null,
    ): Long {
        if (!url.startsWith("https://", ignoreCase = true) &&
            !url.startsWith("http://", ignoreCase = true)
        ) {
            // Blob/data/POST downloads cannot be replayed as a plain GET.
            // Do not log the URL: it can contain private query parameters.
            Log.w(TAG, "Unsupported download URL scheme")
            return -1L
        }
        val safeName = DownloadFileName.sanitize(fileName)
        val id = nextId.incrementAndGet()
        fileNames[id] = safeName
        progressById[id] = Progress(DownloadManager.STATUS_PENDING)
        val job = scope.launch {
            performDownload(id, url, safeName, mimeType, pageUrl)
        }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id, job)
            connections.remove(id)
        }
        return id
    }

    private suspend fun performDownload(
        id: Long,
        url: String,
        fileName: String,
        mimeType: String?,
        pageUrl: String?,
    ) {
        val tempDir = File(appContext.cacheDir, "downloads").apply { mkdirs() }
        val tempFile = File(tempDir, "$id.part")
        var connection: HttpURLConnection? = null
        try {
            progressById[id] = Progress(DownloadManager.STATUS_RUNNING)
            val opened = URL(url.replace(" ", "%20")).openConnection() as HttpURLConnection
            connection = opened
            connections[id] = opened
            opened.instanceFollowRedirects = true
            opened.connectTimeout = CONNECT_TIMEOUT_MILLIS
            opened.readTimeout = READ_TIMEOUT_MILLIS
            opened.requestMethod = "GET"
            opened.setRequestProperty("Accept", "*/*")
            opened.setRequestProperty("Accept-Encoding", "identity")
            System.getProperty("http.agent")
                ?.takeIf { it.isNotBlank() }
                ?.let { opened.setRequestProperty("User-Agent", it) }
            pageUrl
                ?.takeIf {
                    it.startsWith("https://", true) || it.startsWith("http://", true)
                }
                ?.let { opened.setRequestProperty("Referer", it) }

            val responseCode = opened.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode")
            }
            val total = opened.contentLengthLong
            var downloaded = 0L
            var lastUpdateAt = 0L
            opened.inputStream.buffered().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        scope.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateAt >= UPDATE_INTERVAL_MILLIS ||
                            (total > 0 && downloaded == total)
                        ) {
                            lastUpdateAt = now
                            progressById[id] = Progress(
                                status = DownloadManager.STATUS_RUNNING,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                            )
                        }
                    }
                }
            }
            scope.ensureActive()
            val publishedUri = publish(tempFile, fileName, mimeType)
            completedUris[id] = publishedUri
            val finalTotal = if (total > 0) total else downloaded
            progressById[id] = Progress(
                status = DownloadManager.STATUS_SUCCESSFUL,
                downloadedBytes = downloaded,
                totalBytes = finalTotal,
            )
        } catch (cancelled: CancellationException) {
            tempFile.delete()
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Download failed", error)
            tempFile.delete()
            progressById[id] = Progress(DownloadManager.STATUS_FAILED)
        } finally {
            connections.remove(id)
            connection?.disconnect()
        }
    }

    /** Copies the finished temp file into the user-visible Downloads folder. */
    private fun publish(tempFile: File, fileName: String, mimeType: String?): Uri {
        val resolvedMime = mimeType
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                fileName.substringAfterLast('.', "").lowercase(),
            )
            ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("MediaStore open failed")
                val done = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            } finally {
                tempFile.delete()
            }
            return uri
        }

        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "Downloads")
        dir.mkdirs()
        val target = uniqueFile(dir, fileName)
        tempFile.copyTo(target, overwrite = false)
        tempFile.delete()
        return Uri.fromFile(target)
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val first = File(dir, fileName)
        if (!first.exists()) return first
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        val base = if (extension.isBlank()) {
            fileName
        } else {
            fileName.removeSuffix(".$extension")
        }
        for (index in 1..99) {
            val candidate = if (extension.isBlank()) {
                File(dir, "$base ($index)")
            } else {
                File(dir, "$base ($index).$extension")
            }
            if (!candidate.exists()) return candidate
        }
        return first
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

    /** Current status (DownloadManager STATUS_*) for [downloadId], if known. */
    fun statusFor(downloadId: Long): Int? = progressFor(downloadId).status

    /** Live progress for Hexora transfers; legacy fallback to DownloadManager. */
    fun progressFor(downloadId: Long): Progress {
        progressById[downloadId]?.let { return it }
        return legacyProgressFor(downloadId)
    }

    private fun legacyProgressFor(downloadId: Long): Progress {
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

    /** Cancels a Hexora transfer, or removes a legacy DownloadManager entry. */
    fun cancel(downloadId: Long) {
        val hadOwnTransfer = jobs.containsKey(downloadId) ||
            progressById.containsKey(downloadId)
        connections.remove(downloadId)?.disconnect()
        jobs.remove(downloadId)?.cancel()
        progressById.remove(downloadId)
        completedUris.remove(downloadId)
        fileNames.remove(downloadId)
        if (!hadOwnTransfer && downloadId > 0) {
            runCatching { downloadManager.remove(downloadId) }
        }
    }

    /** Content URI of the finished file so it can be opened with other apps. */
    fun uriFor(downloadId: Long): Uri? {
        completedUris[downloadId]?.let { return it }
        if (progressById.containsKey(downloadId)) return null
        return runCatching { downloadManager.getUriForDownloadedFile(downloadId) }
            .getOrNull()
    }

    private companion object {
        const val TAG = "DownloadHandler"
        const val BUFFER_SIZE = 128 * 1024
        const val UPDATE_INTERVAL_MILLIS = 250L
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}
