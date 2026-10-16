package com.example.browser.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        HistoryEntity::class,
        BookmarkEntity::class,
        DownloadEntity::class,
        CredentialEntity::class,
        ShortcutEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadDao(): DownloadDao
    abstract fun credentialDao(): CredentialDao
    abstract fun shortcutDao(): ShortcutDao
}
