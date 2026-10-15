package com.example.browser.ui.downloads

import android.app.DownloadManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.data.database.DownloadEntity
import com.example.browser.data.repository.DownloadRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DownloadStatus { RUNNING, PAUSED, PENDING, COMPLETED, FAILED, UNKNOWN }

class DownloadsViewModel(
    private val repository: DownloadRepository,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntity>> = repository.downloads.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }

    companion object {
        fun mapStatus(downloadManagerStatus: Int?): DownloadStatus = when (downloadManagerStatus) {
            DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
            DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
            DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
            DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
            DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
            else -> DownloadStatus.UNKNOWN
        }
    }
}
