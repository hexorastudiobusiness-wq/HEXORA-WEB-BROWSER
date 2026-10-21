package com.example.browser.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the stack trace of an uncaught exception so the NEXT launch can
 * show it to the user (and the user can forward it for diagnosis) instead
 * of the app dying silently at startup. The previous default handler is
 * always invoked afterwards, so process-crash behaviour is unchanged.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val trace = StringWriter().also { sw ->
                    throwable.printStackTrace(PrintWriter(sw))
                }.toString()
                File(appContext.filesDir, FILE_NAME)
                    .writeText("thread=${thread.name}\n$trace")
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
