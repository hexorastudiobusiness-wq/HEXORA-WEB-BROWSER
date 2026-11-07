package com.example.browser.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.example.browser.BuildConfig

/**
 * Hardware/software identity of the device the bug happened on.
 *
 * A report that says only "it is black" cannot be acted on; the same symptom
 * means different things on One UI 7 and HyperOS 3, and on a 60 Hz budget phone
 * versus a 120 Hz flagship. Everything here is read-only information about the
 * device and the build, plus the ROM's own version properties, which the
 * vendor skins only expose through `getprop`.
 */
object DeviceInfo {

    /** ROM version properties, in the order a report should show them. */
    private val ROM_PROPERTIES = listOf(
        "ro.build.version.oneui",           // Samsung One UI
        "ro.build.version.oneui_minor",     // Samsung One UI (minor)
        "ro.mi.os.version.name",            // HyperOS 2+
        "ro.miui.ui.version.name",          // MIUI / HyperOS 1
        "ro.miui.ui.version.code",
        "ro.build.version.oplusrom",        // ColorOS / OxygenOS 12+
        "ro.build.version.opporom",         // ColorOS (older)
        "ro.oxygen.version",                // OxygenOS
        "ro.build.version.realmeui",        // realme UI
        "ro.build.version.magic",           // MagicOS
        "ro.build.version.emui",            // EMUI
        "ro.vivo.os.version",               // OriginOS / Funtouch
        "ro.nothing.version",               // Nothing OS
        "ro.build.version.ios",             // some NothingOS builds
        "ro.build.display.id",
        "ro.product.system.name",
        "ro.build.fingerprint",
    )

    private val propCache = mutableMapOf<String, String>()
    private val lock = Any()

    /**
     * `getprop <name>` — the only portable way to read these on modern Android.
     *
     * Bounded on purpose. A property read is a `fork`/`exec` of another
     * process: on a loaded device it can take a second, and an unbounded wait
     * here once left the report builder hanging with the dialog frozen. A
     * property that does not answer within [PROP_TIMEOUT_MS] is treated as
     * absent — the report says less, but it arrives.
     */
    fun property(name: String): String = synchronized(lock) {
        propCache[name]?.let { return it }
        val value = runCatching {
            val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
            val finished = process.waitFor(PROP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                ""
            } else {
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            }
        }.getOrDefault("")
        propCache[name] = value
        value
    }

    private const val PROP_TIMEOUT_MS = 600L

    /** e.g. "HyperOS 2.0.201" / "One UI 7.0" — empty when nothing is exposed. */
    fun romLabel(): String {
        val parts = ROM_PROPERTIES.mapNotNull { prop ->
            val value = property(prop)
            if (value.isBlank()) null else "$prop=$value"
        }
        return parts.joinToString(", ")
    }

    /**
     * The block a report starts with: hardware, OS, ROM, display, app build.
     * Multi-line plain text, no user data.
     */
    fun summary(context: Context): String = runCatching { summaryOrThrow(context) }
        .getOrElse { error ->
            // A report without device details is still a report; a crash here
            // took the whole app down from the bug-report dialog.
            "— Device —\n(unavailable: ${error.javaClass.simpleName})\n"
        }

    private fun summaryOrThrow(context: Context): String = buildString {
        appendLine("— Device —")
        appendLine("Manufacturer : ${Build.MANUFACTURER}")
        appendLine("Brand        : ${Build.BRAND}")
        appendLine("Model        : ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Hardware     : ${Build.HARDWARE} / ${Build.BOARD}")
        appendLine("Android      : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Security     : ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Build id     : ${Build.DISPLAY} / ${Build.ID}")
        appendLine("ABIs         : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("Kernel       : ${System.getProperty("os.version").orEmpty()}")
        appendLine("ROM          : ${romLabel().ifBlank { "stock / not exposed" }}")
        appendLine()
        appendLine("— Display —")
        val metrics = context.resources.displayMetrics
        val configuration = context.resources.configuration
        appendLine("Resolution   : ${metrics.widthPixels} x ${metrics.heightPixels} px")
        appendLine("Density      : ${metrics.densityDpi} dpi")
        appendLine("Refresh rate : ${refreshRate(context)} Hz")
        appendLine(
            "Orientation  : ${
                if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    "landscape"
                } else {
                    "portrait"
                }
            }",
        )
        appendLine()
        appendLine("— App —")
        appendLine("Package      : ${context.packageName}")
        appendLine("Version      : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Debug build  : ${BuildConfig.DEBUG}")
        // `getLocales()` is empty when a configuration was built without one.
        val locale = runCatching { configuration.locales.get(0) }
            .getOrNull() ?: java.util.Locale.getDefault()
        appendLine("Locale       : $locale")
        appendLine("Time zone    : ${java.util.TimeZone.getDefault().id}")
        appendLine("Uptime (app) : since launch")
    }

    private fun refreshRate(context: Context): String = runCatching {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display?.refreshRate?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "?"
    }.getOrDefault("?")
}
