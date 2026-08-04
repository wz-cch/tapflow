package com.tapflow.android

import android.app.Application
import com.tapflow.android.data.Repo
import com.tapflow.android.engine.CrashLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, and before Repo: a crash during startup is one of the ones worth catching, and this is
        // the earliest point in the process that runs for every entry point — activity or service.
        CrashLog.install(this)
        // Both the activity and the accessibility service read through Repo, and either can be the
        // first to start, so initialise it here rather than in whichever happens to win.
        Repo.init(this)
    }
}
