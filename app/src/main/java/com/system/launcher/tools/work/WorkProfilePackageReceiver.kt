package com.system.launcher.tools.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Build
import android.util.Log
import com.system.launcher.tools.data.model.AppInfo
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
            ProfileAppStore.updateSystemState(
                context = context,
                packageName = packageName,
                installed = false,
                launchable = false,
                diagnosticReason = "应用当前未安装在隐藏空间中"
            )
            Log.w(TAG, "Marked profile package as not installed after package event: $packageName")
            return
        }
        if (ProfileAppStore.containsApp(context, packageName)) {
            cacheAppMetadata(context, packageName)
            Log.i(TAG, "Package already cached, refreshed metadata and skipped auto-hide: $packageName")
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
            val pm = context.packageManager
            val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
            if (!WorkProfileManager(context).isPackageInstalledInProfile(packageName)) return false
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, flags)
            }
            if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) == 0) {
                ProfileAppStore.updateSystemState(
                    context = context,
                    packageName = packageName,
                    installed = false,
                    launchable = false,
                    diagnosticReason = "应用当前未安装在隐藏空间中"
                )
                Log.w(TAG, "Skip caching metadata for not-installed profile package: $packageName")
                return false
            }
            val launchable = WorkProfileManager(context).canLaunchPackageInProfile(packageName)
            val app = AppInfo(
                packageName = packageName,
                appName = pm.getApplicationLabel(appInfo).toString(),
                icon = pm.getApplicationIcon(appInfo),
                isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                showOnHome = true,
                installed = true,
                launchable = launchable,
                diagnosticReason = if (launchable) "" else "未找到可启动入口，可能是系统组件或启动入口被系统限制"
            )
            ProfileAppStore.upsertApp(context, app)
            Log.i(TAG, "Cached profile package metadata: $packageName")
            true
        } catch (e: NameNotFoundException) {
            ProfileAppStore.updateSystemState(
                context = context,
                packageName = packageName,
                installed = false,
                launchable = false,
                diagnosticReason = "应用当前未安装在隐藏空间中"
            )
            Log.w(TAG, "Skip caching metadata for missing profile package: $packageName")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error caching profile package metadata: $packageName", e)
            false
        }
    }

    companion object {
        private const val TAG = "WorkProfilePkgReceiver"
    }
}





