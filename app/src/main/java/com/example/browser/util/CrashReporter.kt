package com.example.browser.util

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash persistence + reporting. On an uncaught exception the trace is
 * (1) written to disk for the next launch and (2) shown immediately in
 * [CrashReportActivity], which runs in a separate process and therefore
 * survives the death of the crashing process. The previous default
 * handler is always invoked afterwards, so crash behaviour is unchanged.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_REPORT_CHARS = 24_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = runCatching {
                StringWriter().also { sw ->
                    throwable.printStackTrace(PrintWriter(sw))
                }.toString()
            }.getOrDefault(throwable.toString())
            val report = "thread=${thread.name}\n$trace"
            runCatching {
                File(appContext.filesDir, FILE_NAME).writeText(report)
            }
            // Show the report right away in its own process; the crashing
            // process is about to be killed by the default handler.
            runCatching {
                appContext.startActivity(
                    Intent(appContext, CrashReportActivity::class.java)
                        .putExtra(
                            CrashReportActivity.EXTRA_CRASH,
                            report.take(MAX_REPORT_CHARS),
                        )
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK,
                        ),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun lastCrash(context: Context): String? = runCatching {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        }
    }
}
