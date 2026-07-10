package com.system.launcher.tools.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.util.Log
import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
import com.system.launcher.tools.data.policy.ProfileAppPolicyTable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用管理仓库
 */
@Singleton
class AppRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val packageManager: PackageManager = context.packageManager
    private val launcherApps: LauncherApps by lazy {
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    }

    companion object {
        private const val TAG = "AppRepository"
    }

    private fun getCurrentUserInstalledApps(): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()

        try {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            for (appInfo in installedApps) {
                if (!appInfo.isSystemApplication()) {
                    try {
                        val appName = packageManager.getApplicationLabel(appInfo).toString()
                        val icon = packageManager.getApplicationIcon(appInfo)

                        apps.add(
                            AppInfo(
                                packageName = appInfo.packageName,
                                appName = appName,
                                icon = icon,
                                isSystemApp = false,
                                entrySource = AppEntrySource.DISCOVERED_INSTALLED,
                                installVerification = InstallVerification.CONFIRMED_INSTALLED,
                                launchVerification = LaunchVerification.LAUNCHABLE
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading app: ${appInfo.packageName}", e)
                    }
                }
            }

            apps.sortBy { it.appName }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed apps", e)
        }

        return apps
    }

    private fun getLaunchableApps(
        userHandle: UserHandle,
        includeSystemApps: Boolean
    ): List<AppInfo> {
        return try {
            launcherApps.getActivityList(null, userHandle)
                .asSequence()
                .distinctBy { it.applicationInfo.packageName }
                .filter { includeSystemApps || !it.applicationInfo.isSystemApplication() }
                .map { launcherInfo ->
                    val appInfo = launcherInfo.applicationInfo
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = launcherInfo.label?.toString()
                            ?: packageManager.getApplicationLabel(appInfo).toString(),
                        icon = launcherInfo.getIcon(0),
                        isSystemApp = appInfo.isSystemApplication(),
                        entrySource = AppEntrySource.DISCOVERED_INSTALLED,
                        installVerification = InstallVerification.CONFIRMED_INSTALLED,
                        launchVerification = LaunchVerification.LAUNCHABLE
                    )
                }
                .sortedBy { it.appName }
                .toList()
                .also { apps ->
                    Log.i(TAG, "Loaded ${apps.size} launchable apps from user=$userHandle includeSystem=$includeSystemApps")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting launchable apps for user=$userHandle", e)
            emptyList()
        }
    }

    fun getInstalledProfileApps(userHandle: UserHandle? = null): List<AppInfo> {
        val profileUser = userHandle ?: android.os.Process.myUserHandle()
        val launchableApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getLaunchableApps(
                userHandle = profileUser,
                includeSystemApps = true
            )
        } else {
            getCurrentUserInstalledApps()
        }
        val appsByPackage = launchableApps.associateBy { it.packageName }.toMutableMap()

        try {
            val includeHiddenFlags = PackageManager.GET_META_DATA or
                PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES

            packageManager.getInstalledApplications(includeHiddenFlags)
                .filter { appInfo ->
                    appInfo.packageName != context.packageName &&
                        (appInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0 &&
                        hasLauncherActivity(appInfo.packageName, includeHiddenFlags)
                }
                .forEach { appInfo ->
                    val launcherInfo = resolveLauncherActivity(appInfo.packageName, includeHiddenFlags)
                    appsByPackage[appInfo.packageName] = AppInfo(
                        packageName = appInfo.packageName,
                        appName = launcherInfo?.loadLabel(packageManager)?.toString()
                            ?: packageManager.getApplicationLabel(appInfo).toString(),
                        icon = launcherInfo?.loadIcon(packageManager)
                            ?: packageManager.getApplicationIcon(appInfo),
                        isSystemApp = appInfo.isSystemApplication(),
                        entrySource = AppEntrySource.DISCOVERED_INSTALLED,
                        installVerification = InstallVerification.CONFIRMED_INSTALLED,
                        launchVerification = LaunchVerification.LAUNCHABLE
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed profile apps", e)
        }

        return appsByPackage.values.sortedBy { it.appName }
            .also { apps -> Log.i(TAG, "Loaded ${apps.size} installed profile apps including hidden candidates, packages=${apps.joinToString { it.packageName }}") }
    }

    fun getSystemCandidateApps(): List<AppInfo> {
        val flags = PackageManager.GET_META_DATA or
            PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        return ProfileAppPolicyTable.systemCandidatePackages().map { packageName ->
            val policy = ProfileAppPolicyTable.resolve(packageName)
            val appInfo = getApplicationInfoOrNull(packageName, flags)
            val installed = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_INSTALLED) != 0
            val launcherInfo = resolveLauncherActivity(packageName, flags)
            val launchVerification = when {
                installed && launcherInfo != null -> LaunchVerification.LAUNCHABLE
                policy.knownLaunchTool -> LaunchVerification.POLICY_LAUNCH_ONLY
                installed -> LaunchVerification.UNKNOWN
                else -> LaunchVerification.UNKNOWN
            }
            AppInfo(
                packageName = packageName,
                appName = policy.displayName
                    ?: launcherInfo?.loadLabel(packageManager)?.toString()
                    ?: appInfo?.let { packageManager.getApplicationLabel(it).toString() }
                    ?: ProfileAppPolicyTable.displayNameFor(packageName),
                icon = launcherInfo?.loadIcon(packageManager)
                    ?: appInfo?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() },
                isSystemApp = true,
                showOnHome = true,
                entrySource = AppEntrySource.SYSTEM_CANDIDATE,
                installVerification = if (installed) InstallVerification.CONFIRMED_INSTALLED else InstallVerification.UNKNOWN,
                launchVerification = launchVerification,
                diagnosticReason = if (installed) "" else "系统候选入口，当前无法确认是否已安装在隐藏空间中"
            )
        }.sortedBy { it.appName }
    }

    private fun getApplicationInfoOrNull(packageName: String, flags: Int): ApplicationInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, flags)
            }
        }.getOrNull()
    }

    private fun hasLauncherActivity(packageName: String, flags: Int): Boolean {
        return resolveLauncherActivity(packageName, flags) != null
    }

    private fun resolveLauncherActivity(packageName: String, flags: Int) = try {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        packageManager.queryIntentActivities(intent, flags).firstOrNull()
    } catch (e: Exception) {
        Log.e(TAG, "Error resolving launcher activity for $packageName", e)
        null
    }

    private fun ApplicationInfo.isSystemApplication(): Boolean {
        return (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }
}
