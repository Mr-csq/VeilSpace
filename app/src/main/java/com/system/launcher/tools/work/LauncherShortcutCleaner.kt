package com.system.launcher.tools.work

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.system.launcher.tools.data.policy.ProfileAppPolicyTable
import com.system.launcher.tools.data.policy.ProfileAppResidualHideAction

object LauncherShortcutCleaner {
    private const val TAG = "LauncherShortcutCleaner"
    private const val MIUI_HOME_PACKAGE = "com.miui.home"
    private const val ACTION_MIUI_UNINSTALL_SHORTCUT = "com.miui.home.launcher.action.UNINSTALL_SHORTCUT"
    private const val ACTION_ANDROID_UNINSTALL_SHORTCUT = "com.android.launcher.action.UNINSTALL_SHORTCUT"

    data class ShortcutHints(
        val labels: Set<String>,
        val components: List<ComponentName>
    )

    fun cleanup(
        context: Context,
        packageName: String,
        cleanupInstalledPackageShortcuts: Boolean = false,
        allowGenericUserAppCleanup: Boolean = false,
        labelHints: Set<String> = emptySet(),
        componentHints: List<ComponentName> = emptyList()
    ): Boolean {
        val policy = ProfileAppPolicyTable.resolve(packageName)
        val policyAllowsCleanup = ProfileAppResidualHideAction.REMOVE_LEGACY_LAUNCHER_SHORTCUTS in policy.residualHideActions
        if (!policyAllowsCleanup && !allowGenericUserAppCleanup) {
            Log.i(TAG, "Skip shortcut cleanup because policy does not allow it: $packageName")
            return false
        }
        val installedInThisProfile = isInstalledInThisProfile(context, packageName)
        val workProfileShortcutOnlyCleanup = installedInThisProfile &&
            allowGenericUserAppCleanup &&
            !cleanupInstalledPackageShortcuts &&
            !policy.removeLauncherShortcutsWhenInstalledInPersonalProfile
        if (installedInThisProfile &&
            !cleanupInstalledPackageShortcuts &&
            !policy.removeLauncherShortcutsWhenInstalledInPersonalProfile &&
            !workProfileShortcutOnlyCleanup
        ) {
            Log.i(TAG, "Skip shortcut cleanup because package is installed in this profile: $packageName generic=$allowGenericUserAppCleanup")
            return false
        }

        val hints = resolveShortcutHints(context, packageName, labelHints, componentHints)
        val baseLabels = hints.labels.ifEmpty { setOf(ProfileAppPolicyTable.displayNameFor(packageName)) }
        val labels = if (workProfileShortcutOnlyCleanup) {
            ProfileAppPolicyTable.genericWorkProfileShortcutLabels(baseLabels)
        } else if (allowGenericUserAppCleanup) {
            ProfileAppPolicyTable.genericUserAppShortcutLabels(baseLabels)
        } else {
            baseLabels
        }
        if (labels.isEmpty()) {
            Log.i(TAG, "Skip shortcut cleanup because no labels resolved: $packageName generic=$allowGenericUserAppCleanup")
            return false
        }
        val components = hints.components.ifEmpty { listOf(ComponentName(packageName, "")) }
        var broadcasts = 0
        labels.forEach { label ->
            components.forEach { component ->
                broadcasts += sendUninstallShortcutBroadcast(context, label, component)
            }
        }
        Log.i(
            TAG,
            "Requested launcher shortcut cleanup package=$packageName labels=${labels.size} components=${components.size} broadcasts=$broadcasts installedCleanup=$cleanupInstalledPackageShortcuts generic=$allowGenericUserAppCleanup workOnly=$workProfileShortcutOnlyCleanup"
        )
        return broadcasts > 0
    }

    fun resolveShortcutHints(
        context: Context,
        packageName: String,
        additionalLabels: Set<String> = emptySet(),
        additionalComponents: List<ComponentName> = emptyList()
    ): ShortcutHints {
        val policy = ProfileAppPolicyTable.resolve(packageName)
        val labels = linkedSetOf<String>()
        policy.residualLauncherShortcutLabels.filterTo(labels) { it.isNotBlank() }
        additionalLabels.filterTo(labels) { it.isNotBlank() }
        resolveApplicationLabel(context, packageName)?.takeIf { it.isNotBlank() }?.let(labels::add)

        val components = mutableListOf<ComponentName>()
        policy.residualLauncherShortcutComponents.forEach { addDistinctComponent(components, it) }
        additionalComponents.forEach { addDistinctComponent(components, it) }
        resolveLauncherAppsComponents(context, packageName).forEach { addDistinctComponent(components, it) }
        resolvePackageManagerLauncherComponents(context, packageName).forEach { addDistinctComponent(components, it) }
        context.packageManager.getLaunchIntentForPackage(packageName)?.component?.let { addDistinctComponent(components, it) }

        return ShortcutHints(labels = labels, components = components)
    }

    private fun resolveApplicationLabel(context: Context, packageName: String): String? {
        return runCatching {
            val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES
            val appInfo = context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.onFailure { error ->
            Log.i(TAG, "Unable to resolve shortcut label for package=$packageName error=${error.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun resolveLauncherAppsComponents(context: Context, packageName: String): List<ComponentName> {
        return runCatching {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.getActivityList(packageName, android.os.Process.myUserHandle())
                .map { it.componentName }
        }.onFailure { error ->
            Log.i(TAG, "Unable to resolve LauncherApps components for package=$packageName error=${error.javaClass.simpleName}")
        }.getOrDefault(emptyList())
    }

    private fun resolvePackageManagerLauncherComponents(context: Context, packageName: String): List<ComponentName> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val flags = PackageManager.MATCH_DEFAULT_ONLY or
            PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        return queryIntentActivities(context.packageManager, intent, flags).mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            ComponentName(activityInfo.packageName, activityInfo.name)
        }
    }

    private fun queryIntentActivities(packageManager: PackageManager, intent: Intent, flags: Int): List<ResolveInfo> {
        return packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    }

    private fun addDistinctComponent(components: MutableList<ComponentName>, component: ComponentName) {
        if (component.packageName.isBlank()) return
        val key = component.flattenToString()
        if (components.none { it.flattenToString() == key }) components += component
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
            val appInfo = context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong()))
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Unable to check package in current profile: $packageName", e)
            false
        }
    }
}