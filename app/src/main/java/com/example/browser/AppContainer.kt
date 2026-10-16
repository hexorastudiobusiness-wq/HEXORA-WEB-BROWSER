package com.example.browser

import android.content.Context
import androidx.room.Room
import com.example.browser.browser.BrowserEngine
import com.example.browser.browser.GeckoBrowserEngine
import com.example.browser.data.database.AppDatabase
import com.example.browser.data.database.DatabaseMigrations
import com.example.browser.data.repository.BookmarkRepository
import com.example.browser.data.repository.CredentialRepository
import com.example.browser.data.repository.DownloadRepository
import com.example.browser.data.repository.HistoryRepository
import com.example.browser.data.repository.FaviconFetcher
import com.example.browser.data.repository.StartShortcutRepository
import com.example.browser.data.security.CredentialCipher
import com.example.browser.util.ShortcutIconStore
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
            // No destructive fallback: browsing data and saved logins must
            // never be silently wiped by a schema change. Explicit migrations
            // cover every shipped schema; future bumps must add another one.
            .addMigrations(*DatabaseMigrations.ALL)
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

    val shortcutIconStore: ShortcutIconStore by lazy {
        ShortcutIconStore(appContext)
    }

    val faviconFetcher: FaviconFetcher by lazy { FaviconFetcher() }

    /**
     * The engine is created by the ViewModel once the persisted settings
     * snapshot (e.g. JavaScript on/off) is known.
     */
    fun createBrowserEngine(javaScriptEnabled: Boolean): BrowserEngine =
        GeckoBrowserEngine(appContext, javaScriptEnabled)
}
