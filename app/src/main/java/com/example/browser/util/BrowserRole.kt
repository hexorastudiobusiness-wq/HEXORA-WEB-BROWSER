package com.example.browser.util

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Being the user's default browser.
 *
 * Android decides that with a *role* (`android.app.role.BROWSER`), not with an
 * old-style "clear defaults" flow. The system only offers the dialog to apps
 * that qualify, and it auto-denies a request that is never answered — so the
 * request is only ever started from a tap, and when the system refuses to offer
 * it at all the owner is taken to the screen where the choice can be made by
 * hand instead of being left with a dead button.
 */
object BrowserRole {

    /** The address the app's mail links are sent to. */
    const val DEVELOPER_EMAIL = "Hexora.Studio.Business@Gmail.com"

    /** Same value as `RoleManager.ROLE_BROWSER`, kept literal so it compiles
     *  on every SDK level the app supports. */
    private const val ROLE_BROWSER = "android.app.role.BROWSER"
    private const val PREFS = "hexora_default_browser"
    private const val KEY_ASKED = "asked_on_first_launch"

    private fun manager(context: Context): RoleManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            context.getSystemService(RoleManager::class.java)
        }.getOrNull()
    }

    /** False on API 26–28: those devices use the old "default apps" screen. */
    fun isSupported(context: Context): Boolean = runCatching {
        manager(context)?.isRoleAvailable(ROLE_BROWSER) == true
    }.getOrDefault(false)

    fun isDefault(context: Context): Boolean = runCatching {
        manager(context)?.isRoleHeld(ROLE_BROWSER) == true
    }.getOrDefault(false)

    /** The system dialog, or null when only the settings screen can help. */
    fun requestIntent(context: Context): Intent? = runCatching {
        manager(context)?.takeIf { it.isRoleAvailable(ROLE_BROWSER) }
            ?.createRequestRoleIntent(ROLE_BROWSER)
    }.getOrNull()

    /**
     * Where to send the owner when the role dialog is unavailable (or already
     * denied twice): the app's own "Open by default" page, with the system's
     * default-apps list as the fallback on builds that hide it.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun defaultAppsSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** True exactly once: the prompt belongs to the first launch only. */
    fun shouldAskOnLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return false
        // Never ask an owner who already made Hexora the default.
        return !isDefault(context)
    }

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED, true)
            .apply()
    }
}
