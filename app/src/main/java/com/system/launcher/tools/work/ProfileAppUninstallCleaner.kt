package com.system.launcher.tools.work

import android.content.Context
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.policy.ProfileAppUninstallCleanupPolicy
import com.system.launcher.tools.data.repository.ProfileAppPolicyStore
import com.system.launcher.tools.data.repository.ProfileAppStore

object ProfileAppUninstallCleaner {
    fun cleanupConfirmedUninstalledApps(
        context: Context,
        workProfileManager: WorkProfileManager
    ): List<String> {
        if (!workProfileManager.isProfileOwner()) return emptyList()
        val packagesToRemove = ProfileAppStore.loadApps(context)
            .filter { app ->
                ProfileAppUninstallCleanupPolicy.shouldRemove(
                    entrySource = app.entrySource,
                    currentVerification = workProfileManager.resolveInstallVerificationInProfile(app.packageName)
                )
            }
            .map(AppInfo::packageName)

        packagesToRemove.forEach { packageName ->
            removeHiddenSpaceRecord(context, packageName)
        }
        return packagesToRemove
    }

    fun removeHiddenSpaceRecord(context: Context, packageName: String): List<AppInfo> {
        ProfileAppPolicyStore.removePackage(context, packageName)
        return ProfileAppStore.removeApp(context, packageName)
    }
}
