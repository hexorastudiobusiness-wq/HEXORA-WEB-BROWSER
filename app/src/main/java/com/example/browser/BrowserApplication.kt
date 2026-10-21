package com.example.browser

import android.app.Application
import com.example.browser.util.CrashReporter

class BrowserApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        // First thing: a startup crash must leave a readable trace behind
        // for the next launch instead of dying without a word.
        CrashReporter.install(this)
        super.onCreate()
        container = AppContainer(this)
    }
}
