package com.example.browser.data.repository

import com.example.browser.data.database.CredentialDao
import com.example.browser.data.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A decrypted login, used only in memory for autofill after user opt-in. */
data class SavedCredential(
    val id: Long,
    val origin: String,
    val username: String,
    val password: String,
    val updatedAt: Long,
)

/** Metadata safe to show in the saved-logins manager (no password). */
data class SavedLoginMetadata(
    val id: Long,
    val origin: String,
    /** Null when this row can no longer be decrypted on this device. */
    val username: String?,
    val updatedAt: Long,
) {
    val isReadable: Boolean get() = username != null
}

/**
 * Offline password vault. Values are encrypted before Room sees them and
 * are decrypted only in this app process when needed for autofill or for
 * showing the username in the vault manager.
 */
class CredentialRepository(
    private val dao: CredentialDao,
    private val cipher: CredentialCipher,
) {

    /**
     * Serializes the find-then-insert/update in [save]. Usernames are
     * encrypted with randomized GCM, so the database cannot enforce a
     * unique (origin, username) constraint; two rapid submissions of the
     * same login form could otherwise insert duplicate rows.
     */
    private val saveMutex = Mutex()

    val savedLogins: Flow<List<SavedLoginMetadata>> = dao.observeAll().map { entries ->
        // Keep rows whose ciphertext can no longer be decrypted (for
        // example after the device credential changed and the Keystore key
        // was invalidated) visible in the manager: they can never be used
        // for autofill again, but the user must still be able to see that
        // they exist and delete them instead of having them stuck in the
        // database forever, invisible and unremovable.
        entries.map { entry ->
            SavedLoginMetadata(
                id = entry.id,
                origin = entry.origin,
                username = cipher.decrypt(entry.usernameCiphertext),
                updatedAt = entry.updatedAt,
            )
        }
    }

    suspend fun findForOrigin(origin: String): List<SavedCredential> =
        dao.getByOrigin(origin).mapNotNull { entry ->
            val username = cipher.decrypt(entry.usernameCiphertext) ?: return@mapNotNull null
            val password = cipher.decrypt(entry.passwordCiphertext) ?: return@mapNotNull null
            SavedCredential(
                id = entry.id,
                origin = entry.origin,
                username = username,
                password = password,
                updatedAt = entry.updatedAt,
            )
        }

    suspend fun save(origin: String, username: String, password: String) =
        saveMutex.withLock {
            val now = System.currentTimeMillis()
            val existing = findForOrigin(origin).firstOrNull { it.username == username }
            if (existing == null) {
                dao.insert(
                    com.example.browser.data.database.CredentialEntity(
                        origin = origin,
                        usernameCiphertext = cipher.encrypt(username),
                        passwordCiphertext = cipher.encrypt(password),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else {
                // Created-at is not exposed by SavedCredential; Room @Update
                // needs the full row, so re-read the encrypted row by id
                // through origin.
                val row = dao.getByOrigin(origin).firstOrNull { it.id == existing.id }
                    ?: return@withLock
                dao.update(
                    row.copy(
                        passwordCiphertext = cipher.encrypt(password),
                        updatedAt = now,
                    ),
                )
            }
        }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}
