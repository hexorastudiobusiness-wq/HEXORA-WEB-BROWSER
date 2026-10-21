package com.example.browser.data.repository

import com.example.browser.data.database.BookmarkDao
import com.example.browser.data.database.BookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-facing bookmarks API. Hides Room from ViewModels (ARCHITECTURE.md §20). */
class BookmarkRepository(private val dao: BookmarkDao) {

    val bookmarks: Flow<List<BookmarkEntity>> = dao.observeAll()

    /** Serializes check-then-act writes (see [toggle]). */
    private val writeMutex = Mutex()

    fun observeIsBookmarked(url: String): Flow<Boolean> = dao.observeIsBookmarked(url)

    suspend fun add(url: String, title: String) {
        dao.insert(
            BookmarkEntity(
                url = url,
                title = title.ifBlank { url },
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeByUrl(url: String) = dao.deleteByUrl(url)

    suspend fun removeById(id: Long) = dao.deleteById(id)

    /** @return true if the page is bookmarked after the toggle. */
    suspend fun toggle(url: String, title: String): Boolean = writeMutex.withLock {
        // Check-then-act must be atomic: two rapid toggles (e.g. star button
        // double-tap) could otherwise both insert or both delete.
        if (dao.getByUrl(url) != null) {
            dao.deleteByUrl(url)
            false
        } else {
            add(url, title)
            true
        }
    }
}
