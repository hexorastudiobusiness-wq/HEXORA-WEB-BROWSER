package com.example.browser.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.data.database.HistoryEntity
import com.example.browser.data.repository.HistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: HistoryRepository,
) : ViewModel() {

    val history: StateFlow<List<HistoryEntity>> = repository.history.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun deleteEntry(id: Long) {
        viewModelScope.launch { repository.deleteEntry(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }
}
