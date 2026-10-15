package com.example.browser.ui.browser

import android.app.Activity
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.browser.R
import com.example.browser.ui.components.AddressBar
import com.example.browser.util.HomeScreenShortcut
import kotlin.math.roundToInt

/**
 * Main browser screen (ARCHITECTURE.md §6):
 * address bar on top, engine view in the middle, navigation bar at the bottom.
 * In fullscreen the chrome hides and only a small exit button remains.
 */
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // System back: exit fullscreen first, then navigate web history, and
    // only when neither applies does it leave the app.
    BackHandler(enabled = state.canGoBack) {
        viewModel.onAction(BrowserAction.GoBack)
    }
    BackHandler(enabled = state.isFullscreen) {
        viewModel.onAction(BrowserAction.ExitFullscreen)
    }

    ImmersiveSystemBars(enabled = state.isFullscreen)

    Column(
        modifier = modifier
            .fillMaxSize()
            // Keep chrome clear of the status bar, camera cutout and
            // gesture/navigation bar (edge-to-edge rendering).
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (!state.isFullscreen) {
            SwipeToSwitchTabBox(
                tabCount = state.tabs.size,
                onSwipe = { delta -> viewModel.onAction(BrowserAction.SwitchAdjacentTab(delta)) },
            ) {
                AddressBar(
                    url = state.currentUrl,
                    isSecure = state.isSecure,
                    isLoading = state.isLoading,
                    progress = state.progress,
                    isBookmarked = state.isBookmarked,
                    compact = landscape,
                    onSubmit = { viewModel.onAction(BrowserAction.LoadInput(it)) },
                    onReloadOrStop = {
                        viewModel.onAction(
                            if (state.isLoading) BrowserAction.StopLoading else BrowserAction.Reload,
                        )
                    },
                    onToggleBookmark = { viewModel.onAction(BrowserAction.ToggleBookmark) },
                )
            }

            if (state.findVisible) {
                FindBar(
                    result = state.findResult,
                    onQueryNext = { viewModel.onAction(BrowserAction.FindNext(it)) },
                    onPrevious = { query ->
                        viewModel.onAction(BrowserAction.FindPrevious(query))
                    },
                    onClose = { viewModel.onAction(BrowserAction.CloseFind) },
                )
            }
        }

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
                        factory = { ctx -> session.createContentView(ctx) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (state.loadFailed && session != null && !state.isFullscreen) {
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

            if (state.isFullscreen) {
                IconButton(
                    onClick = { viewModel.onAction(BrowserAction.ExitFullscreen) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.FullscreenExit,
                        contentDescription = stringResource(R.string.action_exit_fullscreen),
                    )
                }
            }
        }

        if (!state.isFullscreen) {
            BrowserBottomBar(
                state = state,
                onAction = viewModel::onAction,
                onOpenTabs = onOpenTabs,
                onOpenBookmarks = onOpenBookmarks,
                onOpenHistory = onOpenHistory,
                onOpenDownloads = onOpenDownloads,
                onOpenSettings = onOpenSettings,
                onAddToHomeScreen = {
                    val added = HomeScreenShortcut.add(context, state.pageTitle, state.currentUrl)
                    Toast.makeText(
                        context,
                        if (added) R.string.home_shortcut_added else R.string.home_shortcut_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onSiteDataCleared = {
                    Toast.makeText(context, R.string.site_data_cleared, Toast.LENGTH_SHORT)
                        .show()
                },
            )
        }
    }
}

/** Hides/shows the system bars while browser fullscreen is active. */
@Composable
private fun ImmersiveSystemBars(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            if (enabled) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                WindowInsetsControllerCompat(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/** Horizontal swipe on the wrapped chrome (address bar) switches tabs. */
@Composable
private fun SwipeToSwitchTabBox(
    tabCount: Int,
    onSwipe: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    var dragTotal by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier.pointerInput(tabCount) {
            detectHorizontalDragGestures(
                onDragStart = { dragTotal = 0f },
                onHorizontalDrag = { _, dragAmount -> dragTotal += dragAmount },
                onDragEnd = {
                    val threshold = 90.dp.toPx()
                    if (dragTotal <= -threshold) onSwipe(1)
                    else if (dragTotal >= threshold) onSwipe(-1)
                    dragTotal = 0f
                },
                onDragCancel = { dragTotal = 0f },
            )
        },
    ) {
        content()
    }
}

@Composable
private fun FindBar(
    result: com.example.browser.browser.FindResultUi,
    onQueryNext: (String) -> Unit,
    onPrevious: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Surface(shadowElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.find_hint)) },
                singleLine = true,
            )
            Text(
                text = when {
                    query.isEmpty() -> ""
                    result.found && result.total > 0 -> "${result.current}/${result.total}"
                    result.found -> "${result.current}"
                    else -> stringResource(R.string.find_not_found)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(
                onClick = { onPrevious(query) },
                enabled = query.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.action_find_previous),
                )
            }
            IconButton(
                onClick = { onQueryNext(query) },
                enabled = query.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.action_find_next),
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_close_find),
                )
            }
        }
    }
}

@Composable
private fun BrowserBottomBar(
    state: BrowserUiState,
    onAction: (BrowserAction) -> Unit,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onSiteDataCleared: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 2.dp),
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
            IconButton(
                onClick = {
                    onAction(
                        if (state.isLoading) BrowserAction.StopLoading else BrowserAction.Reload,
                    )
                },
            ) {
                Icon(
                    imageVector = if (state.isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = stringResource(
                        if (state.isLoading) R.string.action_stop else R.string.action_reload,
                    ),
                )
            }
            IconButton(onClick = { onAction(BrowserAction.LoadHome) }) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = stringResource(R.string.action_home),
                )
            }
            IconButton(onClick = { onAction(BrowserAction.ToggleFullscreen) }) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.menu_fullscreen),
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
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.NewTab)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_new_private_tab)) },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.NewPrivateTab)
                        },
                    )
                    HorizontalDivider()

                    // Text zoom (engine text scaling), 50%..200% like Chrome's menu.
                    var sliderValue by remember(state.textZoomPercent) {
                        mutableFloatStateOf(state.textZoomPercent.toFloat())
                    }
                    Column(
                        modifier = Modifier
                            .width(260.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.menu_zoom),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "${sliderValue.roundToInt()}%",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                onAction(BrowserAction.SetTextZoom(sliderValue.roundToInt()))
                            },
                            valueRange = 50f..200f,
                            steps = 14,
                        )
                    }
                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_desktop_site)) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (state.isDesktopMode) {
                                    Icons.Filled.DesktopWindows
                                } else {
                                    Icons.Filled.PhoneAndroid
                                },
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (state.isDesktopMode) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.ToggleDesktopMode)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_find_in_page)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.OpenFind)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_fullscreen)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Fullscreen, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.ToggleFullscreen)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_add_to_home)) },
                        leadingIcon = {
                            Icon(Icons.Filled.AddToHomeScreen, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onAddToHomeScreen()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_bookmarks)) },
                        leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenBookmarks()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_history)) },
                        leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenHistory()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_downloads)) },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenDownloads()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_settings)) },
                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenSettings()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_clear_site_data)) },
                        leadingIcon = { Icon(Icons.Filled.Cookie, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAction(BrowserAction.ClearSiteData)
                            onSiteDataCleared()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_clear_data)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
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
