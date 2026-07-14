package com.system.launcher.tools.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AutomationBoundaryReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: AutomationCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExactAlarmScheduler.ACTION_AUTOMATION_BOUNDARY) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                coordinator.recoverAndSchedule("alarm")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
