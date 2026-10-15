package com.example.browser.ui.downloads

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.R
import com.example.browser.data.database.DownloadEntity
import com.example.browser.download.DownloadHandler

/**
 * "Recent downloads" page: lists everything the browser handed to Android's
 * DownloadManager. Tapping a completed item opens the file; the trailing
 * button removes the entry (file removal stays with the system's Downloads UI).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    downloadHandler: DownloadHandler,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloads by viewModel.downloads.collectAsState()
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (downloads.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearAll() }) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Text(stringResource(R.string.downloads_clear_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (downloads.isEmpty()) {
            Column(modifier = Modifier.padding(padding).padding(24.dp)) {
                Text(
                    text = stringResource(R.string.downloads_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(downloads, key = { it.id }) { entry ->
                    DownloadRow(
                        entry = entry,
                        status = DownloadsViewModel.mapStatus(
                            remember(entry.downloadManagerId) {
                                downloadHandler.statusFor(entry.downloadManagerId)
                            },
                        ),
                        onOpen = {
                            val uri = downloadHandler.uriFor(entry.downloadManagerId)
                            if (uri != null) {
                                val intent = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, entry.mimeType ?: "*/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(intent) }
                            }
                        },
                        onDelete = { viewModel.delete(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntity,
    status: DownloadStatus,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusText = stringResource(
        when (status) {
            DownloadStatus.RUNNING -> R.string.download_status_running
            DownloadStatus.PAUSED -> R.string.download_status_paused
            DownloadStatus.PENDING -> R.string.download_status_pending
            DownloadStatus.COMPLETED -> R.string.download_status_completed
            DownloadStatus.FAILED -> R.string.download_status_failed
            DownloadStatus.UNKNOWN -> R.string.download_status_unknown
        },
    )
    ListItem(
        headlineContent = {
            Text(entry.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                "$statusText · ${entry.url}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_remove),
                )
            }
        },
        modifier = if (status == DownloadStatus.COMPLETED) {
            Modifier.clickable { onOpen() }
        } else {
            Modifier
        },
    )
}
