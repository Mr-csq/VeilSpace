package com.system.launcher.tools.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
import com.system.launcher.tools.data.repository.ProfileAppStore

class WorkProfilePackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        if (packageName == context.packageName) return

        val manager = WorkProfileManager(context)
        if (!manager.isProfileOwner()) return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED -> cacheAndHideIfNeeded(context, manager, packageName)
            Intent.ACTION_PACKAGE_REMOVED -> {
                Log.i(TAG, "Ignoring package removed event for cached/hidden profile package: $packageName")
            }
        }
    }

    fun cacheAndHideIfNeeded(context: Context, manager: WorkProfileManager, packageName: String) {
        if (!manager.isPackageInstalledInProfile(packageName)) {
            markUnverifiedIfCached(context, packageName, "收到包事件，但当前无法确认应用是否已安装在隐藏空间中")
            Log.w(TAG, "Package event could not verify profile install: $packageName")
            return
        }

        val alreadyCached = ProfileAppStore.containsApp(context, packageName)
        if (manager.shouldDeferPackageEventAutoHide(packageName)) {
            if (!alreadyCached) cacheAppMetadata(context, packageName)
            Log.i(TAG, "Package event auto-hide skipped during active launch: $packageName cached=$alreadyCached")
            return
        }

        if (alreadyCached) {
            cacheAppMetadata(context, packageName)
            manager.hideAppInProfileIfAllowed(packageName, "packageReceiverCached")
            Log.i(TAG, "Package already cached, refreshed metadata and applied auto-hide policy: $packageName")
            return
        }
        cacheAndHide(context, manager, packageName)
    }

    fun cacheAndHide(context: Context, manager: WorkProfileManager, packageName: String) {
        val cached = cacheAppMetadata(context, packageName)
        if (cached) {
            manager.hideAppInProfileIfAllowed(packageName, "packageReceiver")
            Log.i(TAG, "Cached and hid profile package: $packageName")
        }
    }

    fun cacheAppMetadata(context: Context, packageName: String): Boolean {
        return try {
            val manager = WorkProfileManager(context)
            val pm = context.packageManager
            val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            if (!manager.isPackageInstalledInProfile(packageName)) {
                markUnverifiedIfCached(context, packageName, "缓存存在，但当前无法确认应用是否仍安装在隐藏空间中")
                return false
            }
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) == 0) {
                markUnverifiedIfCached(context, packageName, "缓存存在，但当前无法确认应用是否仍安装在隐藏空间中")
                Log.w(TAG, "Skip caching metadata for not-installed-or-hidden profile package: $packageName")
                return false
            }
            val launchVerification = manager.resolveLaunchVerificationInProfile(packageName, InstallVerification.CONFIRMED_INSTALLED)
            val launcherComponentNames = manager.getLauncherComponentNamesInProfile(packageName)
            val app = AppInfo(
                packageName = packageName,
                appName = pm.getApplicationLabel(appInfo).toString(),
                icon = pm.getApplicationIcon(appInfo),
                isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                showOnHome = true,
                entrySource = AppEntrySource.DISCOVERED_INSTALLED,
                installVerification = InstallVerification.CONFIRMED_INSTALLED,
                launchVerification = launchVerification,
                launcherComponentNames = launcherComponentNames,
                diagnosticReason = if (launchVerification == LaunchVerification.NOT_LAUNCHABLE) {
                    "未找到可启动入口，可能是系统组件或启动入口被系统限制"
                } else {
                    ""
                }
            )
            ProfileAppStore.upsertApp(context, app)
            Log.i(TAG, "Cached profile package metadata: $packageName")
            true
        } catch (e: NameNotFoundException) {
            markUnverifiedIfCached(context, packageName, "缓存存在，但当前无法确认应用是否仍安装在隐藏空间中")
            Log.w(TAG, "Skip caching metadata for unverified profile package: $packageName")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error caching profile package metadata: $packageName", e)
            false
        }
    }

    private fun markUnverifiedIfCached(context: Context, packageName: String, reason: String) {
        if (!ProfileAppStore.containsApp(context, packageName)) return
        ProfileAppStore.updateVerificationState(
            context = context,
            packageName = packageName,
            installVerification = InstallVerification.UNKNOWN,
            launchVerification = LaunchVerification.UNKNOWN,
            diagnosticReason = reason
        )
    }

    companion object {
        private const val TAG = "WorkProfilePkgReceiver"
    }
}
