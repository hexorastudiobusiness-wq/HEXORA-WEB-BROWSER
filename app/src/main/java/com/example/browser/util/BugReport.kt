package com.example.browser.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri

/**
 * The bug report the shake gesture prepares.
 *
 * It is assembled on the device, handed to the mail app the owner already uses,
 * and only sent if they press send. Nothing leaves the phone on its own — this
 * build talks to no server of ours. The body is plain text so it survives every
 * mail client and can also be pasted into a chat if the owner prefers.
 */
object BugReport {

    /** Everything the developer needs, in the order they read it. */
    fun build(
        context: Context,
        description: String,
        appState: String,
    ): String = buildString {
        appendLine("— What happened —")
        appendLine(description.trim().ifBlank { "(not described)" })
        appendLine()
        // Each block is optional: a report that loses the display list is
        // worth more than a dialog that throws and kills the app.
        appendLine(runCatching { DeviceInfo.summary(context) }.getOrElse {
            "— Device —\n(unavailable: ${it.javaClass.simpleName})\n"
        })
        appendLine()
        appendLine("— App state —")
        appendLine(appState.trim().ifBlank { "(unavailable)" })
        appendLine()
        appendLine("— App log —")
        appendLine(runCatching { AppLog.dump() }.getOrDefault("(unavailable)"))
        runCatching { AppLog.crashDump() }.getOrNull()?.let { crash ->
            appendLine()
            appendLine("— Last crash —")
            appendLine(crash)
        }
        appendLine()
        appendLine("(Prepared by Hexora ${com.example.browser.BuildConfig.VERSION_NAME},")
        appendLine(" embedded browser engine: GeckoView)")
    }

    /**
     * The last-resort body: what the app can guarantee to produce when the full
     * report cannot be assembled. Small on purpose, and built from parts that
     * cannot throw (the log dump is already tolerant).
     */
    fun minimal(description: String): String = buildString {
        appendLine("— What happened —")
        appendLine(description.trim().ifBlank { "(not described)" })
        appendLine()
        appendLine("(the full report could not be assembled)")
        appendLine()
        appendLine("— App log —")
        appendLine(runCatching { AppLog.dump() }.getOrDefault("(unavailable)"))
        runCatching { AppLog.crashDump() }.getOrNull()?.let { crash ->
            appendLine()
            appendLine("— Last crash —")
            appendLine(crash)
        }
    }

    fun subject(description: String): String {
        val firstLine = description.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(70)
        return if (firstLine.isNullOrBlank()) {
            "Hexora bug report"
        } else {
            "Hexora bug report — $firstLine"
        }
    }

    /**
     * Opens the owner's mail composer with the report already in the body.
     * `ACTION_SEND` instead of a `mailto:` URI because a report is far longer
     * than a URI can safely carry.
     */
    fun mailIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(BrowserRole.DEVELOPER_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject(text))
        putExtra(Intent.EXTRA_TEXT, text)
    }

    /**
     * Starts [intent] without letting the launch kill the process.
     *
     * Two things go wrong here on real devices: an OEM context that is not an
     * Activity (which needs `FLAG_ACTIVITY_NEW_TASK` and otherwise throws
     * `AndroidRuntimeException`), and a system with no app that answers the
     * intent at all (`ActivityNotFoundException`). Both used to be one
     * unguarded `startActivity` away from a crash in the report dialog.
     *
     * @return false when nothing handled the intent.
     */
    fun launch(context: Context, intent: Intent): Boolean {
        val host = context.findActivity()
        val safeIntent = if (host == null) {
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            intent
        }
        return try {
            (host ?: context).startActivity(safeIntent)
            true
        } catch (error: ActivityNotFoundException) {
            false
        } catch (error: RuntimeException) {
            AppLog.event("report launch failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    /** Plain "write to the developer" link: no report, just their address. */
    fun contactIntent(): Intent = Intent(
        Intent.ACTION_SENDTO,
        Uri.parse("mailto:${BrowserRole.DEVELOPER_EMAIL}"),
    ).apply {
        putExtra(Intent.EXTRA_SUBJECT, "Hexora")
    }
}
