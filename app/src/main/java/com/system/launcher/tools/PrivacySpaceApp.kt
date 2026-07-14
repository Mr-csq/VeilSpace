package com.system.launcher.tools

import android.app.Application
import com.system.launcher.tools.automation.AutomationCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PrivacySpaceApp : Application() {
    @Inject lateinit var automationCoordinator: AutomationCoordinator

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            automationCoordinator.recoverAndSchedule("applicationStart")
        }
    }
}
