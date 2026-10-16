package com.example.browser.browser

/**
 * Engine-level options used when a new [BrowserSession] is created.
 * These are mapped from persisted user settings by the ViewModel layer;
 * the engine itself knows nothing about DataStore.
 */
data class BrowserSessionConfig(
    val isPrivate: Boolean = false,
    val desktopMode: Boolean = false,
)
