package com.example.browser.ui.downloads

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.data.database.DownloadEntity
import com.example.browser.data.repository.DownloadRepository
import com.example.browser.download.DownloadHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadStatus { RUNNING, PAUSED, PENDING, COMPLETED, FAILED, UNKNOWN }

/** Live state of one DownloadManager transfer, keyed by DownloadManager id. */
data class DownloadProgressUi(
    val status: DownloadStatus = DownloadStatus.UNKNOWN,
    /** 0f..1f, or null when the total size is not known yet. */
    val fraction: Float? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
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
        // DownloadManager has no callback flow. Poll only the transfers that
        // can still change, on IO, once per second; this makes the page feel
        // alive without turning every row into a one-shot frozen snapshot.
        viewModelScope.launch {
            while (isActive) {
                val entries = downloads.value
                if (entries.isNotEmpty()) {
                    val updates = withContext(Dispatchers.IO) {
                        entries.associate { entry ->
                            val live = downloadHandler.progressFor(entry.downloadManagerId)
                            entry.downloadManagerId to DownloadProgressUi(
                                status = mapStatus(live.status),
                                fraction = live.fraction,
                                downloadedBytes = live.downloadedBytes,
                                totalBytes = live.totalBytes,
                            )
                        }
                    }
                    if (updates != _progress.value) _progress.value = updates
                    val hasActive = updates.values.any { ui ->
                        ui.status == DownloadStatus.RUNNING ||
                            ui.status == DownloadStatus.PENDING ||
                            ui.status == DownloadStatus.PAUSED
                    }
                    if (!hasActive) delay(IDLE_POLL_MILLIS) else delay(ACTIVE_POLL_MILLIS)
                } else {
                    if (_progress.value.isNotEmpty()) _progress.value = emptyMap()
                    delay(IDLE_POLL_MILLIS)
                }
            }
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

    /** Cancels a stuck/failed transfer and enqueues it again from scratch. */
    fun retry(entry: DownloadEntity) {
        viewModelScope.launch {
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
                // Force the next poll to describe the new transfer, not the
                // cancelled one still keyed by the old manager id.
                _progress.value = _progress.value - entry.downloadManagerId
            }
        }
    }

    companion object {
        private const val ACTIVE_POLL_MILLIS = 1_000L
        private const val IDLE_POLL_MILLIS = 3_000L

        fun mapStatus(downloadManagerStatus: Int?): DownloadStatus =
            when (downloadManagerStatus) {
                DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                else -> DownloadStatus.UNKNOWN
            }
    }
}
