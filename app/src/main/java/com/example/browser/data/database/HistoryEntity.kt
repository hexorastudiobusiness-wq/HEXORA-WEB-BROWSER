package com.example.browser.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val timestamp: Long,
    /** Number of recorded visits, used by the Start Page top sites. */
    val visitCount: Int = 1,
)
