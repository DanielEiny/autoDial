package com.autodial

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        EventLog.log("App process started (pid=${android.os.Process.myPid()})")
    }
}
