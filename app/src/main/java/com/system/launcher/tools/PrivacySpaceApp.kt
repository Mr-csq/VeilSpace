package com.system.launcher.tools

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PrivacySpaceApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 初始化应用
    }
}
