package com.system.launcher.tools.work

import android.app.Activity
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.CrossProfileApps
import android.content.pm.LauncherApps
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.PackageManager.DONT_KILL_APP
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.repository.ProfileAppPolicyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkProfileManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val userManager: UserManager by lazy {
        context.getSystemService(Context.USER_SERVICE) as UserManager
    }
    private val packageManager: PackageManager by lazy { context.packageManager }
    private val launcherApps: LauncherApps by lazy {
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    }
    private val crossProfileApps: CrossProfileApps by lazy {
        context.getSystemService(CrossProfileApps::class.java)
    }
    private val sharedPrefs by lazy {
        context.getSharedPreferences("work_profile_prefs", Context.MODE_PRIVATE)
    }
    private val mainPrefs by lazy {
        context.getSharedPreferences("work_profile_main_prefs", Context.MODE_PRIVATE)
    }
    private val foregroundMonitorHandler by lazy { Handler(Looper.getMainLooper()) }

    companion object {
        private const val TAG = "WorkProfileManager"
        const val REQUEST_CODE_PROVISION_PROFILE = 1001
        const val REQUEST_CODE_ENABLE_ADMIN = 1002
        const val ACTION_OPEN_PRIVACY_SPACE = "com.system.launcher.tools.action.OPEN_PRIVACY_SPACE"
        const val ACTION_OPEN_REAL_GAME_CENTER = "com.system.launcher.tools.action.OPEN_REAL_GAME_CENTER"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val GOOGLE_PLAY_PACKAGE = "com.android.vending"
        private val GOOGLE_CORE_SERVICE_PACKAGES = setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.configupdater",
            "com.google.android.partnersetup",
            "com.google.android.syncadapters.contacts",
            "com.google.android.onetimeinitializer"
        )
        private val FILE_MANAGER_PACKAGES = setOf(
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.android.fileexplorer"
        )
        private val SPECIAL_LAUNCH_COMPONENTS = mapOf(
            "com.xiaomi.market" to listOf(
                ComponentName("com.xiaomi.market", "com.xiaomi.market.ui.DefaultLauncherIcon"),
                ComponentName("com.xiaomi.market", "com.xiaomi.market.ui.MarketTabActivity")
            ),
            "com.miui.securitycenter" to listOf(
                ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainEntryActivity"),
                ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainActivity")
            )
        )
        private const val ACTIVE_LAUNCH_PACKAGE = "active_launch_package"
        private const val ACTIVE_LAUNCH_STARTED_AT = "active_launch_started_at"
        private const val FOREGROUND_MONITOR_POLL_MS = 300L
        private const val FOREGROUND_LEAVE_DEBOUNCE_MS = 300L
        private const val GOOGLE_PLAY_MIN_FOREGROUND_MS_BEFORE_HIDE = 10_000L
        private const val FOREGROUND_MONITOR_TIMEOUT_MS = 60_000L
        private val INSTALL_SUPPORT_PACKAGES = setOf(
            "com.miui.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.lbe.security.miui",
            "com.miui.securitycenter",
            "com.miui.securityadd",
            "com.miui.guardprovider"
        )
        private val NEVER_AUTO_HIDE_PACKAGES = setOf(
            "com.miui.securitycenter",
            "com.miui.securityadd",
            "com.miui.securitymanager",
            "com.lbe.security.miui",
            "com.miui.guardprovider",
            "com.miui.packageinstaller",
            "com.android.permissioncontroller"
        )
    }

    private data class ForegroundSnapshot(
        val packageName: String?,
        val targetMovedToBackground: Boolean
    )

    private fun getAdminComponent(): ComponentName {
        return ComponentName(context, WorkProfileAdminReceiver::class.java)
    }

    fun checkIfProfileExists(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false

            val currentUser = android.os.Process.myUserHandle()
            val launcherProfiles = launcherApps.profiles
            val otherLauncherProfiles = launcherProfiles.filter { it != currentUser }
            Log.i(TAG, "checkIfProfileExists launcherApps: current=$currentUser, total=${launcherProfiles.size}, otherProfiles=${otherLauncherProfiles.size}")
            if (otherLauncherProfiles.isNotEmpty()) return true

            val userProfiles = userManager.userProfiles
            val otherUserProfiles = userProfiles.filter { it != currentUser }
            Log.i(TAG, "checkIfProfileExists userManager: current=$currentUser, total=${userProfiles.size}, otherProfiles=${otherUserProfiles.size}")
            otherUserProfiles.isNotEmpty() || isProfileOwner()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking profile existence", e)
            false
        }
    }

    fun configureCrossProfileEntry(): Boolean {
        return try {
            if (!isProfileOwner()) {
                Log.w(TAG, "Not Profile Owner, cannot configure cross-profile entry")
                return false
            }

            val admin = getAdminComponent()
            enableManagedProfile(admin)
            configureProfileInstallPolicy(admin)
            showPrivacyActionAliasInProfile()
            showPrivacySpaceLauncherAliasInProfile()
            hideGameCenterLauncherAliasInProfile()
            hideGameCenterProxyInProfile()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                devicePolicyManager.setCrossProfilePackages(admin, setOf(context.packageName))
                Log.i(TAG, "Allowed cross-profile package: ${context.packageName}")
            }
            devicePolicyManager.clearCrossProfileIntentFilters(admin)
            devicePolicyManager.addCrossProfileIntentFilter(
                admin,
                IntentFilter(ACTION_OPEN_PRIVACY_SPACE).apply { addCategory(Intent.CATEGORY_DEFAULT) },
                DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED
            )
            devicePolicyManager.addCrossProfileIntentFilter(
                admin,
                IntentFilter(ACTION_OPEN_REAL_GAME_CENTER).apply { addCategory(Intent.CATEGORY_DEFAULT) },
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
            )
            devicePolicyManager.addCrossProfileIntentFilter(
                admin,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
            )
            Log.i(TAG, "Configured cross-profile privacy entry")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring cross-profile entry", e)
            false
        }
    }

    fun enableManagedProfile(): Boolean {
        return if (isProfileOwner()) enableManagedProfile(getAdminComponent()) else false
    }

    private fun enableManagedProfile(admin: ComponentName): Boolean {
        return try {
            devicePolicyManager.setProfileName(admin, "系统工具")
            devicePolicyManager.setProfileEnabled(admin)
            Log.i(TAG, "Managed profile enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling managed profile", e)
            false
        }
    }

    private fun configureProfileInstallPolicy(admin: ComponentName) {
        clearProfileInstallRestrictions(admin)
        Log.i(TAG, "Configured managed profile install policy")
    }

    private fun clearProfileInstallRestrictions(admin: ComponentName) {
        val restrictions = mutableListOf(
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            restrictions.add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        }

        restrictions.forEach { restriction ->
            runCatching {
                devicePolicyManager.clearUserRestriction(admin, restriction)
            }.onFailure { error ->
                Log.w(TAG, "Unable to clear user restriction: $restriction", error)
            }
        }
    }

    fun canUseWorkProfileFeatures(): Boolean {
        val markedReady = isMainProfileMarkedReady()
        val profileExists = checkIfProfileExists()
        val result = markedReady || profileExists
        Log.i(TAG, "canUseWorkProfileFeatures: markedReady=$markedReady, profileExists=$profileExists, result=$result")
        return result
    }

    fun markMainProfileReady() {
        val success = mainPrefs.edit().putBoolean("work_profile_ready", true).commit()
        Log.i(TAG, "Main profile marked as ready, commitSuccess=$success")
    }

    fun clearMainProfileReady() {
        val success = mainPrefs.edit().putBoolean("work_profile_ready", false).commit()
        Log.i(TAG, "Main profile ready flag cleared, commitSuccess=$success")
    }

    fun isMainProfileMarkedReady(): Boolean {
        val value = mainPrefs.getBoolean("work_profile_ready", false)
        Log.i(TAG, "isMainProfileMarkedReady: $value")
        return value
    }

    fun isProfileOwner(): Boolean {
        return try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                devicePolicyManager.isProfileOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking profile owner status", e)
            false
        }
    }

    fun getOtherProfileUserHandle(): UserHandle? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val currentUser = android.os.Process.myUserHandle()
                launcherApps.profiles.firstOrNull { it != currentUser }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting other profile user handle", e)
            null
        }
    }

    fun getPersonalProfileUserHandle(): UserHandle? = getOtherProfileUserHandle()

    fun requestManagedProfileAvailable(userHandle: UserHandle? = getOtherProfileUserHandle()): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || userHandle == null) return false
            val currentlyQuiet = runCatching { userManager.isQuietModeEnabled(userHandle) }.getOrDefault(false)
            Log.i(TAG, "requestManagedProfileAvailable user=$userHandle quiet=$currentlyQuiet")
            if (currentlyQuiet) userManager.requestQuietModeEnabled(false, userHandle)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting managed profile availability", e)
            false
        }
    }

    fun getPrivacyEntryComponent(): ComponentName {
        return ComponentName(context.packageName, "${context.packageName}.WorkProfileGameCenterAlias")
    }

    fun getPrivacyActionComponent(): ComponentName {
        return ComponentName(context.packageName, "${context.packageName}.PrivacyActionAlias")
    }

    private fun getGameCenterEntryComponent(): ComponentName {
        return ComponentName(context.packageName, "${context.packageName}.GameCenterAlias")
    }

    private fun getGameCenterProxyComponent(): ComponentName {
        return ComponentName(context.packageName, "${context.packageName}.ui.disguise.GameCenterProxyActivity")
    }

    fun redirectToManagedProfile(
        activity: Activity,
        targetActivity: Class<out Activity>
    ): Boolean {
        return try {
            if (isProfileOwner()) return false
            val currentUser = android.os.Process.myUserHandle()
            val targetUsers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { crossProfileApps.targetUserProfiles }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            Log.i(TAG, "redirectToManagedProfile currentUser=$currentUser targetUsers=$targetUsers launcherProfiles=${launcherApps.profiles}")
            val workUser = targetUsers.firstOrNull { it != currentUser } ?: getOtherProfileUserHandle()
            if (workUser == null) {
                Log.w(TAG, "No managed profile target available for redirect")
                return false
            }
            requestManagedProfileAvailable(workUser)
            val component = if (targetActivity.name == "${context.packageName}.MainActivity") {
                getPrivacyEntryComponent()
            } else {
                ComponentName(context, targetActivity)
            }
            if (targetActivity.name == "${context.packageName}.MainActivity" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val explicitIntent = Intent(ACTION_OPEN_PRIVACY_SPACE).apply {
                    setComponent(ComponentName(context.packageName, "${context.packageName}.MainActivity"))
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                runCatching {
                    activity.startActivity(Intent(ACTION_OPEN_PRIVACY_SPACE).apply {
                        setPackage(context.packageName)
                        addCategory(Intent.CATEGORY_DEFAULT)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }.getOrElse { implicitError ->
                    Log.w(TAG, "Implicit cross-profile action start failed, trying CrossProfileApps action", implicitError)
                    runCatching {
                        crossProfileApps.startActivity(explicitIntent, workUser, activity)
                    }.getOrElse { explicitError ->
                        Log.w(TAG, "Explicit cross-profile start failed, falling back to launcher alias", explicitError)
                        crossProfileApps.startMainActivity(component, workUser)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                crossProfileApps.startMainActivity(component, workUser)
            } else {
                launcherApps.startMainActivity(component, workUser, null, null)
            }
            activity.finish()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error redirecting to managed profile", e)
            false
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
    }

    fun createUnknownAppSourcesIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            null
        }
    }

    fun createApkInstallIntent(apkUri: Uri): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun repairProfileInstallEnvironment(): Boolean {
        if (!isProfileOwner()) return false
        val admin = getAdminComponent()
        clearProfileInstallRestrictions(admin)
        runCatching {
            devicePolicyManager.setSecureSetting(admin, "install_non_market_apps", "1")
        }.onFailure { error ->
            Log.w(TAG, "Unable to set install_non_market_apps secure setting", error)
        }

        var changed = false
        INSTALL_SUPPORT_PACKAGES.forEach { packageName ->
            runCatching {
                if (!isPackageInstalledInProfile(packageName)) {
                    enableAppInProfile(packageName)
                }
                if (isPackageInstalledInProfile(packageName)) {
                    changed = unhideAppInProfile(packageName) || changed
                }
            }.onFailure { error ->
                Log.w(TAG, "Unable to repair install support package: $packageName", error)
            }
        }
        Log.i(TAG, "Repaired profile install environment changed=$changed")
        return true
    }

    fun becomeProfileOwner(activity: Activity): Boolean {
        return if (isProfileOwner()) true else createProfile(activity)
    }

    fun createProfile(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            if (checkIfProfileExists()) {
                Log.w(TAG, "Work Profile already exists")
                false
            } else if (!devicePolicyManager.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
                Log.e(TAG, "Provisioning not allowed on this device")
                false
            } else {
                activity.startActivityForResult(createProvisioningIntent(), REQUEST_CODE_PROVISION_PROFILE)
                Log.i(TAG, "Started Work Profile provisioning")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating profile", e)
            false
        }
    }

    private fun createProvisioningIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, getAdminComponent())
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME, context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_USER_CONSENT, true)
            }
        }
    }

    fun removeProfile() {
        try {
            if (isProfileOwner()) {
                devicePolicyManager.wipeData(0)
                sharedPrefs.edit().clear().apply()
                clearMainProfileReady()
                Log.i(TAG, "Work Profile removed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing profile", e)
        }
    }

    fun isProvisioningComplete(): Boolean {
        return sharedPrefs.getBoolean("provisioning_complete", false)
    }

    fun enableAppInProfile(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                Log.w(TAG, "installExistingPackage requires Android 9.0+")
                return false
            }
            if (!isProfileOwner()) {
                Log.w(TAG, "Not Profile Owner, cannot install app into profile")
                return false
            }
            val installed = devicePolicyManager.installExistingPackage(getAdminComponent(), packageName)
            Log.i(TAG, "installExistingPackage result for $packageName = $installed")
            installed
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException enabling app: $packageName", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling app: $packageName", e)
            false
        }
    }

    fun enableSystemAppInProfile(packageName: String): Boolean {
        return try {
            if (!isProfileOwner()) {
                Log.w(TAG, "Not Profile Owner, cannot enable system app in profile")
                return false
            }
            devicePolicyManager.enableSystemApp(getAdminComponent(), packageName)
            Log.i(TAG, "Enabled system app in profile: $packageName")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException enabling system app: $packageName", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling system app: $packageName", e)
            false
        }
    }
    fun hidePrivacySpaceLauncherAliasInProfile(): Boolean {
        return setPrivacySpaceLauncherAliasEnabled(false)
    }

    fun showPrivacySpaceLauncherAliasInProfile(): Boolean {
        return setPrivacySpaceLauncherAliasEnabled(true)
    }

    fun hidePrivacyActionAliasInProfile(): Boolean {
        return setPrivacyActionAliasEnabled(false)
    }

    fun showPrivacyActionAliasInProfile(): Boolean {
        return setPrivacyActionAliasEnabled(true)
    }

    fun hideGameCenterLauncherAliasInProfile(): Boolean {
        return setGameCenterLauncherAliasEnabled(false)
    }

    fun hideGameCenterProxyInProfile(): Boolean {
        return setGameCenterProxyEnabled(false)
    }

    private fun setPrivacySpaceLauncherAliasEnabled(enabled: Boolean): Boolean {
        return try {
            val state = if (enabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(getPrivacyEntryComponent(), state, DONT_KILL_APP)
            Log.i(TAG, "Set privacy launcher alias enabled=$enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting privacy launcher alias enabled=$enabled", e)
            false
        }
    }
    private fun setPrivacyActionAliasEnabled(enabled: Boolean): Boolean {
        return try {
            val state = if (enabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(getPrivacyActionComponent(), state, DONT_KILL_APP)
            Log.i(TAG, "Set privacy action alias enabled=$enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting privacy action alias enabled=$enabled", e)
            false
        }
    }

    private fun setGameCenterLauncherAliasEnabled(enabled: Boolean): Boolean {
        return try {
            val state = if (enabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(getGameCenterEntryComponent(), state, DONT_KILL_APP)
            Log.i(TAG, "Set game center launcher alias enabled=$enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting game center launcher alias enabled=$enabled", e)
            false
        }
    }

    private fun setGameCenterProxyEnabled(enabled: Boolean): Boolean {
        return try {
            val state = if (enabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(getGameCenterProxyComponent(), state, DONT_KILL_APP)
            Log.i(TAG, "Set game center proxy enabled=$enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting game center proxy enabled=$enabled", e)
            false
        }
    }

    fun hideAppInProfile(packageName: String): Boolean {
        return setAppHiddenInProfile(packageName, true)
    }

    fun hideAppInProfileIfAllowed(packageName: String, reason: String): Boolean {
        if (packageName in NEVER_AUTO_HIDE_PACKAGES) {
            Log.i(TAG, "Skip hiding non-hideable system tool reason=$reason package=$packageName")
            return false
        }
        if (ProfileAppPolicyStore.isKeepAliveApp(context, packageName)) {
            Log.i(TAG, "Skip hiding keep-alive app reason=$reason package=$packageName")
            return false
        }
        if (packageName in GOOGLE_CORE_SERVICE_PACKAGES) {
            Log.i(TAG, "Skip hiding Google core service reason=$reason package=$packageName")
            return false
        }
        return hideAppInProfile(packageName)
    }

    fun shouldNeverAutoHide(packageName: String): Boolean {
        return packageName in NEVER_AUTO_HIDE_PACKAGES
    }

    private fun unhideNeverAutoHideDependencies() {
        if (!isProfileOwner()) return
        NEVER_AUTO_HIDE_PACKAGES.forEach { packageName ->
            if (!isPackageInstalledInProfile(packageName)) {
                enableAppInProfile(packageName)
            }
            if (isPackageInstalledInProfile(packageName)) {
                unhideAppInProfile(packageName)
            }
        }
    }

    fun unhideAppInProfile(packageName: String): Boolean {
        return setAppHiddenInProfile(packageName, false)
    }

    private fun unhideGooglePlayRuntimeDependencies() {
        if (!isProfileOwner()) return
        GOOGLE_CORE_SERVICE_PACKAGES.forEach { packageName ->
            if (!isPackageInstalledInProfile(packageName)) {
                enableAppInProfile(packageName)
            }
            if (isPackageInstalledInProfile(packageName)) {
                unhideAppInProfile(packageName)
            } else {
                Log.w(TAG, "Google Play dependency is not installed in profile: $packageName")
            }
        }
    }

    fun isPackageInstalledInProfile(packageName: String): Boolean {
        return try {
            val appInfo = getProfileApplicationInfo(packageName) ?: return false
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking installed package in profile: $packageName", e)
            false
        }
    }

    private fun isUserInstalledPackageInProfile(packageName: String): Boolean {
        return try {
            val appInfo = getProfileApplicationInfo(packageName) ?: return false
            val installed = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
            val systemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            installed && !systemApp
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking user installed package in profile: $packageName", e)
            false
        }
    }

    private fun getProfileApplicationInfo(packageName: String): android.content.pm.ApplicationInfo? {
        val flags = PackageManager.GET_META_DATA or
            PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, flags)
        }
    }
    private fun setAppHiddenInProfile(packageName: String, hidden: Boolean): Boolean {
        return try {
            if (!isProfileOwner()) return false
            if (packageName == context.packageName) {
                Log.i(TAG, "Skip hiding own package to keep cross-profile entry available")
                return false
            }
            devicePolicyManager.setApplicationHidden(getAdminComponent(), packageName, hidden)
            Log.i(TAG, "Set app hidden=$hidden in profile: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting app hidden=$hidden: $packageName", e)
            false
        }
    }
    fun getLaunchIntent(packageName: String): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val launcherInfo = launcherApps.getActivityList(packageName, android.os.Process.myUserHandle()).firstOrNull()
                if (launcherInfo != null) Intent().apply { component = launcherInfo.componentName }
                else packageManager.getLaunchIntentForPackage(packageName)
            } else {
                packageManager.getLaunchIntentForPackage(packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting launch intent for package: $packageName", e)
            null
        }
    }

    private fun getPrimaryLauncherActivityInfo(packageName: String): LauncherActivityInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                launcherApps.getActivityList(packageName, android.os.Process.myUserHandle()).firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting launcher activity for package: $packageName", e)
            null
        }
    }

    fun isFileManagerPackage(packageName: String): Boolean {
        return packageName in FILE_MANAGER_PACKAGES
    }

    fun hasLauncherActivityInProfile(packageName: String): Boolean {
        return try {
            val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_DISABLED_COMPONENTS
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            packageManager.queryIntentActivities(intent, flags).isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving launcher activity in profile: $packageName", e)
            false
        }
    }

    fun canOpenFileManagerInProfile(packageName: String): Boolean {
        return try {
            hasLauncherActivityInProfile(packageName) || createFileManagerRecentsIntents(packageName).any { intent ->
                intent.resolveActivity(packageManager) != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving file manager entry in profile: $packageName", e)
            false
        }
    }

    fun canLaunchPackageInProfile(packageName: String): Boolean {
        return try {
            isUserInstalledPackageInProfile(packageName) ||
                isKnownProfileLaunchTool(packageName) ||
                hasLauncherActivityInProfile(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking launchable profile package: $packageName", e)
            false
        }
    }

    private fun isKnownProfileLaunchTool(packageName: String): Boolean {
        return packageName == GOOGLE_PLAY_PACKAGE ||
            packageName == "com.android.settings" ||
            isFileManagerPackage(packageName) ||
            packageName in SPECIAL_LAUNCH_COMPONENTS
    }
    private fun startLaunchableApp(packageName: String): Boolean {
        return when {
            isFileManagerPackage(packageName) -> startFileManagerAtRecents(packageName)
            packageName == "com.miui.securitycenter" -> startSpecialLaunchUri(packageName) || startSpecialLaunchComponent(packageName) || startLauncherActivity(packageName)
            else -> startLauncherActivity(packageName)
        }
    }

    private fun startLauncherActivity(packageName: String): Boolean {
        return try {
            val launcherInfo = getPrimaryLauncherActivityInfo(packageName)
            if (launcherInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                launcherApps.startMainActivity(
                    launcherInfo.componentName,
                    android.os.Process.myUserHandle(),
                    null,
                    null
                )
                Log.i(TAG, "Started app via LauncherApps: $packageName component=${launcherInfo.componentName}")
                true
            } else if (startSpecialLaunchComponent(packageName)) {
                true
            } else {
                val intent = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent != null) {
                    context.startActivity(intent)
                    Log.i(TAG, "Started app via launch intent fallback: $packageName")
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting launchable app: $packageName", e)
            false
        }
    }

    private fun startSpecialLaunchComponent(packageName: String): Boolean {
        SPECIAL_LAUNCH_COMPONENTS[packageName].orEmpty().forEach { component ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    launcherApps.startMainActivity(
                        component,
                        android.os.Process.myUserHandle(),
                        null,
                        null
                    )
                    Log.i(TAG, "Started app via special LauncherApps component: $packageName component=$component")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Special LauncherApps component failed: $packageName component=$component", e)
                }
            }

            try {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setComponent(component)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                Log.i(TAG, "Started app via special launch component: $packageName component=$component")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Special launch component failed: $packageName component=$component", e)
            }
        }

        return startSpecialLaunchUri(packageName)
    }

    private fun startSpecialLaunchUri(packageName: String): Boolean {
        val intents = when (packageName) {
            "com.xiaomi.market" -> listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("mimarket://home")).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_VIEW, Uri.parse("mimarket://home")).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_VIEW, Uri.parse("mimarket://browse")).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_VIEW, Uri.parse("market://launchordetail")).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            "com.miui.securitycenter" -> listOf(
                Intent("miui.intent.action.APP_MANAGER").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent("miui.intent.action.APP_MANAGER").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent("miui.intent.action.APP_SETTINGS").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent("miui.intent.action.SECURITY_CENTER_SETTINGS").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_VIEW, Uri.parse("securitycenter://home/mainActivity")).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent(Intent.ACTION_VIEW, Uri.parse("securitycenter://home/mainActivity")).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            else -> emptyList()
        }

        intents.forEach { intent ->
            try {
                context.startActivity(intent)
                Log.i(TAG, "Started app via special launch uri: $packageName uri=${intent.data}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Special launch uri failed: $packageName uri=${intent.data}", e)
            }
        }
        return false
    }

    private fun createFileManagerRecentsIntents(packageName: String): List<Intent> {
        return listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        )
    }
    private fun startFileManagerAtRecents(packageName: String): Boolean {
        val intents = createFileManagerRecentsIntents(packageName)

        intents.forEach { intent ->
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    context.startActivity(intent)
                    Log.i(TAG, "Started file manager at recents: $packageName action=${intent.action}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "File manager recents intent failed: $packageName action=${intent.action}", e)
            }
        }

        return startLauncherActivity(packageName)
    }

    private fun getHomePackageNames(): Set<String> {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val flags = PackageManager.MATCH_DEFAULT_ONLY
            packageManager.queryIntentActivities(homeIntent, flags)
                .map { it.activityInfo.packageName }
                .toSet() + setOf("com.miui.home")
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving home packages", e)
            setOf("com.miui.home")
        }
    }

    private fun shouldHideAfterForegroundChange(packageName: String, snapshot: ForegroundSnapshot): Boolean {
        if (snapshot.packageName == packageName) return false
        if (snapshot.packageName == context.packageName) return true
        if (snapshot.packageName in getHomePackageNames()) return true
        if (snapshot.packageName == null && snapshot.targetMovedToBackground) return true
        return false
    }
    fun forceHideAppsInProfile(apps: List<AppInfo>, reason: String): Int {
        if (!isProfileOwner()) return 0
        var hiddenCount = 0
        apps
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .forEach { app ->
                if (setAppHiddenInProfile(app.packageName, true)) hiddenCount++
            }
        Log.i(TAG, "Force hid profile apps reason=$reason count=$hiddenCount")
        return hiddenCount
    }

    fun rehideAppsInProfile(apps: List<AppInfo>, reason: String): Int {
        if (!isProfileOwner()) return 0
        val activePackage = sharedPrefs.getString(ACTIVE_LAUNCH_PACKAGE, null)
        val activeStartedAt = sharedPrefs.getLong(ACTIVE_LAUNCH_STARTED_AT, 0L)
        val activeLaunchValid = activePackage != null &&
            System.currentTimeMillis() - activeStartedAt <= FOREGROUND_MONITOR_TIMEOUT_MS
        var hiddenCount = 0
        apps
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .forEach { app ->
                if (activeLaunchValid && app.packageName == activePackage) {
                    Log.i(TAG, "Skip rehide active launched app reason=$reason package=${app.packageName}")
                    return@forEach
                }
                if (hideAppInProfileIfAllowed(app.packageName, reason)) hiddenCount++
            }
        if (!activeLaunchValid && activePackage != null) {
            clearActiveLaunchSession()
        }
        Log.i(
            TAG,
            "Rehid profile apps reason=$reason count=$hiddenCount active=$activePackage activeStartedAt=$activeStartedAt"
        )
        return hiddenCount
    }

    fun isActiveLaunchSession(packageName: String): Boolean {
        return sharedPrefs.getString(ACTIVE_LAUNCH_PACKAGE, null) == packageName
    }

    private fun markActiveLaunchSession(packageName: String) {
        sharedPrefs.edit()
            .putString(ACTIVE_LAUNCH_PACKAGE, packageName)
            .putLong(ACTIVE_LAUNCH_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearActiveLaunchSession() {
        sharedPrefs.edit()
            .remove(ACTIVE_LAUNCH_PACKAGE)
            .remove(ACTIVE_LAUNCH_STARTED_AT)
            .apply()
    }

    private fun startLaunchedAppForegroundMonitor(packageName: String) {
        val sessionStartedAt = System.currentTimeMillis()
        var observedTargetInForeground = false
        var targetForegroundFirstSeenAt = 0L
        var nonTargetForegroundFirstSeenAt = 0L

        fun pollForeground() {
            if (!isActiveLaunchSession(packageName)) return

            val now = System.currentTimeMillis()
            val elapsed = now - sessionStartedAt
            val foregroundSnapshot = getCurrentForegroundSnapshot(sessionStartedAt - 2_000L, packageName)
            val foregroundPackage = foregroundSnapshot.packageName
            Log.i(
                TAG,
                "Foreground monitor package=$packageName foreground=$foregroundPackage targetMovedToBackground=${foregroundSnapshot.targetMovedToBackground} observed=$observedTargetInForeground elapsed=$elapsed"
            )

            if (foregroundPackage == packageName) {
                observedTargetInForeground = true
                if (targetForegroundFirstSeenAt == 0L) targetForegroundFirstSeenAt = now
                nonTargetForegroundFirstSeenAt = 0L
                foregroundMonitorHandler.postDelayed(::pollForeground, FOREGROUND_MONITOR_POLL_MS)
                return
            }

            if (observedTargetInForeground && foregroundPackage != packageName) {
                if (nonTargetForegroundFirstSeenAt == 0L) nonTargetForegroundFirstSeenAt = now
                val nonTargetElapsed = now - nonTargetForegroundFirstSeenAt
                if (nonTargetElapsed < FOREGROUND_LEAVE_DEBOUNCE_MS) {
                    foregroundMonitorHandler.postDelayed(::pollForeground, FOREGROUND_MONITOR_POLL_MS)
                    return
                }

                val targetForegroundElapsed = if (targetForegroundFirstSeenAt == 0L) 0L else now - targetForegroundFirstSeenAt
                if (packageName == GOOGLE_PLAY_PACKAGE && targetForegroundElapsed < GOOGLE_PLAY_MIN_FOREGROUND_MS_BEFORE_HIDE) {
                    Log.i(
                        TAG,
                        "Delaying Google Play hide until startup is stable elapsed=$targetForegroundElapsed foreground=$foregroundPackage"
                    )
                    foregroundMonitorHandler.postDelayed(::pollForeground, FOREGROUND_MONITOR_POLL_MS)
                    return
                }

                if (!shouldHideAfterForegroundChange(packageName, foregroundSnapshot)) {
                    Log.i(
                        TAG,
                        "Delaying hide for launched app package=$packageName transientForeground=$foregroundPackage targetMovedToBackground=${foregroundSnapshot.targetMovedToBackground}"
                    )
                    foregroundMonitorHandler.postDelayed(::pollForeground, FOREGROUND_MONITOR_POLL_MS)
                    return
                }

                val hidden = hideAppInProfileIfAllowed(packageName, "foregroundChange")
                if (hidden) clearActiveLaunchSession()
                Log.i(TAG, "Hid launched profile app after safe foreground change package=$packageName foreground=$foregroundPackage hidden=$hidden")
                return
            }

            if (elapsed >= FOREGROUND_MONITOR_TIMEOUT_MS) {
                Log.w(TAG, "Foreground monitor timed out before detecting target leave foreground: $packageName")
                return
            }

            foregroundMonitorHandler.postDelayed(::pollForeground, FOREGROUND_MONITOR_POLL_MS)
        }

        foregroundMonitorHandler.postDelayed(::pollForeground, FOREGROUND_MONITOR_POLL_MS)
    }

    private fun getCurrentForegroundSnapshot(sinceTime: Long, targetPackageName: String): ForegroundSnapshot {
        return try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usageStatsManager.queryEvents(sinceTime.coerceAtLeast(0L), System.currentTimeMillis())
            val event = UsageEvents.Event()
            var foregroundPackage: String? = null
            var targetMovedToBackground = false
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when {
                    isUsageForegroundEvent(event.eventType) -> foregroundPackage = event.packageName
                    isUsagePackageBackgroundEvent(event.eventType) -> {
                        if (event.packageName == targetPackageName) targetMovedToBackground = true
                        if (foregroundPackage == event.packageName) foregroundPackage = null
                    }
                }
            }
            ForegroundSnapshot(foregroundPackage, targetMovedToBackground)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading foreground package from usage stats", e)
            ForegroundSnapshot(null, false)
        }
    }

    private fun isUsageForegroundEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && eventType == UsageEvents.Event.ACTIVITY_RESUMED)
    }

    private fun isUsagePackageBackgroundEvent(eventType: Int): Boolean {
        return eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
    }
    fun launchAppInProfile(packageName: String): Boolean {
        if (packageName == context.packageName) {
            Log.i(TAG, "Privacy space app selected; already in profile")
            return true
        }
        return try {
            if (isProfileOwner()) {
                if (packageName == GOOGLE_PLAY_PACKAGE) {
                    unhideGooglePlayRuntimeDependencies()
                }
                if (packageName == "com.miui.securitycenter") {
                    unhideNeverAutoHideDependencies()
                }
                unhideAppInProfile(packageName)
            }
            if (startLaunchableApp(packageName)) {
                markActiveLaunchSession(packageName)
                startLaunchedAppForegroundMonitor(packageName)
                Log.i(TAG, "Launched app in profile: $packageName")
                true
            } else {
                if (isProfileOwner()) hideAppInProfileIfAllowed(packageName, "launchFailed")
                clearActiveLaunchSession()
                Log.w(TAG, "No launchable activity for package: $packageName")
                false
            }
        } catch (e: Exception) {
            if (isProfileOwner()) hideAppInProfileIfAllowed(packageName, "launchException")
            clearActiveLaunchSession()
            Log.e(TAG, "Error launching app: $packageName", e)
            false
        }
    }
    fun createUninstallIntent(packageName: String): Intent {
        return Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName")).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    fun disableAppInProfile(packageName: String): Boolean {
        return try {
            if (isProfileOwner()) {
                devicePolicyManager.setApplicationHidden(getAdminComponent(), packageName, true)
                Log.i(TAG, "Disabled app in profile: $packageName")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling app: $packageName", e)
            false
        }
    }
}















































