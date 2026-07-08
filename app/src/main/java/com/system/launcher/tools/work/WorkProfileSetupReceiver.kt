package com.system.launcher.tools.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-applies Work Profile policies after app updates or device restart.
 */
class WorkProfileSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val manager = WorkProfileManager(context)
        val isOwner = manager.isProfileOwner()
        Log.i(TAG, "Received $action, isProfileOwner=$isOwner")
        if (isOwner) {
            manager.configureCrossProfileEntry()
        }
    }

    companion object {
        private const val TAG = "WorkProfileSetup"
    }
}
