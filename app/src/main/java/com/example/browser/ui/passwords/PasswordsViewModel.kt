package com.example.browser.ui.passwords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.data.repository.CredentialRepository
import com.example.browser.data.repository.SavedLoginMetadata
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class PasswordsViewModel(
    private val repository: CredentialRepository,
) : ViewModel() {

    /**
     * Decrypted login metadata. This stays empty until [unlock] runs after
     * the user passes device authentication — collecting the repository flow
     * eagerly (the previous `stateIn` wiring) decrypted every saved
     * username into memory for as long as the screen was merely composed,
     * even while the vault UI showed itself as locked.
     */
    private val _savedLogins = MutableStateFlow<List<SavedLoginMetadata>>(emptyList())
    val savedLogins: StateFlow<List<SavedLoginMetadata>> = _savedLogins.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Whether the vault is currently unlocked. Owned by the ViewModel (not
     * a composable-local flag) so the Activity can force a re-lock when the
     * app leaves the foreground and the screen can never disagree with the
     * ViewModel about whether decrypted metadata may be shown.
     */
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Starts loading metadata; call only after device authentication. */
    fun unlock() {
        if (_unlocked.value) return
        _unlocked.value = true
        loadJob = viewModelScope.launch {
            repository.savedLogins
                // A database/Keystore read failure shows an empty vault
                // instead of crashing the manager; rows are not deleted.
                .catch { _savedLogins.value = emptyList() }
                .collect { _savedLogins.value = it }
        }
    }

    /** Drops decrypted metadata and stops observing the vault. */
    fun lock() {
        _unlocked.value = false
        loadJob?.cancel()
        loadJob = null
        _savedLogins.value = emptyList()
    }

    fun delete(id: Long) {
        if (!_unlocked.value) return
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearAll() {
        if (!_unlocked.value) return
        viewModelScope.launch { repository.clearAll() }
    }

    override fun onCleared() {
        lock()
        super.onCleared()
    }
}
