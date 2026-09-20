package com.system.launcher.tools.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.system.launcher.tools.ui.migration.WorkProfileMigrationArchive

/**
 * Re-applies Work Profile policies after app updates or device restart.
 */
class WorkProfileSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val manager = WorkProfileManager(context)
        val isOwner = manager.isProfileOwner()

        if (isOwner) {
            WorkProfileMigrationArchive.clearStaleArchive(context)
            manager.configureCrossProfileEntry()
        } else if (manager.hasCrossProfileTarget()) {
            manager.configurePersonalProfileEntry()
        }
    }

    companion object {
    }
}
