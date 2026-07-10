package com.system.launcher.tools.work

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.CrossProfileApps
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.system.launcher.tools.data.policy.ProfileAppPolicyTable

class LauncherShortcutCleanupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPackageName = intent.getStringExtra(WorkProfileManager.EXTRA_CLEANUP_PACKAGE_NAME)
        val profileOwner = isProfileOwner()
        val cleaned = if (targetPackageName != null) {
            val cleanupInstalledPackageShortcuts = profileOwner &&
                ProfileAppPolicyTable.resolve(targetPackageName).removeLauncherShortcutsWhenInstalledInManagedProfile
            LauncherShortcutCleaner.cleanup(this, targetPackageName, cleanupInstalledPackageShortcuts)
        } else {
            false
        }
        val forwarded = if (profileOwner && targetPackageName != null) {
            forwardToPersonalProfile(targetPackageName)
        } else {
            false
        }
        Log.i(TAG, "Launcher shortcut cleanup activity package=$targetPackageName profileOwner=$profileOwner forwarded=$forwarded cleaned=$cleaned")
        finishWithoutAnimation()
    }

    private fun isProfileOwner(): Boolean {
        return runCatching {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            devicePolicyManager.isProfileOwnerApp(applicationContext.packageName)
        }.getOrDefault(false)
    }

    private fun forwardToPersonalProfile(targetPackageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val crossProfileApps = getSystemService(CrossProfileApps::class.java)
            val currentUser = android.os.Process.myUserHandle()
            val targetUser = crossProfileApps.targetUserProfiles.firstOrNull { it != currentUser }
                ?: return@runCatching false
            val cleanupIntent = Intent(WorkProfileManager.ACTION_CLEANUP_LAUNCHER_SHORTCUT).apply {
                component = ComponentName(
                    applicationContext.packageName,
                    "${applicationContext.packageName}.work.LauncherShortcutCleanupActivity"
                )
                putExtra(WorkProfileManager.EXTRA_CLEANUP_PACKAGE_NAME, targetPackageName)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            crossProfileApps.startActivity(cleanupIntent, targetUser, this)
            Log.i(TAG, "Forwarded launcher shortcut cleanup to personal profile package=$targetPackageName targetUser=$targetUser")
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to forward launcher shortcut cleanup package=$targetPackageName", error)
        }.getOrDefault(false)
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "ShortcutCleanupActivity"
    }
}