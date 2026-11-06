package com.example.browser.ui.tabs

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch
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
    val recentlyClosedTabs by viewModel.recentlyClosedTabs.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val closedAllMessage = stringResource(R.string.tabs_closed_message)
    val undoLabel = stringResource(R.string.action_undo)
    // Re-evaluate inactivity when this screen is entered; the underlying
    // tab state may not have emitted since the four-hour boundary passed.
    val nowMs = remember { System.currentTimeMillis() }
    val tabsWithFreshInactiveState = state.tabs.map { tab ->
        tab.copy(
            isInactive = !tab.isActive && tab.lastAccessMs > 0L &&
                nowMs - tab.lastAccessMs > INACTIVE_TAB_AFTER_MS,
        )
    }
    val currentTabs = tabsWithFreshInactiveState.filterNot { it.isInactive }
    val inactiveTabs = tabsWithFreshInactiveState.filter { it.isInactive }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    if (state.tabs.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                viewModel.onAction(BrowserAction.CloseAllTabs)
                                // UndoCloseAllTabs and the batch it restores
                                // have existed in the ViewModel for several
                                // releases with nothing calling them; the
                                // close-all is the moment that batch is the
                                // only copy of those tabs, so say so here.
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = closedAllMessage,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.onAction(BrowserAction.UndoCloseAllTabs)
                                        onNavigateToBrowser()
                                    }
                                }
                            },
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
            if (state.tabs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Text(
                        text = stringResource(R.string.tabs_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.tabs_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (inactiveTabs.isNotEmpty() && currentTabs.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = stringResource(R.string.tabs_current),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                    items(currentTabs, key = { it.id }) { tab ->
                        TabTile(
                            tab = tab,
                            thumbnailVersion = state.thumbnailVersions[tab.id] ?: 0,
                            thumbnailStore = thumbnailStore,
                            onSelect = {
                                viewModel.onAction(BrowserAction.SwitchTab(tab.id))
                                onNavigateToBrowser()
                            },
                            onClose = {
                                viewModel.onAction(BrowserAction.CloseTab(tab.id))
                            },
                            onMove = { delta ->
                                viewModel.onAction(BrowserAction.MoveTab(tab.id, delta))
                            },
                        )
                    }
                    if (inactiveTabs.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = stringResource(R.string.tabs_inactive),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(inactiveTabs, key = { it.id }) { tab ->
                            TabTile(
                                tab = tab,
                                thumbnailVersion = state.thumbnailVersions[tab.id] ?: 0,
                                thumbnailStore = thumbnailStore,
                                onSelect = {
                                    viewModel.onAction(BrowserAction.SwitchTab(tab.id))
                                    onNavigateToBrowser()
                                },
                                onClose = {
                                    viewModel.onAction(BrowserAction.CloseTab(tab.id))
                                },
                                onMove = { delta ->
                                    viewModel.onAction(
                                        BrowserAction.MoveTab(tab.id, delta),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            if (recentlyClosedTabs.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.tabs_recently_closed),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            TextButton(
                                onClick = {
                                    viewModel.onAction(
                                        BrowserAction.ClearRecentlyClosedTabs,
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.action_clear))
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 176.dp),
                        ) {
                            items(
                                recentlyClosedTabs,
                                key = { it.id },
                            ) { closed ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = closed.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = closed.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color =
                                                MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            viewModel.onAction(
                                                BrowserAction.RestoreRecentlyClosedTab(
                                                    closed.id,
                                                ),
                                            )
                                            onNavigateToBrowser()
                                        },
                                    ) {
                                        Text(stringResource(R.string.action_restore))
                                    }
                                }
                            }
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
    onMove: (Int) -> Unit = {},
) {
    var accumulatedDrag by remember(tab.id) { mutableFloatStateOf(0f) }
    Card(
        modifier = Modifier.pointerInput(tab.id) {
            detectDragGesturesAfterLongPress(
                onDragStart = { accumulatedDrag = 0f },
                onDrag = { change, dragAmount ->
                    change.consume()
                    accumulatedDrag += dragAmount.x
                    val threshold = 72.dp.toPx()
                    while (accumulatedDrag > threshold) {
                        onMove(1)
                        accumulatedDrag -= threshold
                    }
                    while (accumulatedDrag < -threshold) {
                        onMove(-1)
                        accumulatedDrag += threshold
                    }
                },
                onDragEnd = { accumulatedDrag = 0f },
                onDragCancel = { accumulatedDrag = 0f },
            )
        },
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
                if (tab.isPrivate) {
                    PrivateTabPreview()
                } else {
                    TabThumbnail(tab.id, thumbnailVersion, thumbnailStore)
                }

                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.tab_drag_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(18.dp),
                )

                if (tab.isPrivate || tab.isSuspended || tab.isInactive) {
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
                        if (tab.isInactive) {
                            MiniBadge {
                                Text(
                                    text = stringResource(R.string.tab_inactive),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }

                // Keep the visible close affordance small and identical for
                // empty and loaded tabs. A plain Surface avoids Material's
                // minimum-interactive-size expansion, which could visually
                // enlarge/clip an IconButton over a thumbnail.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(36.dp)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(25.dp),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.tab_close),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (tab.isPrivate) {
                    // Private tabs never leak their title/URL into the grid
                    // (the preview is already a placeholder; the text must
                    // be one too).
                    Text(
                        text = stringResource(R.string.tab_private),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
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
}

@Composable
private fun MiniBadge(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        content()
    }
}

@Composable
private fun PrivateTabPreview() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

private const val INACTIVE_TAB_AFTER_MS = 4 * 60 * 60 * 1000L
