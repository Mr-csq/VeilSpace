package com.system.launcher.tools.work

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.system.launcher.tools.data.policy.ProfileAppPolicyTable
import com.system.launcher.tools.data.policy.ProfileAppResidualHideAction

object LauncherShortcutCleaner {
    private const val TAG = "LauncherShortcutCleaner"
    private const val MIUI_HOME_PACKAGE = "com.miui.home"
    private const val ACTION_MIUI_UNINSTALL_SHORTCUT = "com.miui.home.launcher.action.UNINSTALL_SHORTCUT"
    private const val ACTION_ANDROID_UNINSTALL_SHORTCUT = "com.android.launcher.action.UNINSTALL_SHORTCUT"

    fun cleanup(context: Context, packageName: String, cleanupInstalledPackageShortcuts: Boolean = false): Boolean {
        val policy = ProfileAppPolicyTable.resolve(packageName)
        if (ProfileAppResidualHideAction.REMOVE_LEGACY_LAUNCHER_SHORTCUTS !in policy.residualHideActions) {
            Log.i(TAG, "Skip shortcut cleanup because policy does not allow it: $packageName")
            return false
        }
        if (isInstalledInThisProfile(context, packageName) &&
            !cleanupInstalledPackageShortcuts &&
            !policy.removeLauncherShortcutsWhenInstalledInPersonalProfile
        ) {
            Log.i(TAG, "Skip shortcut cleanup because package is installed in this profile: $packageName")
            return false
        }

        val labels = policy.residualLauncherShortcutLabels.ifEmpty { setOf(ProfileAppPolicyTable.displayNameFor(packageName)) }
        val components = policy.residualLauncherShortcutComponents.ifEmpty { listOf(ComponentName(packageName, "")) }
        var broadcasts = 0
        labels.forEach { label ->
            components.forEach { component ->
                broadcasts += sendUninstallShortcutBroadcast(context, label, component)
            }
        }
        Log.i(
            TAG,
            "Requested launcher shortcut cleanup package=$packageName labels=${labels.size} components=${components.size} broadcasts=$broadcasts installedCleanup=$cleanupInstalledPackageShortcuts"
        )
        return broadcasts > 0
    }

    private fun sendUninstallShortcutBroadcast(context: Context, label: String, component: ComponentName): Int {
        val shortcutIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setComponent(component)
        }
        val actions = listOf(
            ACTION_MIUI_UNINSTALL_SHORTCUT,
            ACTION_ANDROID_UNINSTALL_SHORTCUT
        )
        actions.forEach { action ->
            Intent(action).apply {
                if (action == ACTION_MIUI_UNINSTALL_SHORTCUT) {
                    setComponent(ComponentName(MIUI_HOME_PACKAGE, "com.miui.home.launcher.UninstallShortcutReceiver"))
                } else {
                    setPackage(MIUI_HOME_PACKAGE)
                }
                putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
            }.also { cleanupIntent ->
                runCatching { context.sendBroadcast(cleanupIntent) }
                    .onFailure { error -> Log.w(TAG, "Shortcut cleanup broadcast failed action=$action label=$label component=$component", error) }
            }
        }
        return actions.size
    }

    private fun isInstalledInThisProfile(context: Context, packageName: String): Boolean {
        return try {
            val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, flags)
            }
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Unable to check package in current profile: $packageName", e)
            false
        }
    }
}