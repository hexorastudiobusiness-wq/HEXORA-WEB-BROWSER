package com.example.browser.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.R
import com.example.browser.browser.SitePermissionDecision
import com.example.browser.browser.SitePermissionEntry
import com.example.browser.browser.SitePermissionState
import com.example.browser.browser.SitePermissionKind
import com.example.browser.ui.browser.BrowserViewModel

/** Stored Gecko site permissions, grouped by origin and editable in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitePermissionsScreen(
    viewModel: BrowserViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var permissions by remember { mutableStateOf<List<SitePermissionEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    fun reload() {
        viewModel.loadSitePermissions { entries ->
            permissions = entries
            loaded = true
        }
    }

    LaunchedEffect(viewModel) { reload() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.site_permissions_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (loaded && permissions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.site_permissions_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.site_permissions_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(
                    permissions,
                    key = {
                        "${it.uri}|${it.kind}|${it.isPrivate}|${it.permissionType}"
                    },
                ) { entry ->
                    SitePermissionRow(
                        entry = entry,
                        onDecision = { decision ->
                            viewModel.setSitePermission(entry, decision)
                            permissions = if (decision == SitePermissionDecision.ASK) {
                                // Back to "ask every time": it no longer
                                // belongs in the stored-decisions list.
                                permissions.filterNot { it == entry }
                            } else {
                                permissions.map { current ->
                                    if (current == entry) {
                                        current.copy(
                                            state = when (decision) {
                                                SitePermissionDecision.ALLOW ->
                                                    SitePermissionState.ALLOW
                                                SitePermissionDecision.DENY ->
                                                    SitePermissionState.DENY
                                                SitePermissionDecision.ASK ->
                                                    SitePermissionState.PROMPT
                                            },
                                        )
                                    } else {
                                        current
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SitePermissionRow(
    entry: SitePermissionEntry,
    onDecision: (SitePermissionDecision) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = entry.uri,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = permissionLabel(entry.kind),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        when (entry.state) {
                            SitePermissionState.ALLOW ->
                                R.string.site_permission_allowed
                            SitePermissionState.DENY ->
                                R.string.site_permission_blocked
                            SitePermissionState.PROMPT ->
                                R.string.site_permission_ask
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onDecision(SitePermissionDecision.ASK) }) {
                        Text(stringResource(R.string.site_permission_ask))
                    }
                    TextButton(onClick = { onDecision(SitePermissionDecision.ALLOW) }) {
                        Text(stringResource(R.string.action_allow))
                    }
                    TextButton(onClick = { onDecision(SitePermissionDecision.DENY) }) {
                        Text(stringResource(R.string.action_block))
                    }
                }
            }
        },
    )
}

@Composable
private fun permissionLabel(kind: SitePermissionKind): String = stringResource(
    when (kind) {
        SitePermissionKind.GEOLOCATION -> R.string.permission_location
        SitePermissionKind.NOTIFICATIONS -> R.string.permission_notifications
        SitePermissionKind.CAMERA -> R.string.permission_camera
        SitePermissionKind.MICROPHONE -> R.string.permission_microphone
        SitePermissionKind.CAMERA_AND_MICROPHONE -> R.string.permission_camera_microphone
        SitePermissionKind.SCREEN_SHARE -> R.string.permission_screen_share
        SitePermissionKind.PERSISTENT_STORAGE -> R.string.permission_persistent_storage
        SitePermissionKind.AUTOPLAY -> R.string.permission_autoplay
        SitePermissionKind.MEDIA_KEY_SYSTEM -> R.string.permission_media_key_system
        SitePermissionKind.XR -> R.string.permission_xr
        SitePermissionKind.STORAGE_ACCESS -> R.string.permission_storage_access
        SitePermissionKind.LOCAL_NETWORK -> R.string.permission_local_network
        SitePermissionKind.UNKNOWN -> R.string.permission_unknown
    },
)
