package com.example.browser.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.browser.R
import com.example.browser.ui.components.AddressBar

/**
 * Main browser screen (ARCHITECTURE.md §6):
 * address bar on top, engine view in the middle, navigation bar at the bottom.
 */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    // System back navigates web history first; only when there is no page
    // to go back to does it fall through to leaving the app.
    BackHandler(enabled = state.canGoBack) {
        viewModel.onAction(BrowserAction.GoBack)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Keep the address bar and bottom bar clear of the status bar,
            // camera cutout and gesture/navigation bar (edge-to-edge).
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AddressBar(
            url = state.currentUrl,
            isSecure = state.isSecure,
            isLoading = state.isLoading,
            progress = state.progress,
            isBookmarked = state.isBookmarked,
            onSubmit = { viewModel.onAction(BrowserAction.LoadInput(it)) },
            onReloadOrStop = {
                viewModel.onAction(
                    if (state.isLoading) BrowserAction.StopLoading else BrowserAction.Reload,
                )
            },
            onToggleBookmark = { viewModel.onAction(BrowserAction.ToggleBookmark) },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val session = state.activeSession
            val activeTabId = state.activeTabId
            if (session != null && activeTabId != null) {
                // One native engine view per tab; switching tabs swaps it.
                key(activeTabId) {
                    AndroidView(
                        factory = { context -> session.createContentView(context) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (state.loadFailed && session != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.page_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { viewModel.onAction(BrowserAction.Reload) }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }

            val error = state.errorMessage
            if (error != null && state.tabs.isEmpty()) {
                Text(
                    text = error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }

        BrowserBottomBar(
            state = state,
            onAction = viewModel::onAction,
            onOpenTabs = onOpenTabs,
            onOpenBookmarks = onOpenBookmarks,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun BrowserBottomBar(
    state: BrowserUiState,
    onAction: (BrowserAction) -> Unit,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onAction(BrowserAction.GoBack) },
                enabled = state.canGoBack,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            IconButton(
                onClick = { onAction(BrowserAction.GoForward) },
                enabled = state.canGoForward,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.action_forward),
                )
            }
            IconButton(onClick = { onAction(BrowserAction.LoadHome) }) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = stringResource(R.string.action_home),
                )
            }

            // Tabs button with a Firefox-style tab counter.
            IconButton(onClick = onOpenTabs) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .border(
                            width = 1.8.dp,
                            color = LocalContentColor.current,
                            shape = RoundedCornerShape(7.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.tabs.size.toString(),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.action_menu),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_new_tab)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.NewTab)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_new_private_tab)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Lock, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.NewPrivateTab)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reload)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.Reload)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_bookmarks)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Bookmark, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenBookmarks()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_history)) },
                        leadingIcon = {
                            Icon(Icons.Filled.History, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenHistory()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_settings)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Settings, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenSettings()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_clear_data)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.ClearBrowsingData)
                        },
                    )
                }
            }
        }
    }
}
