package com.tapflow.android

import android.app.Application
import com.tapflow.android.data.Repo

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Both the activity and the accessibility service read through Repo, and either can be the
        // first to start, so initialise it here rather than in whichever happens to win.
        Repo.init(this)
    }
}
