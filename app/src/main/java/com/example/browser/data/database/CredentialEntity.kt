package com.example.browser.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One locally saved login. Username and password are stored only as
 * Android Keystore-backed AES-GCM ciphertext — never as plain text and
 * never transmitted anywhere by Hexora.
 */
@Entity(
    tableName = "credentials",
    indices = [Index(value = ["origin"])],
)
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Exact web origin, e.g. https://example.com or https://example.com:8443. */
    val origin: String,
    val usernameCiphertext: String,
    val passwordCiphertext: String,
    val createdAt: Long,
    val updatedAt: Long,
)
