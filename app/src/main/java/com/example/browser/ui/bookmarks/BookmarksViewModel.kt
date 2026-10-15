package com.example.browser.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.data.database.BookmarkEntity
import com.example.browser.data.repository.BookmarkRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BookmarksViewModel(
    private val repository: BookmarkRepository,
) : ViewModel() {

    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.bookmarks.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun removeBookmark(id: Long) {
        viewModelScope.launch { repository.removeById(id) }
    }
}
