package com.example.browser

import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch

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
                )
            }
        }
    }

    private var pendingFilePickerRequestId: String? = null
    private var pendingFilePickerMultiple: Boolean = false

    // ACTION_GET_CONTENT (wrapped in a chooser below) lets the user pick
    // Files, Gallery/Photos, Drive or another installed picker app instead
    // of always being forced into the system file manager.
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> finishFilePicker(extractPickedUris(result.data)) }

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
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Gecko sessions are active only while the window is in front. This
        // also gives Gecko a clean stop/start edge when returning from an
        // external picker instead of reviving a torn-down surface.
        browserViewModel.setWindowActive(true)
    }

    override fun onStop() {
        browserViewModel.setWindowActive(false)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Memory pressure: release every inactive tab's engine session.
        // Tabs keep their saved state + thumbnail and restore on selection.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            browserViewModel.suspendAllInactiveTabs()
        }
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

    /** Opens the system picker when a web page asks for an upload file. */
    private fun collectFilePickerRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                browserViewModel.filePickerRequests.collect { request ->
                    if (pendingFilePickerRequestId != null) {
                        browserViewModel.cancelFilePicker(request.requestId)
                        return@collect
                    }
                    pendingFilePickerRequestId = request.requestId
                    pendingFilePickerMultiple = request.allowMultiple
                    runCatching {
                        if (request.isFolder) {
                            folderPicker.launch(null)
                        } else {
                            filePicker.launch(buildFileChooserIntent(request))
                        }
                    }.onFailure {
                        pendingFilePickerRequestId = null
                        pendingFilePickerMultiple = false
                        browserViewModel.cancelFilePicker(request.requestId)
                    }
                }
            }
        }
    }

    private fun buildFileChooserIntent(
        request: BrowserViewModel.FilePickerRequest,
    ): Intent {
        val mimeTypes = pickerMimeTypes(request.mimeTypes)
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeTypes.first())
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.allowMultiple)
        if (mimeTypes.size > 1) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        return Intent.createChooser(intent, getString(R.string.file_picker_title))
    }

    private fun extractPickedUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let { uris += it }
            }
        }
        if (uris.isEmpty()) data.data?.let { uris += it }
        return if (pendingFilePickerMultiple) uris else uris.take(1)
    }

    private fun finishFilePicker(uris: List<Uri>) {
        val requestId = pendingFilePickerRequestId ?: return
        pendingFilePickerRequestId = null
        pendingFilePickerMultiple = false
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
        browserViewModel.requestContentRefresh()
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
                    val downloadId = container.downloadHandler.download(
                        url = event.url,
                        fileName = event.fileName,
                        mimeType = event.mimeType,
                        pageUrl = event.pageUrl,
                    )
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
