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
class AutomationLifecycleReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: AutomationCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                coordinator.recoverAndSchedule(intent.action ?: "systemEvent")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
