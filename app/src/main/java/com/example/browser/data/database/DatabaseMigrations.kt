package com.example.browser.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit upgrades to the current schema (version 4).
 *
 * Earlier builds used a destructive fallback, which silently erased local
 * data on schema changes. These direct migrations preserve existing rows
 * for every schema this app has shipped:
 *
 * - v1: history + bookmarks
 * - v2: + downloads
 * - v3: + credentials, and history.visitCount (removed again in v4)
 * - v4: + start_shortcuts, history without visitCount
 */
object DatabaseMigrations {

    val MIGRATION_1_4: Migration = object : Migration(1, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createDownloads(db)
            createCredentials(db)
            createShortcuts(db)
        }
    }

    val MIGRATION_2_4: Migration = object : Migration(2, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createCredentials(db)
            createShortcuts(db)
        }
    }

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
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
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_4, MIGRATION_2_4, MIGRATION_3_4)

    private fun createDownloads(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `downloads` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`downloadManagerId` INTEGER NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`fileName` TEXT NOT NULL, " +
                "`mimeType` TEXT, " +
                "`createdAt` INTEGER NOT NULL)",
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
