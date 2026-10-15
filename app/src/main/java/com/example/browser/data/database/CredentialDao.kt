package com.example.browser.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    @Query("SELECT * FROM credentials ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials WHERE origin = :origin ORDER BY updatedAt DESC")
    suspend fun getByOrigin(origin: String): List<CredentialEntity>

    @Insert
    suspend fun insert(entry: CredentialEntity)

    @Update
    suspend fun update(entry: CredentialEntity)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM credentials")
    suspend fun clearAll()
}
