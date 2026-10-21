package com.example.browser.util

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.browser.MainActivity
import com.example.browser.R

/**
 * Shows the stack trace of a crash that killed the main process. Runs in
 * its own process (see AndroidManifest) and uses only framework Views —
 * no Compose, no ViewModels, no database, no Gecko — so it can render
 * even when whatever it reports on is completely broken. Opened directly
 * by [CrashReporter]'s uncaught-exception handler at crash time, and it
 * also serves as the fallback display for a trace saved to disk.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = intent.getStringExtra(EXTRA_CRASH)
            ?: CrashReporter.lastCrash(this)
            ?: getString(R.string.crash_title)

        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.crash_title)
            textSize = 15f
            setPadding(0, 0, 0, padding)
        })
        root.addView(Button(this).apply {
            text = getString(R.string.crash_share)
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, crash)
                }
                startActivity(Intent.createChooser(send, null))
            }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.crash_open_app)
            setOnClickListener {
                CrashReporter.clear(this@CrashReportActivity)
                startActivity(
                    Intent(this@CrashReportActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                finish()
            }
        })
        root.addView(
            ScrollView(this).apply {
                addView(TextView(this@CrashReportActivity).apply {
                    text = crash
                    setTextIsSelectable(true)
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                })
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)
    }

    companion object {
        const val EXTRA_CRASH = "extra_crash"
    }
}
