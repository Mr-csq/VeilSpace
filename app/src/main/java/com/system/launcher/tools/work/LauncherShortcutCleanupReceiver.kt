package com.system.launcher.tools.work

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.util.Log
import com.system.launcher.tools.data.policy.ProfileAppPolicyTable

class LauncherShortcutCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WorkProfileManager.ACTION_CLEANUP_LAUNCHER_SHORTCUT) return
        val packageName = intent.getStringExtra(WorkProfileManager.EXTRA_CLEANUP_PACKAGE_NAME) ?: return
        val allowGenericUserAppCleanup = intent.getBooleanExtra(
            WorkProfileManager.EXTRA_CLEANUP_ALLOW_GENERIC_USER_APP,
            false
        )
        val labelHints = intent.getStringArrayListExtra(WorkProfileManager.EXTRA_CLEANUP_SHORTCUT_LABELS)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
        val componentHints = intent.getStringArrayListExtra(WorkProfileManager.EXTRA_CLEANUP_SHORTCUT_COMPONENTS)
            ?.mapNotNull(ComponentName::unflattenFromString)
            .orEmpty()
        val profileOwner = (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isProfileOwnerApp(context.packageName)
        val cleanupInstalledPackageShortcuts = profileOwner &&
            (allowGenericUserAppCleanup || ProfileAppPolicyTable.resolve(packageName).removeLauncherShortcutsWhenInstalledInManagedProfile)
        val cleaned = LauncherShortcutCleaner.cleanup(
            context = context,
            packageName = packageName,
            cleanupInstalledPackageShortcuts = cleanupInstalledPackageShortcuts,
            allowGenericUserAppCleanup = allowGenericUserAppCleanup,
            labelHints = labelHints,
            componentHints = componentHints
        )
        Log.i(
            TAG,
            "Launcher shortcut cleanup receiver package=$packageName cleaned=$cleaned generic=$allowGenericUserAppCleanup labels=${labelHints.size} components=${componentHints.size}"
        )
    }

    companion object {
        private const val TAG = "ShortcutCleanupReceiver"
    }
}