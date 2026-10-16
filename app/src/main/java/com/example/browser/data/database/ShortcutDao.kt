package com.example.browser.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShortcutDao {

    @Query("SELECT * FROM start_shortcuts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ShortcutEntity>>

    @Query("SELECT COUNT(*) FROM start_shortcuts")
    suspend fun count(): Int

    @Query("SELECT * FROM start_shortcuts WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): ShortcutEntity?

    @Query("SELECT * FROM start_shortcuts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ShortcutEntity?

    @Insert
    suspend fun insert(shortcut: ShortcutEntity): Long

    @Update
    suspend fun update(shortcut: ShortcutEntity)

    @Query("DELETE FROM start_shortcuts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
