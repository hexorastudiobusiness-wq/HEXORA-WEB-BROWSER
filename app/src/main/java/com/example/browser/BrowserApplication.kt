package com.example.browser

import android.app.Application
import com.example.browser.util.AppLog

class BrowserApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Before the container: if anything below throws, the report the user
        // can shake out of the app must already know what the process was
        // doing (and the crash handler must be installed).
        // The context is what lets the crash trace outlive the crash.
        AppLog.install(this)
        AppLog.event("process start")
        container = AppContainer(this)
    }
}
