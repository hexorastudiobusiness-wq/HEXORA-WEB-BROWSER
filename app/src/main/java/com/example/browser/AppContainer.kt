package com.example.browser

import android.content.Context
import androidx.room.Room
import com.example.browser.browser.BrowserEngine
import com.example.browser.browser.GeckoBrowserEngine
import com.example.browser.data.database.AppDatabase
import com.example.browser.data.repository.BookmarkRepository
import com.example.browser.data.repository.CredentialRepository
import com.example.browser.data.repository.DownloadRepository
import com.example.browser.data.repository.HistoryRepository
import com.example.browser.data.repository.StartShortcutRepository
import com.example.browser.data.security.CredentialCipher
import com.example.browser.util.TabThumbnailStore
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
            // Dev-stage database: schema changes simply reset local data.
            .fallbackToDestructiveMigration(dropAllTables = true)
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

    val downloadRepository: DownloadRepository by lazy {
        DownloadRepository(database.downloadDao())
    }

    val tabThumbnailStore: TabThumbnailStore by lazy {
        TabThumbnailStore(appContext)
    }

    val credentialRepository: CredentialRepository by lazy {
        CredentialRepository(database.credentialDao(), CredentialCipher())
    }

    val startShortcutRepository: StartShortcutRepository by lazy {
        StartShortcutRepository(database.shortcutDao())
    }

    /**
     * The engine is created by the ViewModel once the persisted settings
     * snapshot (e.g. JavaScript on/off) is known.
     */
    fun createBrowserEngine(javaScriptEnabled: Boolean): BrowserEngine =
        GeckoBrowserEngine(appContext, javaScriptEnabled)
}
