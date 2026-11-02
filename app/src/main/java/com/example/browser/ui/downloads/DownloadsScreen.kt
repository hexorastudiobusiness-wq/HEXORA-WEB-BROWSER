package com.example.browser.ui.downloads

import android.content.Intent
import androidx.compose.foundation.clickable
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.R
import com.example.browser.data.database.DownloadEntity

/**
 * "Recent downloads" page: lists transfers recorded by Hexora's download
 * layer. Tapping a completed item opens the file; the trailing button
 * removes the entry (file removal stays with the system's Downloads UI).
 * All transfer operations go through the ViewModel — this screen never
 * touches the download handler directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloads by viewModel.downloads.collectAsState()
    val progressById by viewModel.progress.collectAsState()
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        // Clearing the list also cancels running transfers, so it is not a
        // one-tap top-bar action.
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.downloads_clear_confirm_title)) },
            text = { Text(stringResource(R.string.downloads_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearAll()
                    },
                ) {
                    Text(stringResource(R.string.downloads_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

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
                        TextButton(onClick = { showClearConfirm = true }) {
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
                        progress = progressById[entry.downloadManagerId]
                            ?: DownloadProgressUi(),
                        onOpen = {
                            val uri = viewModel.completedUri(entry)
                            if (uri != null) {
                                val intent = Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, entry.mimeType ?: "*/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(intent) }
                            }
                        },
                        onDelete = { viewModel.delete(entry) },
                        onRetry = { viewModel.retry(entry) },
                        onPause = { viewModel.pause(entry) },
                        onResume = { viewModel.resume(entry) },
                        onCancel = { viewModel.cancel(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntity,
    progress: DownloadProgressUi,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val context = LocalContext.current
    val status = progress.status
    val statusText = stringResource(
        when (status) {
            DownloadStatus.RUNNING -> R.string.download_status_running
            DownloadStatus.PAUSED -> R.string.download_status_paused
            DownloadStatus.PENDING -> R.string.download_status_pending
            DownloadStatus.COMPLETED -> R.string.download_status_completed
            DownloadStatus.FAILED -> R.string.download_status_failed
            DownloadStatus.CANCELLED -> R.string.download_status_cancelled
            DownloadStatus.UNKNOWN -> R.string.download_status_unknown
        },
    )
    val detail = buildString {
        append(statusText)
        if (status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED) {
            val downloaded = Formatter.formatShortFileSize(
                context,
                progress.downloadedBytes.coerceAtLeast(0L),
            )
            if (progress.totalBytes > 0L) {
                val total = Formatter.formatShortFileSize(context, progress.totalBytes)
                val percent = ((progress.fraction ?: 0f) * 100f).toInt()
                append(" · ")
                append(downloaded)
                append(" / ")
                append(total)
                append(" · ")
                append(percent)
                append('%')
            } else if (progress.downloadedBytes > 0L) {
                append(" · ")
                append(downloaded)
            }
        }
    }
    ListItem(
        headlineContent = {
            Text(entry.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    detail,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status == DownloadStatus.RUNNING ||
                    status == DownloadStatus.PENDING ||
                    status == DownloadStatus.PAUSED
                ) {
                    val fraction = progress.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row {
                if (status == DownloadStatus.RUNNING ||
                    status == DownloadStatus.PENDING
                ) {
                    IconButton(onClick = onPause) {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = stringResource(R.string.action_pause),
                        )
                    }
                }
                if (status == DownloadStatus.PAUSED) {
                    IconButton(onClick = onResume) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.action_resume),
                        )
                    }
                }
                if (status == DownloadStatus.FAILED ||
                    status == DownloadStatus.CANCELLED ||
                    status == DownloadStatus.UNKNOWN
                ) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_retry),
                        )
                    }
                }
                if (status == DownloadStatus.RUNNING ||
                    status == DownloadStatus.PENDING ||
                    status == DownloadStatus.PAUSED
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_remove),
                    )
                }
            }
        },
        modifier = if (status == DownloadStatus.COMPLETED) {
            Modifier.clickable { onOpen() }
        } else {
            Modifier
        },
    )
}
