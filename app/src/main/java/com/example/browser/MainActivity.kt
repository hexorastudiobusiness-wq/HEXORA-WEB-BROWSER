package com.example.browser

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import com.example.browser.ui.history.HistoryScreen
import com.example.browser.ui.history.HistoryViewModel
import com.example.browser.ui.settings.SettingsScreen
import com.example.browser.ui.settings.SettingsViewModel
import com.example.browser.ui.tabs.TabsScreen
import com.example.browser.ui.theme.HexoraBrowserTheme
import kotlinx.coroutines.launch

private object Routes {
    const val BROWSER = "browser"
    const val TABS = "tabs"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
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
                    settingsRepository = container.settingsRepository,
                )
            }
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

    private val settingsViewModel: SettingsViewModel by viewModels {
        viewModelFactory {
            initializer { SettingsViewModel(container.settingsRepository) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleViewIntent(intent)
        collectDownloadRequests()

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
                        settingsViewModel = settingsViewModel,
                    )
                }
            }
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
                ?.takeIf { it.isNotBlank() }
                ?.let { browserViewModel.handleExternalUrl(it) }
        }
    }

    /** Routes engine download requests to the Android DownloadManager layer. */
    private fun collectDownloadRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                browserViewModel.downloadEvents.collect { event ->
                    val started = container.downloadHandler.download(
                        url = event.url,
                        fileName = event.fileName,
                        mimeType = event.mimeType,
                    )
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
    settingsViewModel: SettingsViewModel,
) {
    val navController = rememberNavController()

    fun openInBrowser(url: String) {
        browserViewModel.openUrl(url)
        navController.navigateToBrowser()
    }

    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                viewModel = browserViewModel,
                onOpenTabs = { navController.navigate(Routes.TABS) },
                onOpenBookmarks = { navController.navigate(Routes.BOOKMARKS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.TABS) {
            TabsScreen(
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
        composable(Routes.HISTORY) {
            HistoryScreen(
                viewModel = historyViewModel,
                onOpenUrl = { url -> openInBrowser(url) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onClearBrowsingData = { browserViewModel.clearBrowsingData() },
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
