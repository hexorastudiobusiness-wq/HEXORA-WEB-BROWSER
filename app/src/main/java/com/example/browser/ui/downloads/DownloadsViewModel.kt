package com.example.browser.ui.downloads

import android.app.DownloadManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.data.database.DownloadEntity
import com.example.browser.data.repository.DownloadRepository
import com.example.browser.download.DownloadHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadStatus {
    RUNNING, PAUSED, PENDING, COMPLETED, FAILED, CANCELLED, UNKNOWN,
}

/** Live state of one transfer, keyed by its transfer id. */
data class DownloadProgressUi(
    val status: DownloadStatus = DownloadStatus.UNKNOWN,
    /** 0f..1f, or null when the total size is not known yet. */
    val fraction: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    /** Raw handler/DownloadManager status this snapshot came from. */
    val rawStatus: Int? = null,
)

class DownloadsViewModel(
    private val repository: DownloadRepository,
    private val downloadHandler: DownloadHandler,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntity>> = repository.downloads.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _progress =
        MutableStateFlow<Map<Long, DownloadProgressUi>>(emptyMap())
    val progress: StateFlow<Map<Long, DownloadProgressUi>> = _progress.asStateFlow()

    init {
        // Event-driven progress (Firefox's download service likewise
        // broadcasts state changes): the handler pushes an update whenever
        // a transfer's progress changes, so there is no polling loop
        // burning CPU/battery while the downloads screen sits open.
        viewModelScope.launch {
            downloads.collect { entries ->
                // Seed rows that have no live progress yet (e.g. entries
                // restored from the database after a process restart).
                val seeded = entries.associate { entry ->
                    val id = entry.downloadManagerId
                    id to (_progress.value[id] ?: snapshotOf(entry))
                }
                if (seeded != _progress.value) _progress.value = seeded
                // A transfer can finish before its database row is inserted
                // (the row is recorded asynchronously when the download
                // starts). The progress event for that finish then finds no
                // row and is dropped; without persisting here, the row that
                // appears a moment later would keep a null lastStatus and
                // read as UNKNOWN after a process restart. Persisting the
                // seeded snapshot closes that race (persistSnapshot is a
                // no-op once the row already matches).
                entries.forEach { entry ->
                    seeded[entry.downloadManagerId]?.let { snapshot ->
                        persistSnapshot(entry, snapshot)
                    }
                }
            }
        }
        viewModelScope.launch {
            downloadHandler.progressUpdates.collect { id ->
                val entry = downloads.value
                    .firstOrNull { it.downloadManagerId == id }
                    ?: return@collect
                val snapshot = snapshotOf(entry)
                val current = _progress.value
                if (current[id] != snapshot) {
                    _progress.value = current + (id to snapshot)
                }
                persistSnapshot(entry, snapshot)
            }
        }
    }

    /**
     * Best current snapshot for [entry]: the live handler state while this
     * process knows the transfer, otherwise the state persisted with the
     * row (which is all that survives a process restart).
     */
    private fun snapshotOf(entry: DownloadEntity): DownloadProgressUi {
        val live = downloadHandler.progressFor(entry.downloadManagerId)
        if (live.status != null) {
            return DownloadProgressUi(
                status = mapStatus(live.status),
                fraction = live.fraction,
                downloadedBytes = live.downloadedBytes,
                totalBytes = live.totalBytes,
                rawStatus = live.status,
            )
        }
        return persistedSnapshotOf(entry)
    }

    private fun persistedSnapshotOf(entry: DownloadEntity): DownloadProgressUi {
        if (entry.sourceKind == DownloadEntity.SOURCE_LEGACY) {
            return DownloadProgressUi(status = DownloadStatus.UNKNOWN)
        }
        val fraction = if (entry.totalBytes > 0L) {
            (entry.downloadedBytes.toFloat() / entry.totalBytes.toFloat())
                .coerceIn(0f, 1f)
        } else {
            null
        }
        return DownloadProgressUi(
            // A transfer recorded as running when the process died is not
            // running anymore; its partial file can be resumed, so the
            // honest cold-start state is "paused" (interrupted).
            status = when (entry.lastStatus) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING ->
                    DownloadStatus.PAUSED
                else -> mapStatus(entry.lastStatus)
            },
            fraction = fraction,
            downloadedBytes = entry.downloadedBytes,
            totalBytes = entry.totalBytes,
            rawStatus = entry.lastStatus,
        )
    }

    /** Persists states the Downloads page must still show after a restart. */
    private fun persistSnapshot(entry: DownloadEntity, snapshot: DownloadProgressUi) {
        val rawStatus = snapshot.rawStatus ?: return
        if (snapshot.status !in PERSISTED_STATUSES) return
        val localUri = if (snapshot.status == DownloadStatus.COMPLETED) {
            downloadHandler.uriFor(entry.downloadManagerId)?.toString()
                ?: entry.localUri
        } else {
            entry.localUri
        }
        if (localUri == entry.localUri && rawStatus == entry.lastStatus &&
            snapshot.downloadedBytes == entry.downloadedBytes &&
            snapshot.totalBytes == entry.totalBytes
        ) {
            return
        }
        viewModelScope.launch {
            repository.updateState(
                id = entry.id,
                localUri = localUri,
                lastStatus = rawStatus,
                downloadedBytes = snapshot.downloadedBytes,
                totalBytes = snapshot.totalBytes,
            )
        }
    }

    /** URI of the finished file so the row can be opened with other apps. */
    fun completedUri(entry: DownloadEntity): Uri? =
        entry.localUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: downloadHandler.uriFor(entry.downloadManagerId)

    fun pause(entry: DownloadEntity) {
        downloadHandler.pause(entry.downloadManagerId)
        _progress.value = _progress.value +
            (entry.downloadManagerId to snapshotOf(entry))
    }

    fun resume(entry: DownloadEntity) {
        val resumed = downloadHandler.resume(entry.downloadManagerId)
        if (!resumed && entry.sourceKind == DownloadEntity.SOURCE_HTTP) {
            // No retained request in this process (e.g. after a restart):
            // only a fresh URL download can be attempted.
            retry(entry)
        } else {
            // One-shot stream downloads cannot be replayed; resume()
            // returning false leaves their paused state untouched.
            _progress.value = _progress.value +
                (entry.downloadManagerId to snapshotOf(entry))
        }
    }

    fun cancel(entry: DownloadEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                downloadHandler.cancel(entry.downloadManagerId)
            }
            _progress.value = _progress.value +
                (entry.downloadManagerId to snapshotOf(entry))
        }
    }

    fun delete(entry: DownloadEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                downloadHandler.cancel(entry.downloadManagerId)
            }
            repository.delete(entry.id)
        }
    }

    fun clearAll() {
        val entries = downloads.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                entries.forEach { downloadHandler.cancel(it.downloadManagerId) }
            }
            repository.clearAll()
        }
    }

    /**
     * Retries a transfer. HTTP(S) downloads first try to resume from the
     * partial file the handler kept (Range request); only when there is
     * nothing resumable does this start a fresh download.
     */
    fun retry(entry: DownloadEntity) {
        if (entry.sourceKind == DownloadEntity.SOURCE_STREAM) {
            // The response body was consumed once; it cannot be replayed.
            return
        }
        viewModelScope.launch {
            val resumed = withContext(Dispatchers.IO) {
                downloadHandler.resume(entry.downloadManagerId)
            }
            if (resumed) {
                _progress.value = _progress.value +
                    (entry.downloadManagerId to snapshotOf(entry))
                return@launch
            }
            val newId = withContext(Dispatchers.IO) {
                downloadHandler.cancel(entry.downloadManagerId)
                downloadHandler.download(
                    url = entry.url,
                    fileName = entry.fileName,
                    mimeType = entry.mimeType,
                )
            }
            if (newId >= 0) {
                repository.updateDownloadManagerId(entry.id, newId)
                // Force the next snapshot to describe the new transfer, not
                // the cancelled one still keyed by the old transfer id.
                _progress.value = _progress.value - entry.downloadManagerId
            }
        }
    }

    companion object {
        private val PERSISTED_STATUSES = setOf(
            DownloadStatus.PAUSED,
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
        )

        fun mapStatus(downloadManagerStatus: Int?): DownloadStatus =
            when (downloadManagerStatus) {
                DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                DownloadHandler.Progress.STATUS_CANCELLED ->
                    DownloadStatus.CANCELLED
                else -> DownloadStatus.UNKNOWN
            }
    }
}
