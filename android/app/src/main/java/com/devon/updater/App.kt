package com.devon.updater

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.channels(this)
        CheckWorker.schedule(this)
    }
}
