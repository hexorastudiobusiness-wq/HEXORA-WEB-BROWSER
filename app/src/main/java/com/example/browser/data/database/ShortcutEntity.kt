package com.example.browser.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-created shortcut shown on the Hexora Start Page. */
@Entity(
    tableName = "start_shortcuts",
    indices = [Index(value = ["url"], unique = true)],
)
data class ShortcutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long,
)
