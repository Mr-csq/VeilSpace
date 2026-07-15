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
import androidx.annotation.RequiresApi
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
import com.system.launcher.tools.data.policy.ProfileAppLaunchMode
import com.system.launcher.tools.data.policy.ProfileAppPolicyTable
import com.system.launcher.tools.data.policy.ProfileAppResidualHideAction
import com.system.launcher.tools.data.repository.ProfileAppPolicyStore
import com.system.launcher.tools.data.repository.ProfileAppStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class SafeProfileHideResult {
    HIDDEN,
    DEFERRED_UNTIL_BACKGROUND,
    NOT_APPLICABLE,
    FAILED
}

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
    private val sharedPrefs by lazy {
        context.getSharedPreferences("work_profile_prefs", Context.MODE_PRIVATE)
    }
    private val foregroundMonitorHandler by lazy { Handler(Looper.getMainLooper()) }
    private val residualHideActionLastRunAt = mutableMapOf<String, Long>()
    private val pendingKeepAliveHidePackages = mutableSetOf<String>()

    companion object {
        private const val TAG = "WorkProfileManager"
        const val ACTION_OPEN_PRIVACY_SPACE = "com.system.launcher.tools.action.OPEN_PRIVACY_SPACE"
        const val ACTION_OPEN_REAL_GAME_CENTER = "com.system.launcher.tools.action.OPEN_REAL_GAME_CENTER"
        const val ACTION_CLEANUP_LAUNCHER_SHORTCUT = "com.system.launcher.tools.action.CLEANUP_LAUNCHER_SHORTCUT"
        const val EXTRA_CLEANUP_PACKAGE_NAME = "com.system.launcher.tools.extra.CLEANUP_PACKAGE_NAME"
        const val EXTRA_CLEANUP_ALLOW_GENERIC_USER_APP = "com.system.launcher.tools.extra.CLEANUP_ALLOW_GENERIC_USER_APP"
        const val EXTRA_CLEANUP_SHORTCUT_LABELS = "com.system.launcher.tools.extra.CLEANUP_SHORTCUT_LABELS"
        const val EXTRA_CLEANUP_SHORTCUT_COMPONENTS = "com.system.launcher.tools.extra.CLEANUP_SHORTCUT_COMPONENTS"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val ACTIVE_LAUNCH_PACKAGE = "active_launch_package"
        private const val ACTIVE_LAUNCH_STARTED_AT = "active_launch_started_at"
        private const val ACTIVE_LAUNCH_TOKEN = "active_launch_token"
        private const val FOREGROUND_MONITOR_POLL_MS = 300L
        private const val FOREGROUND_MONITOR_IDLE_POLL_MS = 1_500L
        private const val FOREGROUND_LEAVE_DEBOUNCE_MS = 300L
        private const val FOREGROUND_MONITOR_TIMEOUT_MS = 60_000L
        private const val KEEP_ALIVE_HIDE_MONITOR_TIMEOUT_MS = 6 * 60 * 60_000L
        private const val RESIDUAL_HIDE_ACTION_COOLDOWN_MS = 2_000L
        private const val ACTIVE_LAUNCH_STALE_SESSION_MS = 60 * 60_000L
        private val ACTIVE_LAUNCH_HIDE_BYPASS_REASONS = setOf(
            "foregroundChange",
            "launchFailed",
            "launchException"
        )
    }

    private data class ForegroundSnapshot(
        val packageName: String?,
        val targetMovedToBackground: Boolean
    )

    private data class ActiveLaunchSession(
        val packageName: String,
        val startedAt: Long,
        val token: Long,
        val elapsedMs: Long
    )

    private fun getAdminComponent(): ComponentName {
        return ComponentName(context, WorkProfileAdminReceiver::class.java)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun getCrossProfileApps(): CrossProfileApps {
        return context.getSystemService(CrossProfileApps::class.java)
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
            showLauncherShortcutCleanupActivityInProfile()
            hidePersonalMediaImportActivityInProfile()
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
                IntentFilter(ACTION_CLEANUP_LAUNCHER_SHORTCUT).apply { addCategory(Intent.CATEGORY_DEFAULT) },
                DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT
            )
            devicePolicyManager.addCrossProfileIntentFilter(
                admin,
                IntentFilter(ProfileMediaTransferContract.ACTION_IMPORT_MEDIA_TO_PERSONAL).apply { addCategory(Intent.CATEGORY_DEFAULT) },
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

    fun connectionState(): WorkProfileConnectionState {
        val profileOwner = isProfileOwner()
        val crossProfileTarget = hasCrossProfileTarget()
        val otherProfile = checkIfProfileExists()
        return WorkProfileConnectionDecider.decide(
            isProfileOwner = profileOwner,
            hasCrossProfileTarget = crossProfileTarget,
            hasOtherProfile = otherProfile
        ).also { state ->
            Log.i(
                TAG,
                "connectionState=$state profileOwner=$profileOwner crossProfileTarget=$crossProfileTarget otherProfile=$otherProfile"
            )
        }
    }

    fun hasCrossProfileTarget(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            val currentUser = android.os.Process.myUserHandle()
            getCrossProfileApps().targetUserProfiles.any { it != currentUser }
        }.onFailure { error ->
            Log.w(TAG, "Unable to inspect cross-profile targets", error)
        }.getOrDefault(false)
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

    private fun getLauncherShortcutCleanupComponent(): ComponentName {
        return ComponentName(context.packageName, "${context.packageName}.work.LauncherShortcutCleanupActivity")
    }

    private fun getPersonalMediaImportComponent(): ComponentName {
        return ComponentName(context.packageName, "${context.packageName}.ui.files.PersonalMediaImportActivity")
    }

    fun startMediaTransferToPersonal(
        activity: Activity,
        transferId: String,
        operation: ProfileMediaTransferContract.Operation,
        mediaUris: List<Uri>,
        resultCallback: android.app.PendingIntent?,
        onLaunchResult: (Boolean) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Reject media transfer: Android ${Build.VERSION.SDK_INT} is below API 29")
            return false
        }
        if (!isProfileOwner()) {
            Log.w(TAG, "Reject media transfer: current user is not Profile Owner")
            return false
        }
        if (transferId.isBlank()) {
            Log.w(TAG, "Reject media transfer: transfer ID is blank")
            return false
        }
        if (mediaUris.isEmpty()) {
            Log.w(TAG, "Reject media transfer: URI list is empty")
            return false
        }
        if (mediaUris.size > ProfileMediaTransferContract.MAX_ITEMS_PER_TRANSFER) {
            Log.w(TAG, "Reject media transfer: count=${mediaUris.size} exceeds limit")
            return false
        }
        val personalUser = findPersonalProfileTransferTarget() ?: run {
            Log.e(
                TAG,
                "Reject media transfer: no personal target; crossProfileTargets=${crossProfileTargetSnapshot()} launcherProfiles=${launcherApps.profiles}"
            )
            return false
        }
        val preparationCallback = object : android.os.ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                if (resultCode != ProfileMediaTransferSourceService.PREPARE_COMPLETE) {
                    Log.e(TAG, "Media source preparation failed id=$transferId result=$resultCode")
                    onLaunchResult(false)
                    return
                }
                val prepared = ProfileMediaTransferContract.readPreparedSource(resultData)
                if (prepared == null) {
                    Log.e(TAG, "Media source preparation returned invalid data id=$transferId")
                    onLaunchResult(false)
                    return
                }
                val importIntent = runCatching {
                    ProfileMediaTransferContract.createImportIntent(
                        transferId = transferId,
                        operation = operation,
                        sources = prepared.sources,
                        sourceReceiver = prepared.sourceReceiver,
                        resultCallback = resultCallback
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Unable to create prepared media transfer intent id=$transferId", error)
                }.getOrNull()
                if (importIntent == null) {
                    prepared.sourceReceiver.send(
                        ProfileMediaTransferContract.SOURCE_REQUEST_CLOSE,
                        android.os.Bundle.EMPTY
                    )
                    onLaunchResult(false)
                    return
                }

                val launched = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        getCrossProfileApps().startActivity(
                            Intent(importIntent).apply { component = getPersonalMediaImportComponent() },
                            personalUser,
                            activity
                        )
                    } else {
                        activity.startActivity(Intent(importIntent).apply { setPackage(null) })
                    }
                    Log.i(
                        TAG,
                        "Started personal-profile media transfer with isolated source id=$transferId operation=$operation count=${mediaUris.size} target=$personalUser"
                    )
                    true
                }.onFailure { error ->
                    prepared.sourceReceiver.send(
                        ProfileMediaTransferContract.SOURCE_REQUEST_CLOSE,
                        android.os.Bundle.EMPTY
                    )
                    Log.e(TAG, "Unable to launch prepared personal-profile media transfer id=$transferId", error)
                }.getOrDefault(false)
                onLaunchResult(launched)
            }
        }

        return ProfileMediaTransferSourceService.start(
            context = context,
            transferId = transferId,
            uris = mediaUris,
            callback = preparationCallback
        ).also { accepted ->
            if (accepted) {
                Log.i(TAG, "Requested isolated media source preparation id=$transferId count=${mediaUris.size}")
            }
        }
    }

    private fun findPersonalProfileTransferTarget(): UserHandle? {
        val currentUser = android.os.Process.myUserHandle()
        val crossProfileTargets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { getCrossProfileApps().targetUserProfiles }
                .onFailure { error ->
                    Log.w(TAG, "Unable to read CrossProfileApps transfer targets", error)
                }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val launcherProfiles = runCatching { launcherApps.profiles }
            .onFailure { error -> Log.w(TAG, "Unable to read LauncherApps transfer profiles", error) }
            .getOrDefault(emptyList())
        return CrossProfileTargetSelector.select(currentUser, crossProfileTargets, launcherProfiles)?.also { target ->
            val source = if (target in crossProfileTargets) "CrossProfileApps" else "LauncherApps fallback"
            Log.i(TAG, "Resolved personal transfer target from $source: $target")
        }
    }

    private fun crossProfileTargetSnapshot(): List<UserHandle> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        return runCatching { getCrossProfileApps().targetUserProfiles }.getOrDefault(emptyList())
    }

    fun redirectToManagedProfile(
        activity: Activity,
        targetActivity: Class<out Activity>
    ): Boolean {
        return try {
            if (isProfileOwner()) return false
            val currentUser = android.os.Process.myUserHandle()
            val targetUsers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { getCrossProfileApps().targetUserProfiles }.getOrDefault(emptyList())
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
                    setComponent(getPrivacyActionComponent())
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
                        getCrossProfileApps().startActivity(explicitIntent, workUser, activity)
                    }.getOrElse { explicitError ->
                        Log.w(TAG, "Explicit cross-profile start failed, falling back to launcher alias", explicitError)
                        getCrossProfileApps().startMainActivity(component, workUser)
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getCrossProfileApps().startMainActivity(component, workUser)
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
        ProfileAppPolicyTable.installSupportPackages().forEach { packageName ->
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

    fun createProfileIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            if (checkIfProfileExists()) {
                Log.w(TAG, "Work Profile already exists")
                null
            } else if (!devicePolicyManager.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
                Log.e(TAG, "Provisioning not allowed on this device")
                null
            } else {
                createProvisioningIntent()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing profile provisioning", e)
            null
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
            if (!isPackageKnownInProfile(packageName)) {
                Log.i(TAG, "Skip installExistingPackage for package not visible in profile: $packageName")
                return false
            }
            val installed = devicePolicyManager.installExistingPackage(getAdminComponent(), packageName)
            Log.i(TAG, "installExistingPackage result for $packageName = $installed")
            installed
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException enabling app: $packageName message=${e.message}")
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

    fun prepareSystemCandidateInProfile(packageName: String): Boolean {
        if (!isProfileOwner()) return false
        if (isPackageInstalledInProfile(packageName)) {
            unhideAppInProfile(packageName)
            return true
        }

        val installedExisting = enableAppInProfile(packageName)
        if (!installedExisting) {
            enableSystemAppInProfile(packageName)
        }
        val installed = isPackageInstalledInProfile(packageName)
        if (installed) {
            unhideAppInProfile(packageName)
        }
        Log.i(TAG, "Prepared system candidate package=$packageName installed=$installed")
        return installed
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

    fun showLauncherShortcutCleanupActivityInProfile(): Boolean {
        return setLauncherShortcutCleanupActivityEnabled(true)
    }

    fun hidePersonalMediaImportActivityInProfile(): Boolean {
        return setPersonalMediaImportActivityEnabled(false)
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

    private fun setLauncherShortcutCleanupActivityEnabled(enabled: Boolean): Boolean {
        return try {
            val state = if (enabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(getLauncherShortcutCleanupComponent(), state, DONT_KILL_APP)
            Log.i(TAG, "Set launcher shortcut cleanup activity enabled=$enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting launcher shortcut cleanup activity enabled=$enabled", e)
            false
        }
    }

    private fun setPersonalMediaImportActivityEnabled(enabled: Boolean): Boolean {
        return try {
            val state = if (enabled) COMPONENT_ENABLED_STATE_ENABLED else COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(getPersonalMediaImportComponent(), state, DONT_KILL_APP)
            Log.i(TAG, "Set personal media import activity enabled=$enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting personal media import activity enabled=$enabled", e)
            false
        }
    }

    fun hideAppInProfile(packageName: String): Boolean {
        return setAppHiddenInProfile(packageName, true)
    }

    fun hideAppInProfileIfAllowed(packageName: String, reason: String): Boolean {
        return hidePolicyPackagesInProfileIfAllowed(packageName, reason).any { it }
    }

    private fun hidePolicyPackagesInProfileIfAllowed(packageName: String, reason: String): List<Boolean> {
        val policy = ProfileAppPolicyTable.resolve(packageName)
        return policy.postLaunchHidePackageNames.map { targetPackageName ->
            if (shouldDeferAutoHideForActiveLaunch(targetPackageName, reason)) {
                false
            } else {
                hideSingleAppInProfileIfAllowed(targetPackageName, reason).also { hidden ->
                    if (hidden || shouldAttemptResidualHideActions(targetPackageName, reason)) {
                        applyResidualHideActions(targetPackageName, reason)
                    }
                }
            }
        }
    }

    private fun hideSingleAppInProfileIfAllowed(packageName: String, reason: String): Boolean {
        val policy = ProfileAppPolicyStore.resolvePolicy(context, packageName)
        val blockReason = policy.autoHideBlockReason
        if (blockReason != null) {
            Log.i(TAG, "Skip hiding by policy reason=$reason block=$blockReason package=$packageName")
            return false
        }
        return hideAppInProfile(packageName)
    }

    private fun schedulePostHideRetries(packageName: String, reason: String) {
        val policy = ProfileAppPolicyTable.resolve(packageName)
        policy.postHideRetryDelaysMs.forEach { delayMs ->
            foregroundMonitorHandler.postDelayed(
                { hidePolicyPackagesInProfileIfAllowed(packageName, "$reason:retry${delayMs}ms") },
                delayMs
            )
        }
    }

    private fun applyResidualHideActions(packageName: String, reason: String) {
        for (policy in ProfileAppPolicyTable.policiesForResidualHidePackage(packageName)) {
            for (action in policy.residualHideActions) {
                if (!shouldRunResidualHideAction(policy.packageName, action, reason)) continue
                when (action) {
                    ProfileAppResidualHideAction.REAPPLY_HIDDEN_STATE -> forceReapplyHiddenState(policy.packageName, reason)
                    ProfileAppResidualHideAction.REMOVE_LEGACY_LAUNCHER_SHORTCUTS -> requestLauncherShortcutCleanup(policy.packageName, reason)
                    ProfileAppResidualHideAction.REQUEST_LAUNCHER_REQUERY -> requestLauncherRequery(policy.packageName, reason)
                }
            }
        }
        applyGenericUserAppResidualHideActions(packageName, reason)
    }

    private fun shouldAttemptResidualHideActions(packageName: String, reason: String): Boolean {
        return ProfileAppPolicyTable.shouldAttemptResidualHide(packageName) || shouldAttemptGenericUserAppResidualHide(packageName, reason)
    }

    private fun applyGenericUserAppResidualHideActions(packageName: String, reason: String) {
        if (!shouldAttemptGenericUserAppResidualHide(packageName, reason)) return
        for (action in ProfileAppPolicyTable.genericUserAppResidualHideActions()) {
            if (!shouldRunResidualHideAction(packageName, action, reason)) continue
            when (action) {
                ProfileAppResidualHideAction.REAPPLY_HIDDEN_STATE -> forceReapplyHiddenState(packageName, reason)
                ProfileAppResidualHideAction.REMOVE_LEGACY_LAUNCHER_SHORTCUTS -> requestLauncherShortcutCleanup(
                    packageName = packageName,
                    reason = reason,
                    allowGenericUserAppCleanup = true
                )
                ProfileAppResidualHideAction.REQUEST_LAUNCHER_REQUERY -> requestLauncherRequery(packageName, reason)
            }
        }
    }

    private fun shouldAttemptGenericUserAppResidualHide(packageName: String, reason: String): Boolean {
        if (!ProfileAppPolicyTable.shouldAttemptGenericUserAppResidualHide(reason)) return false
        if (ProfileAppPolicyTable.shouldAttemptResidualHide(packageName)) return false
        if (!ProfileAppPolicyStore.canAutoHideApp(context, packageName)) return false
        return isUserInstalledPackageInProfile(packageName)
    }
    private fun shouldRunResidualHideAction(
        packageName: String,
        action: ProfileAppResidualHideAction,
        reason: String
    ): Boolean {
        if (!isResidualHideActionAllowedForReason(reason)) {
            Log.i(TAG, "Skip residual hide action by reason reason=$reason package=$packageName action=$action")
            return false
        }
        val key = "$packageName:${action.name}"
        val now = System.currentTimeMillis()
        val lastRunAt = residualHideActionLastRunAt[key] ?: 0L
        if (now - lastRunAt < RESIDUAL_HIDE_ACTION_COOLDOWN_MS) {
            Log.i(TAG, "Skip residual hide action by cooldown reason=$reason package=$packageName action=$action")
            return false
        }
        residualHideActionLastRunAt[key] = now
        return true
    }

    private fun isResidualHideActionAllowedForReason(reason: String): Boolean {
        if (reason.contains(":retry")) return false
        return ProfileAppPolicyTable.isResidualHideActionReasonAllowed(reason)
    }
    private fun forceReapplyHiddenState(packageName: String, reason: String): Boolean {
        return setAppHiddenInProfile(packageName, hidden = true, force = true, reason = "residual:$reason")
    }

    private fun requestLauncherShortcutCleanup(
        packageName: String,
        reason: String,
        allowGenericUserAppCleanup: Boolean = false
    ): Boolean {
        val cachedApp = ProfileAppStore.loadApps(context).firstOrNull { it.packageName == packageName }
        val cachedLabels = cachedApp?.appName?.takeIf { it.isNotBlank() }?.let { setOf(it) }.orEmpty()
        val cachedComponents = cachedApp?.launcherComponentNames
            .orEmpty()
            .mapNotNull(ComponentName::unflattenFromString)
        val hints = LauncherShortcutCleaner.resolveShortcutHints(context, packageName, cachedLabels, cachedComponents)
        val intent = Intent(ACTION_CLEANUP_LAUNCHER_SHORTCUT).apply {
            setComponent(getLauncherShortcutCleanupComponent())
            putExtra(EXTRA_CLEANUP_PACKAGE_NAME, packageName)
            putExtra(EXTRA_CLEANUP_ALLOW_GENERIC_USER_APP, allowGenericUserAppCleanup)
            putStringArrayListExtra(EXTRA_CLEANUP_SHORTCUT_LABELS, ArrayList(hints.labels))
            putStringArrayListExtra(EXTRA_CLEANUP_SHORTCUT_COMPONENTS, ArrayList(hints.components.map { it.flattenToString() }))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val activityStarted = runCatching {
            context.startActivity(intent)
            true
        }.onFailure { error ->
            Log.w(TAG, "Launcher shortcut cleanup activity request failed reason=$reason package=$packageName", error)
        }.getOrDefault(false)
        Log.i(
            TAG,
            "Requested launcher shortcut cleanup reason=$reason package=$packageName activity=$activityStarted generic=$allowGenericUserAppCleanup labels=${hints.labels.size} components=${hints.components.size}"
        )
        return activityStarted
    }
    private fun requestLauncherRequery(packageName: String, reason: String): Boolean {
        return runCatching {
            val disabled = setPrivacySpaceLauncherAliasEnabled(false)
            val enabled = setPrivacySpaceLauncherAliasEnabled(true)
            Log.i(TAG, "Requested launcher requery reason=$reason package=$packageName disabled=$disabled enabled=$enabled")
            disabled || enabled
        }.onFailure { error ->
            Log.w(TAG, "Launcher requery request failed reason=$reason package=$packageName", error)
        }.getOrDefault(false)
    }
    fun shouldNeverAutoHide(packageName: String): Boolean {
        return ProfileAppPolicyStore.shouldNeverAutoHide(packageName)
    }

    private fun preparePolicyPackagesForUse(packages: Set<String>, reason: String, targetPackageName: String? = null) {
        if (!isProfileOwner()) return
        packages
            .filterNot { it == targetPackageName }
            .forEach { packageName ->
                runCatching {
                    if (!isPackageInstalledInProfile(packageName)) {
                        if (isPackageKnownInProfile(packageName)) {
                            enableAppInProfile(packageName)
                        } else {
                            Log.i(TAG, "Skip unavailable policy dependency reason=$reason package=$packageName")
                        }
                    }
                    if (isPackageInstalledInProfile(packageName)) {
                        unhideAppInProfile(packageName)
                    } else {
                        Log.w(TAG, "Policy dependency is not installed reason=$reason package=$packageName")
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Unable to prepare policy dependency reason=$reason package=$packageName", error)
                }
            }
    }

    fun unhideAppInProfile(packageName: String): Boolean {
        return setAppHiddenInProfile(packageName, false)
    }

    fun isAppHiddenInProfile(packageName: String): Boolean? {
        if (!isProfileOwner()) return null
        return runCatching {
            devicePolicyManager.isApplicationHidden(getAdminComponent(), packageName)
        }.onFailure { error ->
            Log.w(TAG, "Unable to read hidden state package=$packageName", error)
        }.getOrNull()
    }

    /**
     * Applies the same foreground-safe semantics used after launching an app. If the
     * target is currently foreground, the app is left running and hidden only after
     * UsageStats reports that it moved to background. Re-enabling keepAlive cancels
     * the pending hide because every poll re-checks the shared policy store.
     */
    fun requestSafeHideAfterKeepAliveDisabled(packageName: String, reason: String): SafeProfileHideResult {
        if (!isProfileOwner() || !ProfileAppPolicyStore.canAutoHideApp(context, packageName)) {
            return SafeProfileHideResult.NOT_APPLICABLE
        }
        if (!isPackageInstalledInProfile(packageName)) return SafeProfileHideResult.NOT_APPLICABLE

        val requestedAt = System.currentTimeMillis()
        val policy = ProfileAppPolicyTable.resolve(packageName)
        val snapshot = getCurrentForegroundSnapshot(requestedAt - 120_000L, packageName)
        val targetIsForeground = isActiveLaunchSession(packageName) ||
            (snapshot.packageName != null && snapshot.packageName in policy.foregroundPackageNames)
        if (!targetIsForeground) {
            val hidden = hideAppInProfileIfAllowed(packageName, reason)
            return if (hidden || isAppHiddenInProfile(packageName) == true) {
                SafeProfileHideResult.HIDDEN
            } else {
                SafeProfileHideResult.FAILED
            }
        }

        synchronized(pendingKeepAliveHidePackages) {
            if (!pendingKeepAliveHidePackages.add(packageName)) return SafeProfileHideResult.DEFERRED_UNTIL_BACKGROUND
        }
        Log.i(TAG, "Deferring keepAlive hide until target leaves foreground package=$packageName reason=$reason")

        fun pollUntilBackground() {
            if (!ProfileAppPolicyStore.canAutoHideApp(context, packageName)) {
                synchronized(pendingKeepAliveHidePackages) { pendingKeepAliveHidePackages.remove(packageName) }
                Log.i(TAG, "Cancelled deferred keepAlive hide because policy changed package=$packageName")
                return
            }
            val elapsed = System.currentTimeMillis() - requestedAt
            if (elapsed >= KEEP_ALIVE_HIDE_MONITOR_TIMEOUT_MS) {
                synchronized(pendingKeepAliveHidePackages) { pendingKeepAliveHidePackages.remove(packageName) }
                Log.w(TAG, "Deferred keepAlive hide monitor timed out package=$packageName")
                return
            }
            val current = getCurrentForegroundSnapshot(requestedAt - 2_000L, packageName)
            val stillForeground = current.packageName != null && current.packageName in policy.foregroundPackageNames
            if (stillForeground || !shouldHideAfterForegroundChange(packageName, current)) {
                foregroundMonitorHandler.postDelayed(::pollUntilBackground, FOREGROUND_MONITOR_IDLE_POLL_MS)
                return
            }
            val hidden = hideAppInProfileIfAllowed(packageName, reason)
            synchronized(pendingKeepAliveHidePackages) { pendingKeepAliveHidePackages.remove(packageName) }
            Log.i(TAG, "Completed deferred keepAlive hide package=$packageName reason=$reason hidden=$hidden")
        }

        foregroundMonitorHandler.post(::pollUntilBackground)
        return SafeProfileHideResult.DEFERRED_UNTIL_BACKGROUND
    }

    private fun setAppHiddenInProfile(
        packageName: String,
        hidden: Boolean,
        force: Boolean = false,
        reason: String = "direct"
    ): Boolean {
        return try {
            if (!isProfileOwner()) return false
            if (packageName == context.packageName) {
                Log.i(TAG, "Skip hiding own package to keep cross-profile entry available")
                return false
            }
            val admin = getAdminComponent()
            val alreadyHidden = runCatching {
                devicePolicyManager.isApplicationHidden(admin, packageName)
            }.getOrNull()
            if (!force && alreadyHidden == hidden) {
                Log.i(TAG, "Skip setting app hidden=$hidden because state is already current: $packageName")
                return false
            }
            if (force && alreadyHidden == hidden) {
                devicePolicyManager.setApplicationHidden(admin, packageName, !hidden)
            }
            var changed = devicePolicyManager.setApplicationHidden(admin, packageName, hidden)
            var appliedHidden = runCatching {
                devicePolicyManager.isApplicationHidden(admin, packageName)
            }.getOrNull()
            if (!force && hidden && appliedHidden != hidden) {
                Log.w(
                    TAG,
                    "DPM hidden state did not apply, retrying with forced reapply package=$packageName reason=$reason changed=$changed dpmHidden=$appliedHidden"
                )
                runCatching { devicePolicyManager.setApplicationHidden(admin, packageName, false) }
                    .onFailure { error -> Log.w(TAG, "Forced unhide step failed package=$packageName reason=$reason", error) }
                changed = runCatching { devicePolicyManager.setApplicationHidden(admin, packageName, true) }
                    .onFailure { error -> Log.w(TAG, "Forced hide step failed package=$packageName reason=$reason", error) }
                    .getOrDefault(false)
                appliedHidden = runCatching {
                    devicePolicyManager.isApplicationHidden(admin, packageName)
                }.getOrNull()
            }
            val launcherActivityCount = runCatching {
                launcherApps.getActivityList(packageName, android.os.Process.myUserHandle()).size
            }.getOrNull()
            val applied = appliedHidden == hidden
            Log.i(
                TAG,
                "Set app hidden=$hidden in profile: $packageName force=$force reason=$reason changed=$changed dpmHidden=$appliedHidden applied=$applied launcherActivities=$launcherActivityCount"
            )
            applied
        } catch (e: Exception) {
            Log.e(TAG, "Error setting app hidden=$hidden: $packageName", e)
            false
        }
    }    fun isPackageInstalledInProfile(packageName: String): Boolean {
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

    private fun isPackageKnownInProfile(packageName: String): Boolean {
        return runCatching { getProfileApplicationInfo(packageName) != null }.getOrDefault(false)
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

    fun getLauncherComponentNamesInProfile(packageName: String): Set<String> {
        return LauncherShortcutCleaner.resolveShortcutHints(context, packageName)
            .components
            .mapTo(linkedSetOf()) { it.flattenToString() }
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
        return ProfileAppPolicyTable.isFileManagerPackage(packageName)
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
        val installVerification = if (isPackageInstalledInProfile(packageName)) {
            InstallVerification.CONFIRMED_INSTALLED
        } else {
            InstallVerification.UNKNOWN
        }
        return resolveLaunchVerificationInProfile(packageName, installVerification).let { launchVerification ->
            launchVerification == LaunchVerification.LAUNCHABLE ||
                launchVerification == LaunchVerification.POLICY_LAUNCH_ONLY
        }
    }

    fun resolveLaunchVerificationInProfile(
        packageName: String,
        installVerification: InstallVerification
    ): LaunchVerification {
        return try {
            val hasLauncherActivity = hasLauncherActivityInProfile(packageName)
            when {
                installVerification == InstallVerification.CONFIRMED_INSTALLED &&
                    (isUserInstalledPackageInProfile(packageName) || hasLauncherActivity) -> LaunchVerification.LAUNCHABLE
                isKnownProfileLaunchTool(packageName) -> LaunchVerification.POLICY_LAUNCH_ONLY
                installVerification == InstallVerification.CONFIRMED_INSTALLED -> LaunchVerification.NOT_LAUNCHABLE
                else -> LaunchVerification.UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving launch verification for profile package: $packageName", e)
            LaunchVerification.UNKNOWN
        }
    }

    fun isKnownProfileLaunchTool(packageName: String): Boolean {
        return ProfileAppPolicyTable.resolve(packageName).knownLaunchTool
    }

    private fun startLaunchableApp(packageName: String): Boolean {
        return when (ProfileAppPolicyTable.resolve(packageName).launchMode) {
            ProfileAppLaunchMode.FILE_MANAGER_RECENTS -> startFileManagerAtRecents(packageName)
            ProfileAppLaunchMode.URI_THEN_COMPONENT -> startSpecialLaunchUri(packageName) || startSpecialLaunchComponent(packageName) || startLauncherActivity(packageName, allowPolicyFallback = false)
            ProfileAppLaunchMode.COMPONENT_THEN_URI -> startSpecialLaunchComponent(packageName) || startSpecialLaunchUri(packageName) || startLauncherActivity(packageName, allowPolicyFallback = false)
            ProfileAppLaunchMode.DEFAULT -> startLauncherActivity(packageName)
        }
    }
    private fun startLauncherActivity(packageName: String, allowPolicyFallback: Boolean = true): Boolean {
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
            } else if (allowPolicyFallback && (startSpecialLaunchComponent(packageName) || startSpecialLaunchUri(packageName))) {
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
        ProfileAppPolicyTable.resolve(packageName).launchComponents.forEach { component ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    launcherApps.startMainActivity(
                        component,
                        android.os.Process.myUserHandle(),
                        null,
                        null
                    )
                    Log.i(TAG, "Started app via policy LauncherApps component: $packageName component=$component")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Policy LauncherApps component failed: $packageName component=$component", e)
                }
            }

            try {
                context.startActivity(
                    Intent().apply {
                        setComponent(component)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                Log.i(TAG, "Started app via policy explicit component: $packageName component=$component")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Policy explicit component failed: $packageName component=$component", e)
            }

            try {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setComponent(component)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                Log.i(TAG, "Started app via policy launch component: $packageName component=$component")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Policy launch component failed: $packageName component=$component", e)
            }
        }

        return false
    }

    private fun startSpecialLaunchUri(packageName: String): Boolean {
        ProfileAppPolicyTable.resolve(packageName).launchIntents
            .map { it.toIntent(packageName) }
            .forEach { intent ->
                try {
                    context.startActivity(intent)
                    Log.i(TAG, "Started app via policy launch intent: $packageName action=${intent.action} uri=${intent.data}")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Policy launch intent failed: $packageName action=${intent.action} uri=${intent.data}", e)
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
        if (snapshot.packageName != null && snapshot.packageName in ProfileAppPolicyTable.resolve(packageName).foregroundPackageNames) return false
        if (snapshot.packageName == context.packageName) return true
        if (snapshot.packageName in getHomePackageNames()) return true
        if (snapshot.packageName == null && snapshot.targetMovedToBackground) return true
        return false
    }
    fun forceHideAppsInProfile(apps: List<AppInfo>, reason: String): Int {
        if (!isProfileOwner()) return 0
        var processedCount = 0
        var hiddenCount = 0
        collectPackagesForResidualHide(apps)
            .forEach { packageName ->
                if (prepareResidualHideTarget(packageName, reason)) {
                    processedCount++
                    if (hideAppInProfileIfAllowed(packageName, reason)) {
                        hiddenCount++
                        schedulePostHideRetries(packageName, reason)
                    }
                }
            }
        Log.i(TAG, "Policy-hid profile apps reason=$reason hidden=$hiddenCount processed=$processedCount")
        return processedCount
    }
    fun rehideAppsInProfile(apps: List<AppInfo>, reason: String): Int {
        if (!isProfileOwner()) return 0
        val activeSession = getActiveLaunchSession()
        var hiddenCount = 0
        collectPackagesForResidualHide(apps)
            .forEach { packageName ->
                if (prepareResidualHideTarget(packageName, reason) && hideAppInProfileIfAllowed(packageName, reason)) {
                    hiddenCount++
                    schedulePostHideRetries(packageName, reason)
                }
            }
        Log.i(
            TAG,
            "Rehid profile apps reason=$reason count=$hiddenCount active=${activeSession?.packageName} activeStartedAt=${activeSession?.startedAt}"
        )
        return hiddenCount
    }

    private fun collectPackagesForResidualHide(apps: List<AppInfo>): List<String> {
        val cachedPackages = apps
            .asSequence()
            .filter { it.packageName != context.packageName && it.installVerification != InstallVerification.CONFIRMED_MISSING }
            .map { it.packageName }
        return (cachedPackages + ProfileAppPolicyTable.residualHideCandidatePackages().asSequence())
            .distinct()
            .toList()
    }

    private fun prepareResidualHideTarget(packageName: String, reason: String): Boolean {
        return runCatching {
            if (!isPackageInstalledInProfile(packageName) && ProfileAppPolicyTable.shouldAttemptResidualHide(packageName)) {
                Log.i(TAG, "Skip installExistingPackage for residual hide candidate reason=$reason package=$packageName")
            }
            val installed = isPackageInstalledInProfile(packageName)
            if (!installed) Log.i(TAG, "Skip residual hide for unavailable package reason=$reason package=$packageName")
            installed
        }.onFailure { error ->
            Log.w(TAG, "Unable to prepare residual hide target reason=$reason package=$packageName", error)
        }.getOrDefault(false)
    }
    fun isActiveLaunchSession(packageName: String): Boolean {
        return sharedPrefs.getString(ACTIVE_LAUNCH_PACKAGE, null) == packageName
    }

    fun shouldDeferPackageEventAutoHide(packageName: String): Boolean {
        return shouldDeferAutoHideForActiveLaunch(packageName, "packageReceiver")
    }

    private fun markActiveLaunchSession(packageName: String): ActiveLaunchSession {
        val now = System.currentTimeMillis()
        val token = System.nanoTime()
        sharedPrefs.edit()
            .putString(ACTIVE_LAUNCH_PACKAGE, packageName)
            .putLong(ACTIVE_LAUNCH_STARTED_AT, now)
            .putLong(ACTIVE_LAUNCH_TOKEN, token)
            .apply()
        Log.i(TAG, "Marked active launch session package=$packageName token=$token")
        return ActiveLaunchSession(packageName, now, token, 0L)
    }

    private fun clearActiveLaunchSession(packageName: String? = null, token: Long? = null) {
        if (packageName != null && sharedPrefs.getString(ACTIVE_LAUNCH_PACKAGE, null) != packageName) return
        if (token != null && sharedPrefs.getLong(ACTIVE_LAUNCH_TOKEN, 0L) != token) return
        sharedPrefs.edit()
            .remove(ACTIVE_LAUNCH_PACKAGE)
            .remove(ACTIVE_LAUNCH_STARTED_AT)
            .remove(ACTIVE_LAUNCH_TOKEN)
            .apply()
    }

    private fun getActiveLaunchSession(): ActiveLaunchSession? {
        val packageName = sharedPrefs.getString(ACTIVE_LAUNCH_PACKAGE, null)?.takeIf { it.isNotBlank() } ?: return null
        val startedAt = sharedPrefs.getLong(ACTIVE_LAUNCH_STARTED_AT, 0L)
        val token = sharedPrefs.getLong(ACTIVE_LAUNCH_TOKEN, 0L)
        if (startedAt <= 0L || token == 0L) return null
        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        return ActiveLaunchSession(packageName, startedAt, token, elapsedMs)
    }

    private fun isActiveLaunchSession(packageName: String, token: Long): Boolean {
        return sharedPrefs.getString(ACTIVE_LAUNCH_PACKAGE, null) == packageName &&
            sharedPrefs.getLong(ACTIVE_LAUNCH_TOKEN, 0L) == token
    }

    private fun shouldDeferAutoHideForActiveLaunch(packageName: String, reason: String): Boolean {
        if (reason.substringBefore(":") in ACTIVE_LAUNCH_HIDE_BYPASS_REASONS) return false
        val session = getActiveLaunchSession() ?: return false
        val policy = ProfileAppPolicyTable.resolve(session.packageName)
        val protectedPackages = policy.postLaunchHidePackageNames +
            policy.preLaunchPackages +
            policy.foregroundPackageNames
        if (packageName !in protectedPackages) return false

        if (session.elapsedMs > ACTIVE_LAUNCH_STALE_SESSION_MS) {
            val snapshot = getCurrentForegroundSnapshot(session.startedAt - 2_000L, session.packageName)
            val activeStillForeground = snapshot.packageName != null && snapshot.packageName in policy.foregroundPackageNames
            if (!activeStillForeground) {
                Log.i(
                    TAG,
                    "Active launch guard expired stale session active=${session.packageName} target=$packageName reason=$reason elapsed=${session.elapsedMs}"
                )
                clearActiveLaunchSession(session.packageName, session.token)
                return false
            }
        }

        Log.i(
            TAG,
            "Deferring auto-hide during active launch active=${session.packageName} target=$packageName reason=$reason elapsed=${session.elapsedMs}"
        )
        return true
    }

    private fun startLaunchedAppForegroundMonitor(packageName: String, session: ActiveLaunchSession) {
        val sessionStartedAt = session.startedAt
        val sessionToken = session.token
        val policy = ProfileAppPolicyTable.resolve(packageName)
        val minForegroundMsBeforeHide = policy.minForegroundMsBeforeHide
        val foregroundPackageNames = policy.foregroundPackageNames
        var observedTargetInForeground = false
        var targetForegroundFirstSeenAt = 0L
        var nonTargetForegroundFirstSeenAt = 0L

        fun pollForeground() {
            if (!isActiveLaunchSession(packageName, sessionToken)) return

            val now = System.currentTimeMillis()
            val elapsed = now - sessionStartedAt
            val foregroundSnapshot = getCurrentForegroundSnapshot(sessionStartedAt - 2_000L, packageName)
            val foregroundPackage = foregroundSnapshot.packageName
            Log.i(
                TAG,
                "Foreground monitor package=$packageName foreground=$foregroundPackage targetMovedToBackground=${foregroundSnapshot.targetMovedToBackground} observed=$observedTargetInForeground elapsed=$elapsed"
            )

            if (foregroundPackage != null && foregroundPackage in foregroundPackageNames) {
                observedTargetInForeground = true
                if (targetForegroundFirstSeenAt == 0L) targetForegroundFirstSeenAt = now
                nonTargetForegroundFirstSeenAt = 0L
                val nextPollMs = if (elapsed >= FOREGROUND_MONITOR_TIMEOUT_MS) {
                    FOREGROUND_MONITOR_IDLE_POLL_MS
                } else {
                    FOREGROUND_MONITOR_POLL_MS
                }
                foregroundMonitorHandler.postDelayed(::pollForeground, nextPollMs)
                return
            }

            if (observedTargetInForeground && (foregroundPackage == null || foregroundPackage !in foregroundPackageNames)) {
                if (nonTargetForegroundFirstSeenAt == 0L) nonTargetForegroundFirstSeenAt = now
                val nonTargetElapsed = now - nonTargetForegroundFirstSeenAt
                if (nonTargetElapsed < FOREGROUND_LEAVE_DEBOUNCE_MS) {
                    foregroundMonitorHandler.postDelayed(::pollForeground, FOREGROUND_MONITOR_POLL_MS)
                    return
                }

                val targetForegroundElapsed = if (targetForegroundFirstSeenAt == 0L) 0L else now - targetForegroundFirstSeenAt
                if (minForegroundMsBeforeHide > 0L && targetForegroundElapsed < minForegroundMsBeforeHide) {
                    Log.i(
                        TAG,
                        "Delaying hide by policy until startup is stable package=$packageName elapsed=$targetForegroundElapsed min=$minForegroundMsBeforeHide foreground=$foregroundPackage"
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
                clearActiveLaunchSession(packageName, sessionToken)
                if (hidden) {
                    schedulePostHideRetries(packageName, "foregroundChange")
                }
                Log.i(TAG, "Hid launched profile app after safe foreground change package=$packageName foreground=$foregroundPackage hidden=$hidden")
                return
            }

            if (elapsed >= FOREGROUND_MONITOR_TIMEOUT_MS && !observedTargetInForeground) {
                Log.w(TAG, "Foreground monitor timed out before detecting target foreground: $packageName")
                clearActiveLaunchSession(packageName, sessionToken)
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
                val policy = ProfileAppPolicyTable.resolve(packageName)
                markActiveLaunchSession(packageName)
                preparePolicyPackagesForUse(policy.preLaunchPackages, "preLaunch:$packageName", packageName)
                unhideAppInProfile(packageName)
            }
            if (startLaunchableApp(packageName)) {
                val session = markActiveLaunchSession(packageName)
                startLaunchedAppForegroundMonitor(packageName, session)
                Log.i(TAG, "Launched app in profile: $packageName")
                true
            } else {
                if (isProfileOwner()) {
                    hideAppInProfileIfAllowed(packageName, "launchFailed")
                    schedulePostHideRetries(packageName, "launchFailed")
                }
                clearActiveLaunchSession()
                Log.w(TAG, "No launchable activity for package: $packageName")
                false
            }
        } catch (e: Exception) {
            if (isProfileOwner()) {
                hideAppInProfileIfAllowed(packageName, "launchException")
                schedulePostHideRetries(packageName, "launchException")
            }
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
