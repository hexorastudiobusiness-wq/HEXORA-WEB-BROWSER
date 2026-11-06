package com.example.browser.util

import android.content.Context
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
        appendLine(DeviceInfo.summary(context))
        appendLine()
        appendLine("— App state —")
        appendLine(appState.trim().ifBlank { "(unavailable)" })
        appendLine()
        appendLine("— App log —")
        appendLine(AppLog.dump())
        AppLog.crashDump()?.let { crash ->
            appendLine()
            appendLine("— Last crash —")
            appendLine(crash)
        }
        appendLine()
        appendLine("(Prepared by Hexora ${com.example.browser.BuildConfig.VERSION_NAME},")
        appendLine(" embedded browser engine: GeckoView)")
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

    /** Plain "write to the developer" link: no report, just their address. */
    fun contactIntent(): Intent = Intent(
        Intent.ACTION_SENDTO,
        Uri.parse("mailto:${BrowserRole.DEVELOPER_EMAIL}"),
    ).apply {
        putExtra(Intent.EXTRA_SUBJECT, "Hexora")
    }
}
