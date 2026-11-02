package com.example.browser.download

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.browser.R

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
    private val streams = ConcurrentHashMap<Long, InputStream>()
    private val completedUris = ConcurrentHashMap<Long, Uri>()
    private val fileNames = ConcurrentHashMap<Long, String>()
    private val pausedIds = ConcurrentHashMap.newKeySet<Long>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<Long>()
    /**
     * Transfers whose job has been asked to stop but has not finished yet.
     * A cancelled coroutine unwinds asynchronously (closing a connection or
     * stream, deleting a published file); until it completes, the same
     * transfer id/temp file must not be resumed or two writers can race on
     * one `.part` file.
     */
    private val stoppingIds = ConcurrentHashMap.newKeySet<Long>()

    private data class NetworkRequest(
        val url: String,
        val fileName: String,
        val mimeType: String?,
        val pageUrl: String?,
    ) {
        /** Validators from the latest response, sent as If-Range on resume. */
        @Volatile var entityTag: String? = null
        @Volatile var lastModified: String? = null

        fun validator(): String? = entityTag ?: lastModified
    }

    /** Original request for HTTP(S) transfers, retained for real resume. */
    private val networkRequests = ConcurrentHashMap<Long, NetworkRequest>()

    private val _progressUpdates =
        MutableSharedFlow<Long>(extraBufferCapacity = 64)

    /**
     * Ids of transfers whose progress just changed. Event-driven (the
     * download layer pushes updates as bytes move), so the UI never has to
     * poll — Firefox's download service likewise broadcasts state changes.
     */
    val progressUpdates: SharedFlow<Long> = _progressUpdates.asSharedFlow()

    /** Records a transfer's progress and notifies [progressUpdates] listeners. */
    private fun setProgress(id: Long, progress: Progress) {
        progressById[id] = progress
        _progressUpdates.tryEmit(id)
    }

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
        networkRequests[id] = NetworkRequest(url, safeName, mimeType, pageUrl)
        setProgress(id, Progress(DownloadManager.STATUS_PENDING))
        val job = scope.launch {
            performDownload(id, url, safeName, mimeType, pageUrl)
        }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id, job)
            stoppingIds.remove(id)
            connections.remove(id)
            updateDownloadService()
        }
        updateDownloadService()
        return id
    }

    private suspend fun performDownload(
        id: Long,
        url: String,
        fileName: String,
        mimeType: String?,
        pageUrl: String?,
        resume: Boolean = false,
    ) {
        val tempDir = File(appContext.cacheDir, "downloads").apply { mkdirs() }
        val tempFile = File(tempDir, "$id.part")
        var connection: HttpURLConnection? = null
        var publishedUri: Uri? = null
        try {
            setProgress(id, Progress(DownloadManager.STATUS_RUNNING))
            val opened = URL(url.replace(" ", "%20")).openConnection() as HttpURLConnection
            connection = opened
            connections[id] = opened
            opened.instanceFollowRedirects = true
            opened.connectTimeout = CONNECT_TIMEOUT_MILLIS
            opened.readTimeout = READ_TIMEOUT_MILLIS
            opened.requestMethod = "GET"
            opened.setRequestProperty("Accept", "*/*")
            opened.setRequestProperty("Accept-Encoding", "identity")
            val existingBytes = if (resume && tempFile.exists()) tempFile.length() else 0L
            if (existingBytes > 0L) {
                opened.setRequestProperty("Range", "bytes=$existingBytes-")
                // If the file changed since the partial download, the
                // server must send the whole new file instead of a slice.
                networkRequests[id]?.validator()?.let { validator ->
                    opened.setRequestProperty("If-Range", validator)
                }
            }
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
            // Remember validators so a later resume can send If-Range and
            // never append bytes belonging to a changed file.
            networkRequests[id]?.let { request ->
                opened.getHeaderField("ETag")?.let { request.entityTag = it }
                opened.getHeaderField("Last-Modified")
                    ?.let { request.lastModified = it }
            }
            var append = false
            if (existingBytes > 0L && responseCode == 206) {
                // Never trust a 206 blindly: appending a slice that does
                // not start exactly where the partial file ends would
                // silently corrupt the download.
                val rangeStart = opened.getHeaderField("Content-Range")
                    ?.substringAfter("bytes ", "")
                    ?.substringBefore('-')
                    ?.trim()
                    ?.toLongOrNull()
                if (rangeStart != existingBytes) {
                    throw IOException(
                        "Range response does not match partial download",
                    )
                }
                append = true
            }
            val initialBytes = if (append) existingBytes else 0L
            val total = when {
                append && opened.contentLengthLong > 0 ->
                    existingBytes + opened.contentLengthLong
                opened.contentLengthLong > 0 -> opened.contentLengthLong
                else -> progressById[id]?.totalBytes ?: -1L
            }
            val downloaded = opened.inputStream.buffered().use { input ->
                copyToTempFile(
                    id = id,
                    input = input,
                    tempFile = tempFile,
                    total = total,
                    append = append,
                    initialDownloaded = initialBytes,
                )
            }
            // Check this transfer's own coroutine, not the shared scope:
            // cancelling one download must not publish its partial file,
            // and cancelling the scope must not be the only way to stop.
            currentCoroutineContext().ensureActive()
            publishedUri = publish(tempFile, fileName, mimeType)
            // Publishing copies the file on this thread and cannot be
            // interrupted mid-copy: a pause/cancel that landed during the
            // copy must still win over the success state below.
            if (id in cancelledIds || id in pausedIds) {
                throw CancellationException("Download stopped while publishing")
            }
            completedUris[id] = publishedUri
            val finalTotal = if (total > 0) total else downloaded
            setProgress(
                id,
                Progress(
                    status = DownloadManager.STATUS_SUCCESSFUL,
                    downloadedBytes = downloaded,
                    totalBytes = finalTotal,
                ),
            )
            notifyDownloadCompleted(id, fileName)
        } catch (cancelled: CancellationException) {
            // If the stop landed while publish() was copying, the file is
            // already in the Downloads folder; a cancelled/paused transfer
            // must not leave a "completed" file behind.
            publishedUri?.let { uri ->
                deletePublishedUri(uri)
                completedUris.remove(id)
            }
            when {
                id in cancelledIds -> {
                    tempFile.delete()
                    setProgress(
                        id,
                        Progress(
                            status = Progress.STATUS_CANCELLED,
                            downloadedBytes = 0L,
                            totalBytes = progressById[id]?.totalBytes ?: -1L,
                        ),
                    )
                }
                id in pausedIds -> {
                    setProgress(
                        id,
                        Progress(
                            status = DownloadManager.STATUS_PAUSED,
                            downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L,
                            totalBytes = progressById[id]?.totalBytes ?: -1L,
                        ),
                    )
                }
                else -> tempFile.delete()
            }
            throw cancelled
        } catch (error: Exception) {
            if (id in cancelledIds) {
                tempFile.delete()
                setProgress(id, Progress(Progress.STATUS_CANCELLED))
            } else if (id in pausedIds) {
                setProgress(
                    id,
                    Progress(
                        status = DownloadManager.STATUS_PAUSED,
                        downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L,
                        totalBytes = progressById[id]?.totalBytes ?: -1L,
                    ),
                )
            } else {
                Log.w(TAG, "Download failed", error)
                // Keep the partial file: retrying an HTTP(S) download
                // resumes from these bytes instead of starting over.
                setProgress(
                    id,
                    Progress(
                        status = DownloadManager.STATUS_FAILED,
                        downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L,
                        totalBytes = progressById[id]?.totalBytes ?: -1L,
                    ),
                )
            }
        } finally {
            connections.remove(id)
            connection?.disconnect()
        }
    }

    /**
     * Starts a transfer from a response body the engine already received.
     * This is the only path that works for blob/data/POST/authenticated
     * downloads (e.g. a generated ZIP export), which cannot be replayed as a
     * fresh GET. Returns the transfer id; the stream is consumed (and
     * closed) on the IO thread.
     */
    fun downloadStream(
        bodySource: () -> InputStream?,
        fileName: String,
        mimeType: String?,
        contentLength: Long = -1L,
    ): Long {
        val safeName = DownloadFileName.sanitize(fileName)
        val id = nextId.incrementAndGet()
        fileNames[id] = safeName
        setProgress(id, Progress(DownloadManager.STATUS_PENDING))
        val job = scope.launch {
            performStreamDownload(id, bodySource, safeName, mimeType, contentLength)
        }
        jobs[id] = job
        job.invokeOnCompletion {
            jobs.remove(id, job)
            stoppingIds.remove(id)
            streams.remove(id)
            updateDownloadService()
        }
        updateDownloadService()
        return id
    }

    private suspend fun performStreamDownload(
        id: Long,
        bodySource: () -> InputStream?,
        fileName: String,
        mimeType: String?,
        contentLength: Long,
    ) {
        val tempDir = File(appContext.cacheDir, "downloads").apply { mkdirs() }
        val tempFile = File(tempDir, "$id.part")
        var stream: InputStream? = null
        var publishedUri: Uri? = null
        try {
            setProgress(id, Progress(DownloadManager.STATUS_RUNNING))
            val opened = bodySource()
                ?: throw IllegalStateException("Download body unavailable")
            stream = opened
            streams[id] = opened
            val downloaded = opened.buffered().use { input ->
                copyToTempFile(id, input, tempFile, contentLength)
            }
            currentCoroutineContext().ensureActive()
            publishedUri = publish(tempFile, fileName, mimeType)
            // Same publish race as the HTTP path: a pause/cancel that
            // arrived while the file was being copied must win.
            if (id in cancelledIds || id in pausedIds) {
                throw CancellationException("Stream download stopped while publishing")
            }
            completedUris[id] = publishedUri
            val finalTotal = if (contentLength > 0) contentLength else downloaded
            setProgress(
                id,
                Progress(
                    status = DownloadManager.STATUS_SUCCESSFUL,
                    downloadedBytes = downloaded,
                    totalBytes = finalTotal,
                ),
            )
            notifyDownloadCompleted(id, fileName)
        } catch (cancelled: CancellationException) {
            publishedUri?.let { uri ->
                deletePublishedUri(uri)
                completedUris.remove(id)
            }
            when {
                id in cancelledIds -> {
                    tempFile.delete()
                    setProgress(
                        id,
                        Progress(
                            status = Progress.STATUS_CANCELLED,
                            downloadedBytes = 0L,
                            totalBytes = contentLength,
                        ),
                    )
                }
                id in pausedIds -> {
                    setProgress(
                        id,
                        Progress(
                            status = DownloadManager.STATUS_PAUSED,
                            downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L,
                            totalBytes = contentLength,
                        ),
                    )
                }
                else -> tempFile.delete()
            }
            throw cancelled
        } catch (error: Exception) {
            if (id in cancelledIds) {
                tempFile.delete()
                setProgress(id, Progress(Progress.STATUS_CANCELLED))
            } else if (id in pausedIds) {
                setProgress(
                    id,
                    Progress(
                        status = DownloadManager.STATUS_PAUSED,
                        downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L,
                        totalBytes = contentLength,
                    ),
                )
            } else {
                Log.w(TAG, "Stream download failed", error)
                tempFile.delete()
                setProgress(id, Progress(DownloadManager.STATUS_FAILED))
            }
        } finally {
            streams.remove(id)
            runCatching { stream?.close() }
        }
    }

    /** Streams [input] into [tempFile], reporting progress; returns bytes copied. */
    private suspend fun copyToTempFile(
        id: Long,
        input: InputStream,
        tempFile: File,
        total: Long,
        append: Boolean = false,
        initialDownloaded: Long = 0L,
    ): Long {
        var downloaded = initialDownloaded
        var lastUpdateAt = 0L
        FileOutputStream(tempFile, append).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                // This transfer's job — cancelling it must stop the copy
                // (cancel()/pause() also close the stream/connection, which
                // unblocks a read parked inside the engine or network).
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                downloaded += read
                val now = System.currentTimeMillis()
                if (now - lastUpdateAt >= UPDATE_INTERVAL_MILLIS ||
                    (total > 0 && downloaded == total)
                ) {
                    lastUpdateAt = now
                    setProgress(
                        id,
                        Progress(
                            status = DownloadManager.STATUS_RUNNING,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        ),
                    )
                }
            }
        }
        // A stream that ends early must never be published as a complete
        // file: when the length is known, byte counts have to match.
        if (total > 0L && downloaded != total) {
            throw IOException("Incomplete download: $downloaded of $total bytes")
        }
        return downloaded
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
                // Only a row that actually left the pending state is a
                // completed, visible download. If the update did not apply,
                // the catch below removes the pending entry and the
                // transfer is reported failed instead of "successful" with
                // an invisible file.
                check(resolver.update(uri, done, null, null) == 1) {
                    "MediaStore publish did not complete"
                }
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
        // A raw file:// URI throws FileUriExposedException when handed to
        // another app on API 24+; always go through the FileProvider.
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            target,
        )
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
        // All numbered variants exist too: fall back to a timestamped name
        // instead of returning an existing file, which made copyTo() fail
        // and the whole download report FAILED for a name collision alone.
        val stamp = System.currentTimeMillis()
        return if (extension.isBlank()) {
            File(dir, "$base-$stamp")
        } else {
            File(dir, "$base-$stamp.$extension")
        }
    }

    /** Best-effort removal of a file [publish] already made visible. */
    private fun deletePublishedUri(uri: Uri) {
        runCatching { appContext.contentResolver.delete(uri, null, null) }
    }

    data class Progress(
        val status: Int?,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val reason: Int? = null,
    ) {
        companion object {
            /** User-cancelled transfer (DownloadManager has no such status). */
            const val STATUS_CANCELLED = -2
        }

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

    /**
     * Pauses a running transfer without deleting its partial file. HTTP(S)
     * transfers can later resume with a Range request when the server
     * supports it; one-shot Gecko streams cannot be recreated after pause.
     */
    fun pause(downloadId: Long) {
        // A completed transfer stays completed even if a stale pause lands
        // while its job is finishing.
        if (progressById[downloadId]?.status == DownloadManager.STATUS_SUCCESSFUL) return
        if (!jobs.containsKey(downloadId)) return
        pausedIds += downloadId
        stoppingIds += downloadId
        connections.remove(downloadId)?.disconnect()
        runCatching { streams.remove(downloadId)?.close() }
        // Keep the job registered until it actually completes: resume()
        // must be able to tell "still unwinding" apart from "stopped".
        jobs[downloadId]?.cancel()
        val tempFile = tempFileFor(downloadId)
        setProgress(
            downloadId,
            Progress(
                status = DownloadManager.STATUS_PAUSED,
                downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L,
                totalBytes = progressById[downloadId]?.totalBytes ?: -1L,
            ),
        )
        updateDownloadService()
    }

    /** Resumes a paused HTTP(S) transfer; false when it cannot be resumed. */
    /** True while the original HTTP request is retained, including stop unwinding. */
    fun hasRetainedRequest(downloadId: Long): Boolean = networkRequests.containsKey(downloadId)

    fun resume(downloadId: Long): Boolean {
        if (progressById[downloadId]?.status == DownloadManager.STATUS_SUCCESSFUL) return true
        // The previous job is still unwinding after pause/cancel; starting
        // another writer on the same temp file now could corrupt it. The
        // caller can retry once the completion event lands.
        if (downloadId in stoppingIds) return false
        val request = networkRequests[downloadId] ?: return false
        if (jobs.containsKey(downloadId)) return true
        pausedIds.remove(downloadId)
        cancelledIds.remove(downloadId)
        setProgress(
            downloadId,
            Progress(
                status = DownloadManager.STATUS_PENDING,
                downloadedBytes = progressById[downloadId]?.downloadedBytes ?: 0L,
                totalBytes = progressById[downloadId]?.totalBytes ?: -1L,
            ),
        )
        val job = scope.launch {
            performDownload(
                id = downloadId,
                url = request.url,
                fileName = request.fileName,
                mimeType = request.mimeType,
                pageUrl = request.pageUrl,
                resume = true,
            )
        }
        jobs[downloadId] = job
        job.invokeOnCompletion {
            jobs.remove(downloadId, job)
            stoppingIds.remove(downloadId)
            connections.remove(downloadId)
            updateDownloadService()
        }
        updateDownloadService()
        return true
    }

    private fun tempFileFor(downloadId: Long): File =
        File(File(appContext.cacheDir, "downloads").apply { mkdirs() }, "$downloadId.part")

    /** Cancels a Hexora transfer, or removes a legacy DownloadManager entry. */
    fun cancel(downloadId: Long) {
        // Same completion guard as pause(): cancelling cannot rewrite a
        // transfer that already published its file successfully.
        if (progressById[downloadId]?.status == DownloadManager.STATUS_SUCCESSFUL) return
        val hadOwnTransfer = jobs.containsKey(downloadId) ||
            progressById.containsKey(downloadId)
        if (hadOwnTransfer) {
            cancelledIds += downloadId
            stoppingIds += downloadId
        }
        connections.remove(downloadId)?.disconnect()
        // Closing the stream also unblocks a read the job is parked in.
        runCatching { streams.remove(downloadId)?.close() }
        jobs[downloadId]?.cancel()
        pausedIds.remove(downloadId)
        tempFileFor(downloadId).delete()
        completedUris.remove(downloadId)
        if (hadOwnTransfer) {
            // Keep the request and a truthful CANCELLED state: the row can
            // show what happened, and an HTTP transfer can be restarted.
            setProgress(
                downloadId,
                Progress(
                    status = Progress.STATUS_CANCELLED,
                    downloadedBytes = 0L,
                    totalBytes = progressById[downloadId]?.totalBytes ?: -1L,
                ),
            )
        } else if (downloadId > 0) {
            runCatching { downloadManager.remove(downloadId) }
        }
        updateDownloadService()
    }

    private fun updateDownloadService() {
        val intent = Intent(appContext, DownloadForegroundService::class.java)
        if (jobs.values.any { it.isActive }) {
            runCatching { ContextCompat.startForegroundService(appContext, intent) }
        } else {
            runCatching { appContext.stopService(intent) }
        }
    }

    private fun notifyDownloadCompleted(id: Long, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    DOWNLOAD_NOTIFICATION_CHANNEL,
                    appContext.getString(R.string.downloads_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
        val pendingIntent = launchIntent?.let { intent ->
            android.app.PendingIntent.getActivity(
                appContext,
                id.toInt(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(
            appContext,
            DOWNLOAD_NOTIFICATION_CHANNEL,
        )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(appContext.getString(R.string.download_completed))
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { manager.notify(id.toInt(), notification) }
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
        const val DOWNLOAD_NOTIFICATION_CHANNEL = "downloads"
        const val BUFFER_SIZE = 128 * 1024
        const val UPDATE_INTERVAL_MILLIS = 250L
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}

/** Keeps active transfers alive while Hexora is backgrounded. */
class DownloadForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.downloads_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.downloads_title))
            .setContentText(getString(R.string.download_status_running))
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: SecurityException) {
            // Promotion can fail asynchronously inside the service even
            // though startForegroundService() succeeded in the caller.
            Log.w("HexoraDownloads", "Download service permission denied", error)
            stopSelf(startId)
        } catch (error: IllegalStateException) {
            Log.w("HexoraDownloads", "Download service promotion disallowed", error)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android otherwise terminates the whole process for an expired
        // dataSync budget. Stop the service within the system grace period.
        stopSelf(startId)
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1001
    }
}
