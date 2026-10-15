package com.example.browser.ui.tabs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.R
import com.example.browser.ui.browser.BrowserAction
import com.example.browser.ui.browser.BrowserViewModel
import com.example.browser.ui.browser.TabUiItem
import com.example.browser.util.TabThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tab overview as a tile grid with page previews (ARCHITECTURE.md §5).
 * Suspended tabs (no live engine session) show a "Sleeping" badge; tapping
 * one restores it transparently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsScreen(
    viewModel: BrowserViewModel,
    thumbnailStore: TabThumbnailStore,
    onNavigateToBrowser: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tabs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.tabs.size > 1) {
                        TextButton(
                            onClick = { viewModel.onAction(BrowserAction.CloseAllTabs) },
                        ) {
                            Text(stringResource(R.string.tabs_close_all))
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.tabs, key = { it.id }) { tab ->
                    TabTile(
                        tab = tab,
                        thumbnailVersion = state.thumbnailVersions[tab.id] ?: 0,
                        thumbnailStore = thumbnailStore,
                        onSelect = {
                            viewModel.onAction(BrowserAction.SwitchTab(tab.id))
                            onNavigateToBrowser()
                        },
                        onClose = { viewModel.onAction(BrowserAction.CloseTab(tab.id)) },
                    )
                }
            }

            if (state.recentlyClosedCount > 0) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.tabs_closed_message),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = { viewModel.onAction(BrowserAction.UndoCloseAllTabs) },
                        ) {
                            Text(stringResource(R.string.action_undo))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        viewModel.onAction(BrowserAction.NewTab)
                        onNavigateToBrowser()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.tab_new))
                }
                OutlinedButton(
                    onClick = {
                        viewModel.onAction(BrowserAction.NewPrivateTab)
                        onNavigateToBrowser()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Text(stringResource(R.string.tab_private))
                }
            }
        }
    }
}

@Composable
private fun TabTile(
    tab: TabUiItem,
    thumbnailVersion: Int,
    thumbnailStore: TabThumbnailStore,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (tab.isActive) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (tab.isActive) {
            androidx.compose.foundation.BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.primary,
            )
        } else {
            null
        },
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                TabThumbnail(tab.id, thumbnailVersion, thumbnailStore)

                if (tab.isPrivate || tab.isSuspended) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (tab.isPrivate) {
                            MiniBadge {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = stringResource(R.string.tab_private),
                                    modifier = Modifier.padding(3.dp),
                                )
                            }
                        }
                        if (tab.isSuspended) {
                            MiniBadge {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Bedtime,
                                        contentDescription = null,
                                        modifier = Modifier.padding(start = 3.dp, top = 3.dp, bottom = 3.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.tab_sleeping),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(end = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.tab_close),
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = tab.title.ifBlank { stringResource(R.string.tab_new_page) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tab.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MiniBadge(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        content()
    }
}

@Composable
private fun TabThumbnail(tabId: String, version: Int, store: TabThumbnailStore) {
    val bitmap by produceState<Bitmap?>(initialValue = null, tabId, version) {
        value = withContext(Dispatchers.IO) { store.load(tabId) }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
