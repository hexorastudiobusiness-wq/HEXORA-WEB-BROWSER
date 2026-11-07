package com.example.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.app.KeyguardManager
import android.app.SearchManager
import android.hardware.biometrics.BiometricPrompt
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.browser.data.database.DownloadEntity
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
import com.example.browser.ui.settings.AboutScreen
import com.example.browser.ui.settings.LicencesScreen
import com.example.browser.ui.settings.SettingsScreen
import com.example.browser.ui.settings.SettingsViewModel
import com.example.browser.ui.settings.SitePermissionsScreen
import com.example.browser.ui.tabs.TabsScreen
import com.example.browser.ui.theme.HexoraBrowserTheme
import com.example.browser.util.AppLog
import com.example.browser.util.BrowserRole
import com.example.browser.util.BugReport
import com.example.browser.util.DeviceInfo
import com.example.browser.util.ShakeDetector
import com.example.browser.util.UrlUtils
import com.example.browser.ui.components.BugReportDialog
import com.example.browser.ui.components.DefaultBrowserDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object Routes {
    const val BROWSER = "browser"
    const val TABS = "tabs"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val SITE_PERMISSIONS = "site_permissions"
    const val PASSWORDS = "passwords"
    const val ABOUT = "about"
    const val LICENCES = "licences"
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
                    sitePreferencesStore = container.sitePreferencesStore,
                )
            }
        }
    }

    private var pendingFilePickerRequest: BrowserViewModel.FilePickerRequest? = null

    /** Set by the shake gesture (or the About screen) to open the report. */
    private val bugReportRequested = MutableStateFlow(false)

    /** First-launch offer; set once, before the first frame. */
    private val askDefaultBrowser = MutableStateFlow(false)

    /** Shown when the system refused its own role dialog. */
    private val defaultBrowserFallback = MutableStateFlow(false)

    /** Null when the system cannot offer the role dialog at all. */
    private var pendingRoleIntent: Intent? = null

    private val shakeDetector = ShakeDetector {
        AppLog.event("shake: preparing bug report")
        bugReportRequested.value = true
    }

    /**
     * The role dialog's outcome. A request the system declines (already denied
     * twice, or the skin hides it) must lead somewhere: the owner is offered the
     * settings route instead of being left with nothing.
     */
    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (!BrowserRole.isDefault(this)) {
            defaultBrowserFallback.value = true
        }
    }

    /** Last external http(s) URI this Activity instance chain consumed. */
    private var lastHandledViewUri: String? = null
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

    private var pendingCameraPhotoFile: File? = null
    private var pendingCameraVideoFile: File? = null
    private var pendingPasswordUnlockCallback: (() -> Unit)? = null
    private var requestedDownloadNotificationPermission: Boolean = false

    private val passwordUnlockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingPasswordUnlockCallback
        pendingPasswordUnlockCallback = null
        if (result.resultCode == RESULT_OK) callback?.invoke()
    }

    private val downloadNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            cancelPendingFilePicker()
        }
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val photoFile = pendingCameraPhotoFile
        pendingCameraPhotoFile = null
        if (success && photoFile?.exists() == true) {
            // Content URI: the engine reads the capture in another process,
            // where a raw file:// URI would be unusable.
            finishFilePicker(
                listOf(
                    FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
                        photoFile,
                    ),
                ),
            )
        } else {
            photoFile?.delete()
            cancelPendingFilePicker()
        }
    }

    private val cameraVideoLauncher = registerForActivityResult(
        ActivityResultContracts.CaptureVideo(),
    ) { success ->
        val videoFile = pendingCameraVideoFile
        pendingCameraVideoFile = null
        if (success && videoFile?.exists() == true) {
            finishFilePicker(
                listOf(
                    FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
                        videoFile,
                    ),
                ),
            )
        } else {
            videoFile?.delete()
            cancelPendingFilePicker()
        }
    }

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

    private companion object {
        const val KEY_PENDING_FILE_REQUEST_ID = "pending_file_request_id"
        const val KEY_PENDING_CAMERA_PHOTO = "pending_camera_photo"
        const val KEY_PENDING_CAMERA_VIDEO = "pending_camera_video"
        const val KEY_SHEET_REQUEST_ID = "file_source_sheet_request_id"
        const val KEY_SHEET_ALLOW_MULTIPLE = "file_source_sheet_allow_multiple"
        const val KEY_SHEET_MIME_TYPES = "file_source_sheet_mime_types"
        const val KEY_SHEET_CAPTURE_MODE = "file_source_sheet_capture_mode"
        const val KEY_LAST_HANDLED_VIEW_URI = "last_handled_view_uri"
        const val KEY_PASSWORD_UNLOCK_PENDING = "password_unlock_pending"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // A picker/camera activity can outlive this Activity instance
        // (rotation, or the system recreating us under memory pressure).
        // The result callback then runs on the *new* instance; without the
        // pending request id and capture paths saved here it would find
        // nothing pending, drop the user's selection on the floor and
        // leave the page's Gecko file prompt hanging forever.
        outState.putString(
            KEY_PENDING_FILE_REQUEST_ID,
            pendingFilePickerRequest?.requestId,
        )
        outState.putString(KEY_PENDING_CAMERA_PHOTO, pendingCameraPhotoFile?.absolutePath)
        outState.putString(KEY_PENDING_CAMERA_VIDEO, pendingCameraVideoFile?.absolutePath)
        // The Gallery/Files/Camera chooser sheet is plain Compose state;
        // save enough of its request to re-show it after recreation
        // instead of stranding the page's pending file prompt.
        outState.putString(KEY_SHEET_REQUEST_ID, fileSourcePickerRequest?.requestId)
        outState.putBoolean(
            KEY_SHEET_ALLOW_MULTIPLE,
            fileSourcePickerRequest?.allowMultiple ?: false,
        )
        outState.putStringArrayList(
            KEY_SHEET_MIME_TYPES,
            ArrayList(fileSourcePickerRequest?.mimeTypes.orEmpty()),
        )
        outState.putInt(
            KEY_SHEET_CAPTURE_MODE,
            fileSourcePickerRequest?.captureMode ?: 0,
        )
        outState.putString(KEY_LAST_HANDLED_VIEW_URI, lastHandledViewUri)
        // The Keyguard prompt (API 26..29) is a separate Activity; if this
        // Activity is recreated while it is up, the in-memory callback
        // below would be gone and a successful verification would unlock
        // nothing. A pending flag is enough to rebuild it on restore.
        outState.putBoolean(
            KEY_PASSWORD_UNLOCK_PENDING,
            pendingPasswordUnlockCallback != null,
        )
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind system bars; content keeps clear via WindowInsets
        // padding in the screens (see BrowserScreen).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        savedInstanceState?.let { state ->
            state.getString(KEY_PENDING_CAMERA_PHOTO)?.let { path ->
                pendingCameraPhotoFile = File(path)
            }
            state.getString(KEY_PENDING_CAMERA_VIDEO)?.let { path ->
                pendingCameraVideoFile = File(path)
            }
            state.getString(KEY_PENDING_FILE_REQUEST_ID)?.let { requestId ->
                // Only the id is needed to deliver or cancel the result;
                // the ViewModel still owns the full pending request.
                pendingFilePickerRequest = BrowserViewModel.FilePickerRequest(
                    requestId = requestId,
                    allowMultiple = false,
                    isFolder = false,
                    mimeTypes = emptyList(),
                )
            }
            state.getString(KEY_SHEET_REQUEST_ID)?.let { requestId ->
                fileSourcePickerRequest = BrowserViewModel.FilePickerRequest(
                    requestId = requestId,
                    allowMultiple = state.getBoolean(KEY_SHEET_ALLOW_MULTIPLE),
                    isFolder = false,
                    mimeTypes = state.getStringArrayList(KEY_SHEET_MIME_TYPES)
                        .orEmpty(),
                    captureMode = state.getInt(KEY_SHEET_CAPTURE_MODE),
                )
            }
            lastHandledViewUri = state.getString(KEY_LAST_HANDLED_VIEW_URI)
            if (state.getBoolean(KEY_PASSWORD_UNLOCK_PENDING)) {
                pendingPasswordUnlockCallback = { passwordsViewModel.unlock() }
            }
        }

        // Android re-delivers the original intent when it recreates this
        // Activity; consuming it again would open a duplicate tab for a
        // link the restored tab strip already contains. Skip only the URI
        // this Activity chain already handled: a genuinely new link that
        // relaunches a dead process still has a different URI and opens.
        if (savedInstanceState == null || intent?.dataString != lastHandledViewUri) {
            handleViewIntent(intent)
        }
        // A WEB_SEARCH re-delivered after a restore would search twice.
        if (savedInstanceState == null) {
            handleWebSearchIntent(intent)
        }
        collectDownloadRequests()
        collectPdfPrintRequests()
        collectExternalLinkRequests()
        collectFilePickerRequests()

        // First launch on this install: offer to become the default browser.
        // Asked once, from a tap, never in the background — a request the
        // system posts and nobody answers is auto-denied, and two auto-denials
        // lock the app out of the dialog for good.
        pendingRoleIntent = BrowserRole.requestIntent(this)
        if (savedInstanceState == null && BrowserRole.shouldAskOnLaunch(this)) {
            BrowserRole.markAsked(this)
            AppLog.event("first launch: offering default browser")
            askDefaultBrowser.value = true
        }

        setContent {
            val settings by browserViewModel.settings.collectAsState()
            val reportRequested by bugReportRequested.collectAsState()
            val offerDefaultBrowser by askDefaultBrowser.collectAsState()
            val defaultBrowserNeedsSettings by defaultBrowserFallback.collectAsState()
            // Read the device block while the owner is still typing, so the
            // first report is not delayed by the ROM property lookups.
            LaunchedEffect(reportRequested) {
                if (reportRequested) {
                    withContext(Dispatchers.IO) {
                        runCatching { DeviceInfo.summary(this@MainActivity) }
                    }
                }
            }
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
                        onClearCacheAndExit = { clearCacheAndExit() },
                        onAuthenticatePasswords = { callback ->
                            authenticateForPasswords(callback)
                        },
                        onReportBug = { bugReportRequested.value = true },
                        onSetDefaultBrowser = { startDefaultBrowserRequest() },
                    )
                    if (offerDefaultBrowser || defaultBrowserNeedsSettings) {
                        DefaultBrowserDialog(
                            // Only the system's own dialog is offered when it
                            // exists and the previous attempt did not fail.
                            canRequestRole = !defaultBrowserNeedsSettings &&
                                pendingRoleIntent != null,
                            onRequestRole = {
                                askDefaultBrowser.value = false
                                startDefaultBrowserRequest()
                            },
                            onOpenSettings = {
                                askDefaultBrowser.value = false
                                defaultBrowserFallback.value = false
                                openDefaultBrowserSettings()
                            },
                            onDismiss = {
                                askDefaultBrowser.value = false
                                defaultBrowserFallback.value = false
                            },
                        )
                    }
                    if (reportRequested) {
                        BugReportDialog(
                            buildReport = { description ->
                                BugReport.build(
                                    context = this@MainActivity,
                                    description = description,
                                    appState = browserViewModel.appStateReport(),
                                )
                            },
                            onDismiss = { bugReportRequested.value = false },
                        )
                    }
                    fileSourcePickerRequest?.let { request ->
                        FileSourcePickerSheet(
                            showCamera = request.captureMode != 0,
                            onCamera = {
                                fileSourcePickerRequest = null
                                requestCameraCapture()
                            },
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
        // The tab layer owns low-memory policy (exact critical/complete
        // levels only, mirroring Mozilla's TrimMemoryMiddleware); the
        // Activity merely forwards the raw signal. UI_HIDDEN, delivered
        // the moment the app is minimised, must never suspend anything.
        browserViewModel.onLowMemory(level)
    }

    override fun onStop() {
        super.onStop()
        browserViewModel.onAppBackgrounded()
        // The saved-login vault never stays unlocked while the app is in
        // the background: returning to Hexora must re-authenticate before
        // any decrypted username is shown or observed again.
        passwordsViewModel.lock()
    }

    override fun onStart() {
        super.onStart()
        // Returning to the foreground: recreate any tab session whose
        // content process died while backgrounded, so the visible tab
        // repaints its page instead of coming back empty.
        browserViewModel.onAppForegrounded()
    }

    override fun onResume() {
        super.onResume()
        // Shake-to-report is armed only while the app is in front of the owner.
        if (!shakeDetector.start(this)) {
            AppLog.event("shake detector: no accelerometer")
        }
        // Content verification is posted past the resume, when the window
        // surface actually exists again.
        window.decorView.post {
            browserViewModel.onBrowserResumed()
            // The window may have come back at a different size than the one
            // the surfaces were measured against (frozen-app restore, split
            // screen). Asking for one layout pass here costs nothing and
            // prevents the page pane from staying at the old rectangle.
            window.decorView.requestLayout()
        }
    }

    /**
     * The manifest tells the system this Activity handles size changes itself
     * (`configChanges` lists `screenSize`, `smallestScreenSize`, `screenLayout`
     * and `orientation`), which keeps the browser from reloading every page on
     * rotation. It said so without overriding this method, so a real change
     * reached nobody: the window resized, the content stayed where it was, and
     * the app came back drawn in part of the screen — the HyperOS 2 report.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        window.decorView.requestLayout()
        browserViewModel.onWindowGeometryChanged()
        AppLog.event(
            "config changed: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp " +
                "orientation=${newConfig.orientation}",
        )
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
    }

    /**
     * Asks the system to make Hexora the default browser.
     *
     * When the role dialog is unavailable the owner is taken to the screen that
     * can actually change it; a button that silently does nothing is worse than
     * no button.
     */
    private fun startDefaultBrowserRequest() {
        // Already the default: the role request is a no-op the system may not
        // even answer, so a tap would look broken. Say what is true instead.
        if (BrowserRole.isDefault(this)) {
            Toast.makeText(
                this,
                R.string.default_browser_already,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val intent = pendingRoleIntent ?: BrowserRole.requestIntent(this)
        if (intent == null) {
            openDefaultBrowserSettings()
            return
        }
        pendingRoleIntent = intent
        val launched = runCatching { roleRequestLauncher.launch(intent) }.isSuccess
        if (!launched) {
            AppLog.event("default browser: role dialog unavailable")
            openDefaultBrowserSettings()
        }
    }

    /** App details ("Open by default"), then the system default-apps list. */
    private fun openDefaultBrowserSettings() {
        val openedAppDetails = runCatching {
            startActivity(BrowserRole.settingsIntent(this))
        }.isSuccess
        if (openedAppDetails) return
        val openedDefaults = runCatching {
            startActivity(BrowserRole.defaultAppsSettingsIntent(this))
        }.isSuccess
        if (!openedDefaults) {
            Toast.makeText(
                this,
                R.string.default_browser_settings_hint,
                Toast.LENGTH_LONG,
            ).show()
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Make this the Activity's current intent so a later recreation
        // re-delivers (and correctly skips) the newest link, not the
        // original one from the first launch.
        setIntent(intent)
        handleViewIntent(intent)
        handleWebSearchIntent(intent)
    }

    /**
     * Device-credential/biometric gate for the saved-login manager.
     *
     * Neither route is trusted blindly. The framework prompt throws
     * `SecurityException` without `USE_BIOMETRIC` in the manifest, and OEM
     * implementations (One UI, HyperOS) throw from `authenticate()` as well
     * when no biometric is enrolled or the prompt is asked for at a bad
     * moment. A gate that cannot open must say so and leave the vault locked;
     * it must never take the process down, which is exactly what it did
     * before: the manifest had no biometric permission at all.
     */
    private fun authenticateForPasswords(onSuccess: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val started = runCatching { showBiometricPrompt(onSuccess) }
                .onFailure { AppLog.event("passwords: biometric prompt failed (${it.javaClass.simpleName})") }
                .isSuccess
            if (started) return
        }
        // API < 30 cannot combine biometric and device credential in one
        // framework prompt, and on API 30+ this is the way back when the
        // prompt itself is unusable.
        val opened = runCatching { showDeviceCredentialPrompt(onSuccess) }
            .onFailure { AppLog.event("passwords: credential prompt failed (${it.javaClass.simpleName})") }
            .getOrDefault(false)
        if (!opened) {
            Toast.makeText(
                this,
                R.string.passwords_unlock_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.passwords_unlock_title))
            .setSubtitle(getString(R.string.passwords_unlock_subtitle))
            .setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(
            CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult?,
                ) {
                    onSuccess()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence?,
                ) {
                    // 10 = ERROR_USER_CANCELED and 13 =
                    // ERROR_NEGATIVE_BUTTON of the framework BiometricPrompt
                    // (the constants are not exposed in the SDK 37 stubs,
                    // so the documented values are compared directly). A
                    // user cancelling the prompt is not an unlock failure.
                    if (errorCode != 10 && errorCode != 13) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.passwords_unlock_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    /** @return true when the system prompt was actually opened. */
    private fun showDeviceCredentialPrompt(onSuccess: () -> Unit): Boolean {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        // With no PIN/pattern/password set there is nothing to confirm, and
        // the intent builder returns null.
        if (!keyguard.isDeviceSecure) return false
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.passwords_unlock_title),
            getString(R.string.passwords_unlock_subtitle),
        ) ?: return false
        pendingPasswordUnlockCallback = onSuccess
        passwordUnlockLauncher.launch(intent)
        return true
    }

    /** Opens http(s) links handed to us by other apps in a fresh tab. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.dataString
                ?.takeIf { UrlUtils.isHttpUrl(it) }
                ?.let { url ->
                    lastHandledViewUri = url
                    browserViewModel.handleExternalUrl(url)
                }
        }
    }

    /** `ACTION_WEB_SEARCH` from another app or the search key. */
    private fun handleWebSearchIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_WEB_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
        if (query.isEmpty()) return
        val settings = browserViewModel.settings.value
        val url = settings.searchEngine.searchUrl(query, settings.customSearchTemplate)
        AppLog.event("web search intent")
        browserViewModel.handleExternalUrl(url)
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

    /** Opens non-web page links (tel:, mailto:, intent:, …) in their apps. */
    private fun collectExternalLinkRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                browserViewModel.externalLinkEvents.collect { uri ->
                    val opened = runCatching {
                        val intent = if (uri.startsWith("intent:", ignoreCase = true)) {
                            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                        } else {
                            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                        }
                        val scheme = Uri.parse(uri).scheme?.lowercase()
                        if (scheme in setOf("http", "https", "intent")) {
                            intent.addCategory(Intent.CATEGORY_BROWSABLE)
                        }
                        intent.component = null
                        intent.selector = null
                        startActivity(intent)
                        true
                    }.getOrDefault(false)
                    if (!opened) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.external_link_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    /** Receives Gecko-generated PDFs and hands them to Android printing. */
    private fun collectPdfPrintRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                browserViewModel.pdfPrintEvents.collect { event ->
                    val stream = event.bodySource()
                    if (stream == null) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.print_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@collect
                    }
                    val pdfFile = withContext(Dispatchers.IO) {
                        runCatching {
                            val directory = File(cacheDir, "print").apply { mkdirs() }
                            val file = File(directory, event.fileName)
                            stream.buffered().use { input ->
                                file.outputStream().buffered().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            file
                        }.getOrNull()
                    }
                    if (pdfFile == null) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.print_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        printPdfFile(pdfFile, event.fileName)
                    }
                }
            }
        }
    }

    private fun printPdfFile(file: File, jobName: String) {
        val printed = runCatching {
            val printManager = getSystemService(PRINT_SERVICE) as PrintManager
            printManager.print(
                jobName,
                PdfFilePrintAdapter(applicationContext, file),
                PrintAttributes.Builder().build(),
            )
        }.isSuccess
        if (!printed) {
            Toast.makeText(this, R.string.print_failed, Toast.LENGTH_SHORT).show()
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

                        request.captureMode != 0 -> fileSourcePickerRequest = request

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

    private fun requestCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCameraCapture()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraCapture() {
        runCatching {
            val request = pendingFilePickerRequest
            val mimeTypes = request?.let { pickerMimeTypes(it.mimeTypes).toList() }
                .orEmpty()
            val videoOnly = mimeTypes.isNotEmpty() &&
                mimeTypes.all { it.startsWith("video/") }
            val directory = File(cacheDir, "camera_captures").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date())
            if (videoOnly) {
                val videoFile = File(directory, "VID_$timestamp.mp4")
                val videoUri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    videoFile,
                )
                pendingCameraVideoFile = videoFile
                cameraVideoLauncher.launch(videoUri)
            } else {
                val photoFile = File(directory, "IMG_$timestamp.jpg")
                val photoUri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    photoFile,
                )
                pendingCameraPhotoFile = photoFile
                cameraCaptureLauncher.launch(photoUri)
            }
        }.onFailure {
            pendingCameraPhotoFile = null
            pendingCameraVideoFile = null
            cancelPendingFilePicker()
        }
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
        // Note: returning from the picker does not rebind the Gecko
        // surface here; surface recovery is centralized in the
        // onResume path (see onBrowserResumed), which runs after the
        // picker activity finishes and the surface exists again.
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
        // Android's document contract treats a "*/*" entry as "any type";
        // sending concrete types next to it is redundant and some OEM
        // pickers mishandle the mixture, so collapse it to the wildcard.
        val effective = if ("*/*" in converted) listOf("*/*") else converted
        return effective.ifEmpty { listOf("*/*") }.toTypedArray()
    }

    /** Routes engine download requests to Hexora's download layer. */
    private fun collectDownloadRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                browserViewModel.downloadEvents.collect { event ->
                    if (!requestedDownloadNotificationPermission &&
                        android.os.Build.VERSION.SDK_INT >=
                        android.os.Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestedDownloadNotificationPermission = true
                        runCatching {
                            downloadNotificationPermissionLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        }
                    }
                    val bodySource = event.bodySource
                    val downloadId = try {
                        if (bodySource != null) {
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
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.w("HexoraDownloads", "Could not start download: ${error.javaClass.simpleName}")
                        -1L
                    }
                    val started = downloadId >= 0
                    if (started && !event.isPrivate) {
                        // Private-tab downloads still produce the file the
                        // user asked for, but their URL is not persisted in
                        // the browser's own download history.
                        launch {
                            try {
                                container.downloadRepository.record(
                                    downloadManagerId = downloadId,
                                    url = event.url,
                                    fileName = event.fileName,
                                    mimeType = event.mimeType,
                                    // Stream transfers consume a one-shot
                                    // response body and can never be replayed;
                                    // the Downloads page needs to know that.
                                    sourceKind = if (bodySource != null) {
                                        DownloadEntity.SOURCE_STREAM
                                    } else {
                                        DownloadEntity.SOURCE_HTTP
                                    },
                                )
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                // Recording metadata must never crash browsing
                                // or stop the already-running transfer.
                                android.util.Log.w("HexoraDownloads", "Could not record download: ${error.javaClass.simpleName}")
                            }
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
    showCamera: Boolean = false,
    onCamera: () -> Unit = {},
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
            if (showCamera) {
                TextButton(onClick = onCamera, modifier = Modifier.fillMaxWidth()) {
                    Text(androidx.compose.ui.res.stringResource(R.string.file_source_camera))
                }
            }
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

/** Minimal Android print adapter for a Gecko-generated PDF file. */
private class PdfFilePrintAdapter(
    private val context: android.content.Context,
    private val file: File,
) : PrintDocumentAdapter() {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    private fun closeRenderer() {
        runCatching { renderer?.close() }
        runCatching { fileDescriptor?.close() }
        renderer = null
        fileDescriptor = null
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        try {
            // A layout pass can run more than once (for example when the
            // user changes printer settings); never leak the previous
            // renderer/descriptor pair.
            closeRenderer()
            val descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            fileDescriptor = descriptor
            val pdfRenderer = PdfRenderer(descriptor)
            renderer = pdfRenderer
            callback.onLayoutFinished(
                PrintDocumentInfo.Builder(file.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pdfRenderer.pageCount)
                    .build(),
                true,
            )
        } catch (error: Exception) {
            closeRenderer()
            callback.onLayoutFailed(error.message)
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onWriteCancelled()
            return
        }
        val pdfRenderer = renderer
        if (pdfRenderer == null) {
            callback.onWriteFailed("PDF renderer unavailable")
            return
        }
        // Intersect the requested ranges with the real page count.
        // PageRange.ALL_PAGES ends at Int.MAX_VALUE, so expanding a range
        // before clamping it (the previous approach) could iterate and
        // allocate effectively forever.
        val requestedPages = sortedSetOf<Int>()
        pages.forEach { range ->
            val start = range.start.coerceAtLeast(0)
            val end = minOf(range.end, pdfRenderer.pageCount - 1)
            for (page in start..end) requestedPages += page
        }
        val pageIndices = if (pages.isEmpty()) {
            (0 until pdfRenderer.pageCount).toList()
        } else {
            requestedPages.toList()
        }
        if (pageIndices.isEmpty()) {
            callback.onWriteFailed("No pages to print")
            return
        }
        // PdfDocument (android.graphics.pdf) is used directly: the
        // convenience PrintedPdfDocument wrapper is not part of the
        // SDK 37 stubs this project compiles against. Each output page
        // takes the source page's own dimensions (PdfRenderer reports
        // page sizes in 1/72-inch points, exactly what PageInfo wants).
        val document = PdfDocument()
        try {
            pageIndices.forEachIndexed { outputIndex, pageIndex ->
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                val sourcePage = pdfRenderer.openPage(pageIndex)
                var bitmap: Bitmap? = null
                try {
                    bitmap = Bitmap.createBitmap(
                        sourcePage.width,
                        sourcePage.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    // A fresh bitmap is transparent; without an opaque
                    // background, pages can composite to blank output in
                    // some print pipelines.
                    bitmap.eraseColor(Color.WHITE)
                    sourcePage.render(
                        bitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                    )
                    val printedPage = document.startPage(
                        PdfDocument.PageInfo.Builder(
                            sourcePage.width,
                            sourcePage.height,
                            outputIndex,
                        ).create(),
                    )
                    try {
                        printedPage.canvas.drawBitmap(
                            bitmap,
                            null,
                            Rect(
                                0,
                                0,
                                printedPage.canvas.width,
                                printedPage.canvas.height,
                            ),
                            null,
                        )
                    } finally {
                        document.finishPage(printedPage)
                    }
                } finally {
                    bitmap?.recycle()
                    sourcePage.close()
                }
            }
            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                document.writeTo(output)
            }
            // The written PDF renumbers its pages from zero (see
            // startPage(outputIndex) above), so report the output range,
            // not the source page numbers. Reporting source indices for a
            // subset (e.g. only page 3) would claim a page the produced
            // one-page document does not contain.
            callback.onWriteFinished(
                arrayOf(PageRange(0, pageIndices.lastIndex)),
            )
        } catch (error: Exception) {
            callback.onWriteFailed(error.message)
        } finally {
            document.close()
        }
    }

    override fun onFinish() {
        closeRenderer()
        super.onFinish()
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
    onClearCacheAndExit: () -> Unit,
    onAuthenticatePasswords: ((() -> Unit) -> Unit) = { callback -> callback() },
    onReportBug: () -> Unit = {},
    onSetDefaultBrowser: () -> Unit = {},
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
                onOpenTabs = {
                    // Snapshot the visible tab as the user leaves for the
                    // tab tray (Firefox refreshes tab thumbnails when the
                    // tray is opened); otherwise the tile can keep showing
                    // the page as it looked at load/switch time, before the
                    // user scrolled or the page updated.
                    browserViewModel.captureActiveThumbnail()
                    navController.navigate(Routes.TABS)
                },
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
                onAuthenticate = onAuthenticatePasswords,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onClearBrowsingData = { browserViewModel.clearBrowsingData() },
                onOpenPasswords = { navController.navigate(Routes.PASSWORDS) },
                onOpenSitePermissions = {
                    navController.navigate(Routes.SITE_PERMISSIONS)
                },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onReportBug = onReportBug,
                onSetDefaultBrowser = onSetDefaultBrowser,
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onReportBug = onReportBug,
                onOpenLicences = { navController.navigate(Routes.LICENCES) },
            )
        }
        composable(Routes.LICENCES) {
            LicencesScreen(
                onBack = { navController.popBackStack() },
                // Licence texts and the engine source are ordinary pages, so
                // they open in Hexora itself rather than in an external app.
                onOpenUrl = { url -> openInBrowser(url) },
            )
        }
        composable(Routes.SITE_PERMISSIONS) {
            SitePermissionsScreen(
                viewModel = browserViewModel,
                onBack = { navController.popBackStack() },
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
