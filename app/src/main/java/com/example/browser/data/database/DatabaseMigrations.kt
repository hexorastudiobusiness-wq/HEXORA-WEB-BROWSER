package com.example.browser.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit upgrades to the current schema (version 5).
 *
 * Earlier builds used a destructive fallback, which silently erased local
 * data on schema changes. These direct migrations preserve existing rows
 * for every schema this app has shipped:
 *
 * - v1: history + bookmarks
 * - v2: + downloads
 * - v3: + credentials, and history.visitCount (removed again in v4)
 * - v4: + start_shortcuts, history without visitCount
 * - v5: downloads gain persisted terminal state (published file URI,
 *   source kind, last status, byte counts) so the Downloads page stays
 *   truthful after a process restart
 */
object DatabaseMigrations {

    val MIGRATION_1_5: Migration = object : Migration(1, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createDownloads(db)
            createCredentials(db)
            createShortcuts(db)
        }
    }

    val MIGRATION_2_5: Migration = object : Migration(2, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createCredentials(db)
            createShortcuts(db)
            addDownloadState(db)
        }
    }

    val MIGRATION_3_5: Migration = object : Migration(3, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v3 history had a visitCount column that v4 removed. Recreate
            // the table (DROP COLUMN is not available on older SQLite
            // versions bundled with supported Android releases).
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `history_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO `history_new` (`id`, `url`, `title`, `timestamp`) " +
                    "SELECT `id`, `url`, `title`, `timestamp` FROM `history`",
            )
            db.execSQL("DROP TABLE IF EXISTS `history`")
            db.execSQL("ALTER TABLE `history_new` RENAME TO `history`")
            createShortcuts(db)
            addDownloadState(db)
        }
    }

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addDownloadState(db)
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_5,
        MIGRATION_2_5,
        MIGRATION_3_5,
        MIGRATION_4_5,
    )

    private fun createDownloads(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `downloads` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`downloadManagerId` INTEGER NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`fileName` TEXT NOT NULL, " +
                "`mimeType` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`localUri` TEXT, " +
                "`sourceKind` TEXT NOT NULL DEFAULT 'LEGACY', " +
                "`lastStatus` INTEGER, " +
                "`downloadedBytes` INTEGER NOT NULL DEFAULT 0, " +
                "`totalBytes` INTEGER NOT NULL DEFAULT -1)",
        )
    }

    /** v5 columns for a downloads table created by an older schema. */
    private fun addDownloadState(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `localUri` TEXT")
        db.execSQL(
            "ALTER TABLE `downloads` ADD COLUMN `sourceKind` TEXT NOT NULL DEFAULT 'LEGACY'",
        )
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `lastStatus` INTEGER")
        db.execSQL(
            "ALTER TABLE `downloads` ADD COLUMN `downloadedBytes` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `downloads` ADD COLUMN `totalBytes` INTEGER NOT NULL DEFAULT -1",
        )
    }

    private fun createCredentials(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `credentials` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`origin` TEXT NOT NULL, " +
                "`usernameCiphertext` TEXT NOT NULL, " +
                "`passwordCiphertext` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_credentials_origin` " +
                "ON `credentials` (`origin`)",
        )
    }

    private fun createShortcuts(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `start_shortcuts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_start_shortcuts_url` " +
                "ON `start_shortcuts` (`url`)",
        )
    }
}
