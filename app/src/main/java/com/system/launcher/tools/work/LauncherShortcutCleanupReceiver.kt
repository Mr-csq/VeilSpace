package com.system.launcher.tools.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class LauncherShortcutCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WorkProfileManager.ACTION_CLEANUP_LAUNCHER_SHORTCUT) return
        val packageName = intent.getStringExtra(WorkProfileManager.EXTRA_CLEANUP_PACKAGE_NAME) ?: return
        val cleaned = LauncherShortcutCleaner.cleanup(context, packageName)
        Log.i(TAG, "Launcher shortcut cleanup receiver package=$packageName cleaned=$cleaned")
    }

    companion object {
        private const val TAG = "ShortcutCleanupReceiver"
    }
}