package com.example.browser.ui.browser

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imeAnimationTarget
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.browser.R
import com.example.browser.browser.PageMediaKind
import com.example.browser.ui.components.AddressBar
import com.example.browser.ui.components.ZoomSlider
import com.example.browser.util.HomeScreenShortcut
import kotlinx.coroutines.delay

/**
 * Main browser screen (ARCHITECTURE.md §6):
 * address bar on top, engine view in the middle, navigation bar at the bottom.
 * In fullscreen the chrome hides and only a small exit button remains.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearCacheAndExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var webContentView by remember { mutableStateOf<View?>(null) }

    // Content-level left/right tab swipes only exist in fullscreen (where
    // the chrome with its own swipe area is hidden).
    LaunchedEffect(state.isFullscreen) {
        viewModel.setTabSwipeEnabled(state.isFullscreen)
    }

    // System back: exit fullscreen first, then navigate web history, and
    // only when neither applies does it leave the app.
    BackHandler(enabled = state.canGoBack && !state.showStartPage) {
        viewModel.onAction(BrowserAction.GoBack)
    }
    BackHandler(enabled = state.isFullscreen) {
        viewModel.onAction(BrowserAction.ExitFullscreen)
    }

    BackHandler(enabled = state.showStartPage && state.hasWebPage) {
        viewModel.onAction(BrowserAction.HideStartPage)
    }

    // A tab created by a page (window.open / target=_blank) has no useful
    // history at its root. Hardware Back closes that tab and returns to the
    // tab that opened it instead of leaving the whole browser.
    BackHandler(
        enabled = !state.canGoBack && state.canBackCloseTab &&
            !state.showStartPage && !state.isFullscreen,
    ) {
        viewModel.onAction(BrowserAction.CloseTabAndReturnToOpener)
    }

    BackHandler(enabled = state.pageContextMenu != null) {
        viewModel.onAction(BrowserAction.DismissPageContextMenu)
    }

    ImmersiveSystemBars(enabled = state.isFullscreen)

    state.pendingCredentialSave?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.onAction(BrowserAction.DismissPendingCredential) },
            title = {
                Text(
                    stringResource(
                        if (prompt.isUpdate) {
                            R.string.password_update_title
                        } else {
                            R.string.password_save_title
                        },
                    ),
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (prompt.isUpdate) {
                                R.string.password_update_message
                            } else {
                                R.string.password_save_message
                            },
                            prompt.origin,
                        ),
                    )
                    Text(
                        text = stringResource(R.string.password_username, prompt.username),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onAction(BrowserAction.SavePendingCredential) },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onAction(BrowserAction.DismissPendingCredential) },
                ) {
                    Text(stringResource(R.string.action_not_now))
                }
            },
        )
    }

    state.pageContextMenu?.let { menu ->
        PageContextMenuSheet(
            menu = menu,
            isPrivate = state.isPrivate,
            onAction = viewModel::onAction,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Keep chrome clear of the status bar and side cutouts. The
            // bottom inset is consumed by the bottom bar itself so landscape
            // does not lose an extra strip below an already short viewport.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                ),
            )
            // The app is edge-to-edge, so on modern Android the window no
            // longer resizes by itself when the keyboard opens
            // (adjustResize is a no-op there), and GeckoView inside a
            // Compose AndroidView does not reliably receive IME insets.
            // Without this, the web view keeps its full height and a
            // page's bottom input (chat composers, comment boxes) stays
            // hidden under the keyboard and cannot be scrolled into view.
            //
            // Padding by the IME *animation target* instead of the live
            // IME inset keeps that behaviour but resizes the browser area
            // exactly once per keyboard toggle, at the target height,
            // instead of following every animation frame. The previous
            // live imePadding() resized the Gecko surface dozens of times
            // per toggle, which lagged on Samsung keyboards and glitched
            // the display on Xiaomi devices.
            .windowInsetsPadding(
                WindowInsets.imeAnimationTarget.only(WindowInsetsSides.Bottom),
            ),
    ) {
        // With every tab closed the Start Page stands alone: no address
        // bar (there is no tab to navigate). It comes back as soon as a
        // tab exists again.
        if (!state.isFullscreen && state.tabs.isNotEmpty()) {
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
                    onOpenDownloads = onOpenDownloads,
                    onToggleBookmark = { viewModel.onAction(BrowserAction.ToggleBookmark) },
                    suggestions = state.addressSuggestions,
                    onQueryChange = { viewModel.onAction(BrowserAction.AddressQueryChanged(it)) },
                    onSuggestionSelected = { viewModel.onAction(BrowserAction.OpenUrl(it)) },
                )
            }

            if (state.findVisible) {
                FindBar(
                    result = state.findResult,
                    pageKey = state.currentUrl,
                    isLoading = state.isLoading,
                    onSearch = { viewModel.onAction(BrowserAction.FindNext(it)) },
                    onQueryNext = { viewModel.onAction(BrowserAction.FindNext(it)) },
                    onPrevious = { query ->
                        viewModel.onAction(BrowserAction.FindPrevious(query))
                    },
                    onClear = { viewModel.onAction(BrowserAction.ClearFind) },
                    onClose = { viewModel.onAction(BrowserAction.CloseFind) },
                )
            }
        }

        BrowserPageContent(
            state = state,
            onAction = viewModel::onAction,
            onOpenUrl = { url -> viewModel.onAction(BrowserAction.OpenUrl(url)) },
            onNewTab = { viewModel.onAction(BrowserAction.NewTab) },
            onContentViewReady = { view -> webContentView = view },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        if (!state.isFullscreen) {
            BrowserBottomBar(
                state = state,
                compact = landscape,
                onAction = viewModel::onAction,
                onOpenTabs = onOpenTabs,
                onOpenBookmarks = onOpenBookmarks,
                onOpenHistory = onOpenHistory,
                onOpenDownloads = onOpenDownloads,
                onOpenSettings = onOpenSettings,
                onClearCacheAndExit = onClearCacheAndExit,
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

@Composable
private fun BrowserPageContent(
    state: BrowserUiState,
    onAction: (BrowserAction) -> Unit,
    onOpenUrl: (String) -> Unit,
    onNewTab: () -> Unit,
    onContentViewReady: (View) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // No on-screen fullscreen-exit button by design: leaving fullscreen
        // is the system Back button's job (handled in BrowserScreen), the
        // way video players behave. Left/right swipes over the content
        // switch tabs while staying in fullscreen; those are detected by
        // the engine's native content view, because touch events over the
        // web surface never reach Compose.

        if (state.showStartPage) {
            StartPage(
                state = state,
                onOpenUrl = onOpenUrl,
                onNewTab = onNewTab,
                onAddShortcut = { title, url ->
                    onAction(BrowserAction.AddStartShortcut(title, url))
                },
                onEditShortcut = { id, title, url ->
                    onAction(BrowserAction.EditStartShortcut(id, title, url))
                },
                onDeleteShortcut = { id ->
                    onAction(BrowserAction.DeleteStartShortcut(id))
                },
                modifier = Modifier.fillMaxSize(),
            )
            return
        }

        val session = state.activeSession
        if (session != null) {
            // One shared GeckoView stays attached while browsing. Tabs are
            // switched with renderContentView(), matching GeckoView's
            // intended session-swap model instead of swapping surfaces.
            AndroidView(
                factory = { ctx ->
                    session.createContentView(ctx).also(onContentViewReady)
                },
                update = { view ->
                    onContentViewReady(view)
                    session.renderContentView(view)
                },
                onRelease = { view -> session.releaseContentView(view) },
                modifier = Modifier.fillMaxSize(),
            )
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
                    Button(onClick = { onAction(BrowserAction.Reload) }) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageContextMenuSheet(
    menu: PageContextMenu,
    isPrivate: Boolean,
    onAction: (BrowserAction) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    fun dismiss() = onAction(BrowserAction.DismissPageContextMenu)

    fun copy(url: String) {
        clipboard.setText(AnnotatedString(url))
        dismiss()
    }

    fun share(url: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        runCatching {
            context.startActivity(Intent.createChooser(intent, null))
        }
        dismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            val heading = menu.title.ifBlank {
                menu.linkUri ?: menu.mediaUri.orEmpty()
            }
            if (heading.isNotBlank()) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            menu.linkUri?.let { link ->
                ContextMenuItem(stringResource(R.string.context_open_link)) {
                    onAction(BrowserAction.OpenContextMenuLink)
                }
                ContextMenuItem(stringResource(R.string.context_open_link_new_tab)) {
                    onAction(BrowserAction.OpenContextMenuLinkInNewTab)
                }
                if (!isPrivate) {
                    ContextMenuItem(stringResource(R.string.context_open_link_private_tab)) {
                        onAction(BrowserAction.OpenContextMenuLinkInPrivateTab)
                    }
                }
                ContextMenuItem(stringResource(R.string.context_copy_link)) {
                    copy(link)
                }
                ContextMenuItem(stringResource(R.string.context_share_link)) {
                    share(link)
                }
                if (link.startsWith("http://", true) ||
                    link.startsWith("https://", true)
                ) {
                    ContextMenuItem(stringResource(R.string.context_download_link)) {
                        onAction(BrowserAction.DownloadContextMenuLink)
                    }
                }
            }

            menu.mediaUri?.let { media ->
                val saveLabel = stringResource(
                    when (menu.mediaKind) {
                        PageMediaKind.IMAGE -> R.string.context_save_image
                        PageMediaKind.VIDEO -> R.string.context_save_video
                        PageMediaKind.AUDIO -> R.string.context_save_audio
                        PageMediaKind.NONE -> R.string.context_save_media
                    },
                )
                if (media.startsWith("http://", true) ||
                    media.startsWith("https://", true)
                ) {
                    ContextMenuItem(saveLabel) {
                        onAction(BrowserAction.DownloadContextMenuMedia)
                    }
                }
                ContextMenuItem(stringResource(R.string.context_open_media)) {
                    onAction(BrowserAction.OpenContextMenuMedia)
                }
                ContextMenuItem(stringResource(R.string.context_open_media_new_tab)) {
                    onAction(BrowserAction.OpenContextMenuMediaInNewTab)
                }
                ContextMenuItem(stringResource(R.string.context_copy_media_address)) {
                    copy(media)
                }
                ContextMenuItem(stringResource(R.string.context_share_media_address)) {
                    share(media)
                }
            }
        }
    }
}

@Composable
private fun ContextMenuItem(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 13.dp),
    )
}

@Composable
private fun StartPage(
    state: BrowserUiState,
    onOpenUrl: (String) -> Unit,
    onNewTab: () -> Unit,
    onAddShortcut: (String, String) -> Unit,
    onEditShortcut: (Long, String, String) -> Unit,
    onDeleteShortcut: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var addDialogVisible by remember { mutableStateOf(false) }
    var shortcutPendingEdit by remember { mutableStateOf<StartShortcut?>(null) }

    if (addDialogVisible) {
        ShortcutDialog(
            title = stringResource(R.string.start_shortcut_add_title),
            initialName = "",
            initialAddress = "",
            onDismiss = { addDialogVisible = false },
            onSave = { title, url ->
                onAddShortcut(title, url)
                addDialogVisible = false
            },
        )
    }

    shortcutPendingEdit?.let { shortcut ->
        ShortcutDialog(
            title = stringResource(R.string.start_shortcut_edit_title),
            initialName = shortcut.title,
            initialAddress = shortcut.url,
            onDismiss = { shortcutPendingEdit = null },
            onSave = { title, url ->
                onEditShortcut(shortcut.id, title, url)
                shortcutPendingEdit = null
            },
        )
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 36.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = Modifier.size(72.dp)) {
                    Image(
                        painter = painterResource(R.drawable.hexora_logo),
                        contentDescription = stringResource(
                            R.string.start_logo_content_description,
                        ),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(18.dp)),
                    )
                    if (state.isPrivate) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.BottomEnd),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.start_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    text = stringResource(R.string.start_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.tabs.isEmpty()) {
                    // Every tab is closed and there is no address bar on
                    // this page: one small "+" opens a fresh tab.
                    FilledIconButton(
                        onClick = onNewTab,
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.tab_new),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.tab_new),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.address_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        if (state.isPrivate) {
            item {
                Text(
                    text = stringResource(R.string.start_shortcuts_private),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.start_shortcuts),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(
                            R.string.start_shortcuts_count,
                            state.startShortcuts.size,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.startShortcuts.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.start_shortcut_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val cells: List<StartShortcut?> = state.startShortcuts +
                if (state.canAddStartShortcut) listOf(null) else emptyList()
            items(cells.chunked(4)) { rowShortcuts ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowShortcuts.forEach { shortcut ->
                        if (shortcut == null) {
                            AddShortcutTile(
                                onClick = { addDialogVisible = true },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            StartShortcutTile(
                                shortcut = shortcut,
                                icon = state.startShortcutIcons[shortcut.id],
                                onClick = { onOpenUrl(shortcut.url) },
                                onEdit = { shortcutPendingEdit = shortcut },
                                onDelete = { onDeleteShortcut(shortcut.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(4 - rowShortcuts.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }

            if (state.startShortcuts.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.start_shortcut_remove_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Box(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShortcutDialog(
    title: String,
    initialName: String,
    initialAddress: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var address by remember(initialAddress) { mutableStateOf(initialAddress) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.start_shortcut_name)) },
                    placeholder = {
                        Text(stringResource(R.string.start_shortcut_name_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.start_shortcut_address)) },
                    placeholder = {
                        Text(stringResource(R.string.start_shortcut_address_hint))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, address) },
                enabled = address.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StartShortcutTile(
    shortcut: StartShortcut,
    icon: android.graphics.Bitmap?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val host = remember(shortcut.url) {
        runCatching { Uri.parse(shortcut.url).host }
            .getOrNull()
            ?.removePrefix("www.")
            ?.ifBlank { shortcut.url }
            ?: shortcut.url
    }
    val label = shortcut.title.ifBlank { host }
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true },
                ),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (icon != null) {
                            Image(
                                bitmap = icon.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface),
                            )
                        } else {
                            Text(
                                text = label.firstOrNull()?.uppercase() ?: "?",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Text(
                    text = host,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.start_shortcut_edit)) },
                leadingIcon = {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                },
                onClick = {
                    menuExpanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.start_shortcut_delete)) },
                leadingIcon = {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun AddShortcutTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(108.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.5.dp,
            MaterialTheme.colorScheme.primary,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.start_add_shortcut),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.start_add_shortcut),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
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
    pageKey: String,
    isLoading: Boolean,
    onSearch: (String) -> Unit,
    onQueryNext: (String) -> Unit,
    onPrevious: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    // Search as the user types. Until Gecko answers for this exact query,
    // the status stays blank instead of incorrectly showing "No results".
    LaunchedEffect(query, pageKey, isLoading) {
        if (query.isBlank()) {
            onClear()
        } else if (!isLoading) {
            delay(180)
            onSearch(query)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { if (query.isNotBlank()) onQueryNext(query) },
                ),
            )
            Text(
                text = when {
                    query.isEmpty() -> ""
                    !result.completed || result.query != query -> ""
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
    compact: Boolean = false,
    onAction: (BrowserAction) -> Unit,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearCacheAndExit: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onSiteDataCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Nine actions share this bar; keep every button on narrow phones.
    val buttonSize = if (compact) 36.dp else 38.dp

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                )
                .padding(horizontal = 2.dp, vertical = if (compact) 0.dp else 2.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(modifier = Modifier.size(buttonSize),
                onClick = { onAction(BrowserAction.GoBack) },
                enabled = state.canGoBack,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            IconButton(modifier = Modifier.size(buttonSize),
                onClick = { onAction(BrowserAction.GoForward) },
                enabled = state.canGoForward,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.action_forward),
                )
            }
            IconButton(modifier = Modifier.size(buttonSize),
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
            IconButton(modifier = Modifier.size(buttonSize), onClick = { onAction(BrowserAction.LoadHome) }) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = stringResource(R.string.action_home),
                )
            }
            IconButton(modifier = Modifier.size(buttonSize), onClick = { onAction(BrowserAction.ToggleFullscreen) }) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = stringResource(R.string.menu_fullscreen),
                )
            }

            // Tabs button with a Firefox-style tab counter.
            IconButton(modifier = Modifier.size(buttonSize), onClick = onOpenTabs) {
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

            IconButton(modifier = Modifier.size(buttonSize), onClick = { onAction(BrowserAction.NewTab) }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.tab_new),
                )
            }

            // Quick tab cycling: each tap jumps to the next tab and wraps
            // from the last tab back to the first.
            IconButton(
                modifier = Modifier.size(buttonSize),
                onClick = { onAction(BrowserAction.CycleNextTab) },
                enabled = state.tabs.size > 1,
            ) {
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = stringResource(R.string.action_cycle_next_tab),
                )
            }

            Box {
                IconButton(modifier = Modifier.size(buttonSize), onClick = { menuExpanded = true }) {
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
                    Column(
                        modifier = Modifier
                            .width(272.dp)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.menu_zoom),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ZoomSlider(
                            percent = state.textZoomPercent,
                            onPercentCommitted = { percent ->
                                onAction(BrowserAction.SetTextZoom(percent))
                            },
                            modifier = Modifier.fillMaxWidth(),
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_clear_cache_and_close)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onClearCacheAndExit()
                        },
                    )
                }
            }
        }
    }
}
