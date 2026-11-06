package com.example.browser.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A small in-memory log the app owns.
 *
 * A user cannot pull `logcat` off a locked phone, and a bug report with no
 * evidence is useless. Every interesting event is mirrored here (and into
 * `logcat`), kept in a bounded ring buffer, and dumped into the report the
 * shake gesture prepares. Contents are app events and counters only — never a
 * URL, a page title, a typed password or page text.
 */
object AppLog {

    private const val TAG = "Hexora"

    /** Enough history to cover the minutes before a crash, small enough for mail. */
    private const val MAX_ENTRIES = 250

    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val lock = Any()
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** True once [install] ran; the process only needs it once. */
    @Volatile
    private var installed = false

    /** The last uncaught exception, kept across the crash it caused. */
    @Volatile
    private var lastCrash: String? = null

    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                lastCrash = buildString {
                    append("thread=").append(thread.name).append('\n')
                    append(Log.getStackTraceString(error))
                }
                // Log first, then let the platform do what it always does.
                event("CRASH ${error.javaClass.name}: ${error.message}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun event(message: String) {
        runCatching { Log.i(TAG, message) }
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.pollFirst()
            entries.addLast("${stamp.format(Date())}  $message")
        }
    }

    /** Plain-text dump, oldest first, ready to paste into a report. */
    fun dump(): String = synchronized(lock) {
        if (entries.isEmpty()) return@synchronized "(no events recorded)"
        entries.joinToString("\n")
    }

    fun crashDump(): String? = lastCrash

    fun clear() = synchronized(lock) { entries.clear() }
}
