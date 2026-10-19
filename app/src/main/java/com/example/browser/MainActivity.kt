package com.example.browser

import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.browser.ui.bookmarks.BookmarksScreen
import com.example.browser.ui.bookmarks.BookmarksViewModel
import com.example.browser.ui.browser.BrowserScreen
import com.example.browser.ui.browser.BrowserViewModel
import com.example.browser.ui.downloads.DownloadsScreen
import com.example.browser.ui.downloads.DownloadsViewModel
import com.example.browser.ui.history.HistoryScreen
import com.example.browser.ui.history.HistoryViewModel
import com.example.browser.ui.passwords.PasswordsScreen
import com.example.browser.ui.passwords.PasswordsViewModel
import com.example.browser.ui.settings.SettingsScreen
import com.example.browser.ui.settings.SettingsViewModel
import com.example.browser.ui.tabs.TabsScreen
import com.example.browser.ui.theme.HexoraBrowserTheme
import com.example.browser.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object Routes {
    const val BROWSER = "browser"
    const val TABS = "tabs"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val PASSWORDS = "passwords"
}

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as BrowserApplication).container

    private val browserViewModel: BrowserViewModel by viewModels {
        viewModelFactory {
            initializer {
                BrowserViewModel(
                    engineFactory = { javaScriptEnabled ->
                        container.createBrowserEngine(javaScriptEnabled)
                    },
                    historyRepository = container.historyRepository,
                    bookmarkRepository = container.bookmarkRepository,
                    credentialRepository = container.credentialRepository,
                    shortcutRepository = container.startShortcutRepository,
                    shortcutIconStore = container.shortcutIconStore,
                    faviconFetcher = container.faviconFetcher,
                    settingsRepository = container.settingsRepository,
                    thumbnailStore = container.tabThumbnailStore,
                    tabSessionStore = container.tabSessionStore,
                )
            }
        }
    }

    private var pendingFilePickerRequest: BrowserViewModel.FilePickerRequest? = null
    private var clearingCacheAndExiting: Boolean = false
    private var fileSourcePickerRequest
        by mutableStateOf<BrowserViewModel.FilePickerRequest?>(null)

    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> finishFilePicker(listOfNotNull(uri)) }

    private val multipleDocumentPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> finishFilePicker(uris) }

    private val visualMediaPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> finishFilePicker(listOfNotNull(uri)) }

    private val multipleVisualMediaPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> finishFilePicker(uris) }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> finishFilePicker(listOfNotNull(uri)) }

    private val bookmarksViewModel: BookmarksViewModel by viewModels {
        viewModelFactory {
            initializer { BookmarksViewModel(container.bookmarkRepository) }
        }
    }

    private val historyViewModel: HistoryViewModel by viewModels {
        viewModelFactory {
            initializer { HistoryViewModel(container.historyRepository) }
        }
    }

    private val downloadsViewModel: DownloadsViewModel by viewModels {
        viewModelFactory {
            initializer {
                DownloadsViewModel(
                    repository = container.downloadRepository,
                    downloadHandler = container.downloadHandler,
                )
            }
        }
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        viewModelFactory {
            initializer { SettingsViewModel(container.settingsRepository) }
        }
    }

    private val passwordsViewModel: PasswordsViewModel by viewModels {
        viewModelFactory {
            initializer { PasswordsViewModel(container.credentialRepository) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind system bars; content keeps clear via WindowInsets
        // padding in the screens (see BrowserScreen).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        handleViewIntent(intent)
        collectDownloadRequests()
        collectFilePickerRequests()

        setContent {
            val settings by browserViewModel.settings.collectAsState()
            HexoraBrowserTheme(themeMode = settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HexoraNavHost(
                        browserViewModel = browserViewModel,
                        bookmarksViewModel = bookmarksViewModel,
                        historyViewModel = historyViewModel,
                        downloadsViewModel = downloadsViewModel,
                        settingsViewModel = settingsViewModel,
                        passwordsViewModel = passwordsViewModel,
                        thumbnailStore = container.tabThumbnailStore,
                        downloadHandler = container.downloadHandler,
                        onClearCacheAndExit = { clearCacheAndExit() },
                    )
                    fileSourcePickerRequest?.let { request ->
                        FileSourcePickerSheet(
                            onGallery = {
                                fileSourcePickerRequest = null
                                launchVisualMediaPicker(request)
                            },
                            onFiles = {
                                fileSourcePickerRequest = null
                                launchDocumentPicker(request)
                            },
                            onDismiss = { cancelPendingFilePicker() },
                        )
                    }
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only genuine low-memory signals may suspend cold tab sessions.
        // This must be an exact-level check, mirroring Mozilla's
        // TrimMemoryMiddleware: a numeric `>=` comparison also matches
        // TRIM_MEMORY_UI_HIDDEN (20), which Android delivers the moment the
        // app is minimised — that comparison is what used to close every
        // inactive tab (and reload it on return) after a few seconds in
        // the background. UI_HIDDEN / BACKGROUND / MODERATE must never
        // suspend anything.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            browserViewModel.suspendTabsOutsideWarmSet()
        }
    }

    override fun onStart() {
        super.onStart()
        // Returning to the foreground: recreate any tab session whose
        // content process died while backgrounded, so the visible tab
        // repaints its page instead of coming back empty.
        browserViewModel.onAppForegrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** Opens http(s) links handed to us by other apps in a fresh tab. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString
                ?.takeIf { UrlUtils.isHttpUrl(it) }
                ?.let { browserViewModel.handleExternalUrl(it) }
        }
    }

    /**
     * "Clear cache & close" from the browser menu: clears only caches (not
     * history/bookmarks/passwords), removes Android cache-directory files,
     * then removes the task so the next launch starts cold.
     */
    private fun clearCacheAndExit() {
        if (clearingCacheAndExiting) return
        clearingCacheAndExiting = true
        var finished = false

        fun finishOnce() {
            if (finished) return
            finished = true
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    // Gecko's HTTP/image caches are cleared by the engine via
                    // StorageController (ALL_CACHES) before this runs. Here we
                    // only drop Hexora's own temporary cache files. codeCacheDir
                    // is intentionally left alone: the system manages it and
                    // deleting it under a still-running process buys nothing.
                    listOf(cacheDir).forEach { dir ->
                        dir.listFiles()?.forEach { it.deleteRecursively() }
                    }
                    externalCacheDirs.filterNotNull().forEach { dir ->
                        dir.listFiles()?.forEach { it.deleteRecursively() }
                    }
                }
                finishAndRemoveTask()
            }
        }

        browserViewModel.clearCache {
            runOnUiThread { finishOnce() }
        }
        // Never trap the user in the app if Gecko does not answer.
        lifecycleScope.launch {
            delay(2_500)
            finishOnce()
        }
    }

    /** Opens the system picker when a web page asks for an upload file. */
    private fun collectFilePickerRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                browserViewModel.filePickerRequests.collect { request ->
                    if (pendingFilePickerRequest != null) {
                        browserViewModel.cancelFilePicker(request.requestId)
                        return@collect
                    }
                    pendingFilePickerRequest = request
                    when {
                        request.isFolder -> runCatching {
                            folderPicker.launch(null)
                        }.onFailure { cancelPendingFilePicker() }

                        supportsVisualMedia(request) ->
                            fileSourcePickerRequest = request

                        else -> launchDocumentPicker(request)
                    }
                }
            }
        }
    }

    private fun supportsVisualMedia(
        request: BrowserViewModel.FilePickerRequest,
    ): Boolean = pickerMimeTypes(request.mimeTypes).any { type ->
        type == "*/*" || type.startsWith("image/") || type.startsWith("video/")
    }

    private fun launchDocumentPicker(request: BrowserViewModel.FilePickerRequest) {
        runCatching {
            val mimeTypes = pickerMimeTypes(request.mimeTypes)
            if (request.allowMultiple) {
                multipleDocumentPicker.launch(mimeTypes)
            } else {
                documentPicker.launch(mimeTypes)
            }
        }.onFailure { cancelPendingFilePicker() }
    }

    private fun launchVisualMediaPicker(
        request: BrowserViewModel.FilePickerRequest,
    ) {
        runCatching {
            val mimeTypes = pickerMimeTypes(request.mimeTypes)
            val mediaType = when {
                mimeTypes.all { it.startsWith("video/") } ->
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        .build()

                mimeTypes.all { it.startsWith("image/") } ->
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        .build()

                else -> PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    .build()
            }
            if (request.allowMultiple) {
                multipleVisualMediaPicker.launch(mediaType)
            } else {
                visualMediaPicker.launch(mediaType)
            }
        }.onFailure { cancelPendingFilePicker() }
    }

    private fun cancelPendingFilePicker() {
        val request = pendingFilePickerRequest ?: return
        pendingFilePickerRequest = null
        fileSourcePickerRequest = null
        browserViewModel.cancelFilePicker(request.requestId)
    }

    private fun finishFilePicker(uris: List<Uri>) {
        val request = pendingFilePickerRequest ?: return
        pendingFilePickerRequest = null
        fileSourcePickerRequest = null
        val requestId = request.requestId
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        browserViewModel.completeFilePicker(requestId, uris)
        // Some devices return from the picker with a dead Gecko surface
        // (alive session, black page). Ask the UI to rebind the display.
    }

    /** Gecko accept values may be MIME types or file extensions. */
    private fun pickerMimeTypes(raw: List<String>): Array<String> {
        val converted = raw.mapNotNull { value ->
            val normalized = value.trim().lowercase()
            when {
                normalized.isBlank() -> null
                normalized.startsWith(".") -> MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(normalized.removePrefix("."))
                "/" in normalized -> normalized
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(normalized)
            }
        }.distinct()
        return converted.ifEmpty { listOf("*/*") }.toTypedArray()
    }

    /** Routes engine download requests to the Android DownloadManager layer. */
    private fun collectDownloadRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                browserViewModel.downloadEvents.collect { event ->
                    val bodySource = event.bodySource
                    val downloadId = if (bodySource != null) {
                        // The engine already received this response; consume
                        // its body. A fresh GET could not reproduce blob,
                        // POST or authenticated downloads.
                        container.downloadHandler.downloadStream(
                            bodySource = bodySource,
                            fileName = event.fileName,
                            mimeType = event.mimeType,
                            contentLength = event.contentLength,
                        )
                    } else {
                        container.downloadHandler.download(
                            url = event.url,
                            fileName = event.fileName,
                            mimeType = event.mimeType,
                            pageUrl = event.pageUrl,
                        )
                    }
                    val started = downloadId >= 0
                    if (started && !event.isPrivate) {
                        // Private-tab downloads still produce the file the
                        // user asked for, but their URL is not persisted in
                        // the browser's own download history.
                        launch {
                            container.downloadRepository.record(
                                downloadManagerId = downloadId,
                                url = event.url,
                                fileName = event.fileName,
                                mimeType = event.mimeType,
                            )
                        }
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (started) {
                            getString(R.string.download_started, event.fileName)
                        } else {
                            getString(R.string.download_failed)
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSourcePickerSheet(
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.file_source_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            TextButton(onClick = onGallery, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(R.string.file_source_gallery))
            }
            TextButton(onClick = onFiles, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(R.string.file_source_files))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(androidx.compose.ui.res.stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun HexoraNavHost(
    browserViewModel: BrowserViewModel,
    bookmarksViewModel: BookmarksViewModel,
    historyViewModel: HistoryViewModel,
    downloadsViewModel: DownloadsViewModel,
    settingsViewModel: SettingsViewModel,
    passwordsViewModel: PasswordsViewModel,
    thumbnailStore: com.example.browser.util.TabThumbnailStore,
    downloadHandler: com.example.browser.download.DownloadHandler,
    onClearCacheAndExit: () -> Unit,
) {
    val navController = rememberNavController()

    fun openInBrowser(url: String) {
        browserViewModel.openUrl(url)
        navController.navigateToBrowser()
    }

    NavHost(
        navController = navController,
        startDestination = Routes.BROWSER,
        // The browser swaps a native Gecko surface between tabs. Cross-fading
        // or sliding that surface makes switching feel heavy and can expose
        // an extra compositor frame, so screen changes are instant here.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                viewModel = browserViewModel,
                onOpenTabs = { navController.navigate(Routes.TABS) },
                onOpenBookmarks = { navController.navigate(Routes.BOOKMARKS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onClearCacheAndExit = onClearCacheAndExit,
            )
        }
        composable(Routes.TABS) {
            TabsScreen(
                thumbnailStore = thumbnailStore,
                viewModel = browserViewModel,
                onNavigateToBrowser = { navController.navigateToBrowser() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.BOOKMARKS) {
            BookmarksScreen(
                viewModel = bookmarksViewModel,
                onOpenUrl = { url -> openInBrowser(url) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(
                viewModel = downloadsViewModel,
                downloadHandler = downloadHandler,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                viewModel = historyViewModel,
                onOpenUrl = { url -> openInBrowser(url) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PASSWORDS) {
            PasswordsScreen(
                viewModel = passwordsViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onClearBrowsingData = { browserViewModel.clearBrowsingData() },
                onOpenPasswords = { navController.navigate(Routes.PASSWORDS) },
            )
        }
    }
}

private fun NavController.navigateToBrowser() {
    navigate(Routes.BROWSER) {
        popUpTo(Routes.BROWSER) { inclusive = false }
        launchSingleTop = true
    }
}

/** Settings-screen entry point for clearing data (reuses the browser action). */
private fun BrowserViewModel.clearBrowsingDataFromSettings() {
    onAction(com.example.browser.ui.browser.BrowserAction.ClearBrowsingData)
}
