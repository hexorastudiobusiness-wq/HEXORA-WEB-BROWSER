package com.example.browser

import android.content.Context
import androidx.room.Room
import com.example.browser.browser.BrowserEngine
import com.example.browser.browser.GeckoBrowserEngine
import com.example.browser.data.database.AppDatabase
import com.example.browser.data.repository.BookmarkRepository
import com.example.browser.data.repository.HistoryRepository
import com.example.browser.data.settings.SettingsRepository
import com.example.browser.download.DownloadHandler

/**
 * Minimal manual dependency container (no DI framework needed at this size).
 * Single instances of the database, repositories and download handler live
 * here for the whole process.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "hexora-browser.db")
            .build()
    }

    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(database.historyDao())
    }

    val bookmarkRepository: BookmarkRepository by lazy {
        BookmarkRepository(database.bookmarkDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val downloadHandler: DownloadHandler by lazy {
        DownloadHandler(appContext)
    }

    /**
     * The engine is created by the ViewModel once the persisted settings
     * snapshot (e.g. JavaScript on/off) is known.
     */
    fun createBrowserEngine(javaScriptEnabled: Boolean): BrowserEngine =
        GeckoBrowserEngine(appContext, javaScriptEnabled)
}
