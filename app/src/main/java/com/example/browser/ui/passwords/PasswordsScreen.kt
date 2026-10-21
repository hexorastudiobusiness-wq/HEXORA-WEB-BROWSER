package com.example.browser.ui.passwords

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.R

/**
 * Saved-login manager. Passwords themselves are intentionally never shown
 * here; entries can only be removed. Values stay encrypted on this device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordsScreen(
    viewModel: PasswordsViewModel,
    onBack: () -> Unit,
    onAuthenticate: (() -> Unit) -> Unit = { callback -> callback() },
    modifier: Modifier = Modifier,
) {
    val logins by viewModel.savedLogins.collectAsState()
    val unlocked by viewModel.unlocked.collectAsState()
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    // Leaving the vault re-locks it: decrypted usernames must not linger in
    // the (activity-scoped) ViewModel after the user navigates away.
    DisposableEffect(viewModel) {
        onDispose { viewModel.lock() }
    }

    if (showDeleteAllConfirm) {
        // Deleting the whole vault is irreversible and was previously a
        // single top-bar tap; it requires the same explicit confirmation
        // as the browser's other destructive actions.
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = {
                Text(
                    stringResource(
                        R.string.passwords_delete_all_confirm_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.passwords_delete_all_confirm_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllConfirm = false
                        viewModel.clearAll()
                    },
                ) {
                    Text(stringResource(R.string.passwords_delete_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.passwords_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (unlocked && logins.isNotEmpty()) {
                        TextButton(onClick = { showDeleteAllConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Text(stringResource(R.string.passwords_delete_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!unlocked) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.passwords_locked),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.passwords_locked_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = {
                            onAuthenticate {
                                viewModel.unlock()
                            }
                        },
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Text(stringResource(R.string.passwords_unlock))
                    }
                }
                return@Column
            }
            Text(
                text = stringResource(R.string.passwords_local_only_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (logins.isEmpty()) {
                Text(
                    text = stringResource(R.string.passwords_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(logins, key = { it.id }) { login ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = login.username
                                        ?: stringResource(
                                            R.string.passwords_unreadable,
                                        ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    login.origin,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.delete(login.id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.action_remove),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
