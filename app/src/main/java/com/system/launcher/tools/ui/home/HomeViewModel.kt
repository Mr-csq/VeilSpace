package com.system.launcher.tools.ui.home

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.system.launcher.tools.R
import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
import com.system.launcher.tools.data.repository.AppRepository
import com.system.launcher.tools.data.repository.ProfileAppStore
import com.system.launcher.tools.work.WorkProfileManager
import com.system.launcher.tools.work.WorkProfileConnectionState
import com.system.launcher.tools.work.WorkProfilePackageReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val workProfileManager: WorkProfileManager,
    private val appRepository: AppRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
        private const val INTERNAL_FILE_MANAGER_SUFFIX = ".internal.filemanager"
        private const val INTERNAL_FILE_MANAGER_LABEL = "文件管理"
    }

    private val launcherApps: LauncherApps by lazy {
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    }

    private val _profileApps = MutableLiveData<List<AppInfo>>()
    val profileApps: LiveData<List<AppInfo>> = _profileApps

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private var pendingApkInstallCandidate: AppInfo? = null
    private var pendingApkInstallDiagnostic: String? = null
    private var pendingApkInstallBlocked: Boolean = false
    private var loadProfileAppsJob: Job? = null
    private var lastSubmittedHomeAppsKey: List<HomeAppUiKey> = emptyList()

    fun loadProfileApps(forceSubmit: Boolean = false) {
        loadProfileAppsJob?.cancel()
        loadProfileAppsJob = viewModelScope.launch {
            _loading.value = true
            try {
                val hasRenderedApps = !_profileApps.value.isNullOrEmpty()
                val cachedApps = withContext(Dispatchers.IO) {
                    ensureBaseEntriesCached()
                    ProfileAppStore.loadHomeApps(context)
                }
                if (!hasRenderedApps) {
                    Log.i(TAG, "Defer first home apps submit until refresh completes count=${cachedApps.size}")
                } else {
                    Log.i(TAG, "Skip cached home apps submit before refresh count=${cachedApps.size} force=$forceSubmit")
                }

                val refreshedApps = withContext(Dispatchers.IO) {
                    refreshCachedAppStates(cacheMissingIcons = true)
                    val refreshed = ProfileAppStore.loadHomeApps(context)
                    workProfileManager.rehideAppsInProfile(refreshed, "homeLoadProfileApps")
                    refreshed
                }
                submitHomeAppsIfChanged(refreshedApps, forceSubmit = forceSubmit || !hasRenderedApps)
            } finally {
                _loading.value = false
            }
        }
    }

    private fun submitHomeAppsIfChanged(apps: List<AppInfo>, forceSubmit: Boolean = false) {
        val key = apps.map(HomeAppUiKey::from)
        if (!forceSubmit && key == lastSubmittedHomeAppsKey) {
            Log.i(TAG, "Skip submitting unchanged home apps count=${apps.size}")
            return
        }
        lastSubmittedHomeAppsKey = key
        _profileApps.value = apps
        Log.i(TAG, "Submitted home apps count=${apps.size} force=$forceSubmit")
    }

    fun repairHomeAppIcons(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val repaired = withContext(Dispatchers.IO) { refreshCachedAppStates(cacheMissingIcons = true, forceIconRefresh = true) }
            loadProfileApps(forceSubmit = true)
            onComplete(repaired)
        }
    }

    fun tidyDesktopResidualIcons(onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            val hiddenCount = withContext(Dispatchers.IO) {
                val appsToHide = ProfileAppStore.loadApps(context)
                    .filterNot { it.packageName == getInternalFileManagerPackageName() }
                workProfileManager.forceHideAppsInProfile(appsToHide, "manualTidyDesktopResidualIcons")
            }
            onComplete(hiddenCount)
        }
    }

    private fun refreshCachedAppStates(
        cacheMissingIcons: Boolean,
        forceIconRefresh: Boolean = false
    ): Boolean {
        if (!workProfileManager.isProfileOwner()) return false
        var changed = false
        val receiver = WorkProfilePackageReceiver()
        ProfileAppStore.loadApps(context).forEach { app ->
            if (app.packageName == getInternalFileManagerPackageName()) return@forEach

            val installedNow = workProfileManager.isPackageInstalledInProfile(app.packageName)
            val installVerification = when {
                installedNow -> InstallVerification.CONFIRMED_INSTALLED
                app.installVerification == InstallVerification.CONFIRMED_MISSING -> InstallVerification.CONFIRMED_MISSING
                else -> InstallVerification.UNKNOWN
            }
            val launchVerification = if (installVerification == InstallVerification.CONFIRMED_MISSING) {
                LaunchVerification.NOT_LAUNCHABLE
            } else {
                workProfileManager.resolveLaunchVerificationInProfile(app.packageName, installVerification)
            }
            val diagnostic = buildDiagnostic(installVerification, launchVerification, app)
            val stateChanged = app.installVerification != installVerification ||
                app.launchVerification != launchVerification ||
                app.diagnosticReason != diagnostic
            if (stateChanged) {
                ProfileAppStore.updateVerificationState(
                    context = context,
                    packageName = app.packageName,
                    installVerification = installVerification,
                    launchVerification = launchVerification,
                    diagnosticReason = diagnostic
                )
                changed = true
            }

            if (installVerification == InstallVerification.CONFIRMED_INSTALLED &&
                (forceIconRefresh || (cacheMissingIcons && app.iconStatus != IconStatus.OK))
            ) {
                val cached = receiver.cacheAppMetadata(context, app.packageName)
                changed = changed || cached
            }
        }
        return changed
    }

    private fun buildDiagnostic(
        installVerification: InstallVerification,
        launchVerification: LaunchVerification,
        app: AppInfo
    ): String {
        return when {
            app.packageName == getInternalFileManagerPackageName() -> ""
            installVerification == InstallVerification.CONFIRMED_MISSING -> "应用已卸载，记录仍保留，可在应用设置中移除"
            installVerification == InstallVerification.UNKNOWN && app.entrySource == AppEntrySource.SYSTEM_CANDIDATE -> "系统候选入口，当前无法确认是否已安装在隐藏空间中"
            installVerification == InstallVerification.UNKNOWN -> "缓存存在，但当前无法确认应用是否仍安装在隐藏空间中"
            launchVerification == LaunchVerification.NOT_LAUNCHABLE -> "未找到可启动入口，可能是系统组件或启动入口被系统限制"
            app.iconStatus != IconStatus.OK -> "应用图标需要修复"
            else -> ""
        }
    }

    private fun ensureBaseEntriesCached() {
        val existingApps = ProfileAppStore.loadApps(context).associateBy { it.packageName }
        ensureInternalFileManagerCached(existingApps[getInternalFileManagerPackageName()])
        val candidatesToUpsert = appRepository.getSystemCandidateApps()
            .filter { candidate -> shouldUpsertBaseEntry(existingApps[candidate.packageName], candidate) }
        if (candidatesToUpsert.isNotEmpty()) {
            ProfileAppStore.upsertApps(context, candidatesToUpsert)
        }
    }

    private fun ensureInternalFileManagerCached(existing: AppInfo?) {
        val needsUpsert = existing == null ||
            existing.appName != INTERNAL_FILE_MANAGER_LABEL ||
            existing.entrySource != AppEntrySource.INTERNAL ||
            existing.iconStatus != IconStatus.OK ||
            existing.installVerification != InstallVerification.CONFIRMED_INSTALLED ||
            existing.launchVerification != LaunchVerification.LAUNCHABLE
        if (!needsUpsert) return

        ProfileAppStore.upsertApp(
            context,
            AppInfo(
                packageName = getInternalFileManagerPackageName(),
                appName = INTERNAL_FILE_MANAGER_LABEL,
                icon = ContextCompat.getDrawable(context, R.drawable.ic_internal_file_manager),
                isSystemApp = true,
                showOnHome = true,
                entrySource = AppEntrySource.INTERNAL,
                installVerification = InstallVerification.CONFIRMED_INSTALLED,
                launchVerification = LaunchVerification.LAUNCHABLE,
                iconStatus = IconStatus.OK,
                diagnosticReason = ""
            )
        )
    }

    private fun shouldUpsertBaseEntry(existing: AppInfo?, candidate: AppInfo): Boolean {
        existing ?: return true
        if (existing.appName != candidate.appName && candidate.appName.isNotBlank()) return true
        if (!existing.isSystemApp && candidate.isSystemApp) return true
        if (existing.entrySource != AppEntrySource.SYSTEM_CANDIDATE) return true
        if (candidate.installVerification != InstallVerification.UNKNOWN && existing.installVerification != candidate.installVerification) return true
        if (candidate.launchVerification != LaunchVerification.UNKNOWN && existing.launchVerification != candidate.launchVerification) return true
        if (existing.iconStatus != IconStatus.OK && candidate.icon != null) return true
        if (existing.diagnosticReason != candidate.diagnosticReason && candidate.installVerification != InstallVerification.UNKNOWN) return true
        return false
    }

    fun isInternalFileManagerApp(app: AppInfo): Boolean {
        return app.packageName == getInternalFileManagerPackageName()
    }

    private fun getInternalFileManagerPackageName(): String {
        return context.packageName + INTERNAL_FILE_MANAGER_SUFFIX
    }

    fun isFileManagerApp(app: AppInfo): Boolean {
        return workProfileManager.isFileManagerPackage(app.packageName)
    }

    fun canAttemptLaunch(app: AppInfo): Boolean {
        if (app.installVerification == InstallVerification.CONFIRMED_MISSING) return false
        return app.canAttemptLaunch || workProfileManager.isKnownProfileLaunchTool(app.packageName)
    }

    fun hideFromHome(app: AppInfo, onComplete: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ProfileAppStore.setShowOnHome(context, app.packageName, false) }
            loadProfileApps()
            onComplete()
        }
    }

    fun reorderHomeApps(packageNames: List<String>) {
        if (packageNames.isEmpty()) return
        viewModelScope.launch {
            val reorderedApps = withContext(Dispatchers.IO) {
                ProfileAppStore.reorderHomeApps(context, packageNames)
                ProfileAppStore.loadHomeApps(context)
            }
            submitHomeAppsIfChanged(reorderedApps, forceSubmit = true)
        }
    }

    fun validateInstallEntry(): String? {
        if (!workProfileManager.isProfileOwner()) {
            return when (workProfileManager.connectionState()) {
                WorkProfileConnectionState.CONNECTED_MANAGED_PROFILE -> {
                    workProfileManager.requestManagedProfileAvailable()
                    "当前不在工作资料实例中，请恢复并从工作资料入口打开后再添加应用"
                }
                WorkProfileConnectionState.OTHER_PROFILE_PRESENT -> {
                    "现有工作资料不受 VeilSpace 管理，请先在引导页处理资料冲突"
                }
                WorkProfileConnectionState.NO_PROFILE -> "尚未创建 VeilSpace 工作资料"
                WorkProfileConnectionState.CURRENT_PROFILE_OWNER -> null
            }
        }
        workProfileManager.configureCrossProfileEntry()
        return null
    }

    fun canRequestPackageInstalls(): Boolean {
        return workProfileManager.canRequestPackageInstalls()
    }

    fun createUnknownAppSourcesIntent(): Intent? {
        return workProfileManager.createUnknownAppSourcesIntent()
    }

    fun createApkInstallIntent(apkUri: Uri): Intent {
        return workProfileManager.createApkInstallIntent(apkUri)
    }

    fun prepareApkInstallCandidate(apkUri: Uri, onComplete: () -> Unit) {
        viewModelScope.launch {
            pendingApkInstallDiagnostic = null
            pendingApkInstallBlocked = false
            pendingApkInstallCandidate = withContext(Dispatchers.IO) { parseApkInstallCandidate(apkUri) }
            onComplete()
        }
    }

    fun finalizePendingApkInstall() {
        val app = pendingApkInstallCandidate ?: return
        if (!workProfileManager.isProfileOwner()) {
            pendingApkInstallCandidate = null
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val installed = workProfileManager.isPackageInstalledInProfile(app.packageName)
            if (installed) {
                val cached = WorkProfilePackageReceiver().cacheAppMetadata(context, app.packageName)
                if (!cached) {
                    val launchVerification = workProfileManager.resolveLaunchVerificationInProfile(
                        app.packageName,
                        InstallVerification.CONFIRMED_INSTALLED
                    )
                    ProfileAppStore.upsertApp(
                        context,
                        app.copy(
                            entrySource = AppEntrySource.DISCOVERED_INSTALLED,
                            installVerification = InstallVerification.CONFIRMED_INSTALLED,
                            launchVerification = launchVerification,
                            iconStatus = if (app.icon == null) IconStatus.MISSING else IconStatus.OK,
                            diagnosticReason = buildDiagnostic(InstallVerification.CONFIRMED_INSTALLED, launchVerification, app)
                        )
                    )
                }
                ProfileAppStore.updateVerificationState(
                    context = context,
                    packageName = app.packageName,
                    installVerification = InstallVerification.CONFIRMED_INSTALLED,
                    launchVerification = workProfileManager.resolveLaunchVerificationInProfile(
                        app.packageName,
                        InstallVerification.CONFIRMED_INSTALLED
                    ),
                    diagnosticReason = "",
                    entrySource = AppEntrySource.DISCOVERED_INSTALLED
                )
                Log.i(TAG, "Finalized pending APK install candidate: ${app.packageName} cached=$cached")
            } else {
                Log.w(TAG, "APK install candidate was not installed in profile: ${app.packageName}")
            }
            pendingApkInstallCandidate = null
            withContext(Dispatchers.Main) { loadProfileApps() }
        }
    }

    private fun parseApkInstallCandidate(apkUri: Uri): AppInfo? {
        return try {
            val cacheFile = File(context.cacheDir, "pending_install_candidate.apk")
            context.contentResolver.openInputStream(apkUri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            val pm = context.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    cacheFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(cacheFile.absolutePath, PackageManager.GET_META_DATA)
            } ?: return null

            updatePendingApkInstallDiagnostic(packageInfo)

            val archiveAppInfo = packageInfo.applicationInfo ?: return null
            archiveAppInfo.sourceDir = cacheFile.absolutePath
            archiveAppInfo.publicSourceDir = cacheFile.absolutePath

            AppInfo(
                packageName = packageInfo.packageName,
                appName = archiveAppInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() } ?: packageInfo.packageName,
                icon = archiveAppInfo.loadIcon(pm),
                isSystemApp = false,
                showOnHome = true,
                entrySource = AppEntrySource.CACHED,
                installVerification = InstallVerification.UNKNOWN,
                launchVerification = LaunchVerification.UNKNOWN,
                iconStatus = IconStatus.OK
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing APK install candidate", e)
            null
        }
    }

    private fun updatePendingApkInstallDiagnostic(apkPackageInfo: PackageInfo) {
        val installedVersion = getInstalledPackageVersion(apkPackageInfo.packageName) ?: return
        val apkVersionCode = apkPackageInfo.longVersionCodeCompat()
        if (installedVersion.versionCode == null) {
            pendingApkInstallBlocked = false
            pendingApkInstallDiagnostic = "检测到主空间已存在同包名应用：${apkPackageInfo.packageName}。\n\n当前系统可能按全局包版本判断安装结果；如果继续安装后提示无法降级，说明你选择的 APK 低于主空间已安装版本。\n\n建议使用同版本或更高版本 APK。"
            return
        }
        if (apkVersionCode < installedVersion.versionCode) {
            pendingApkInstallBlocked = true
            pendingApkInstallDiagnostic = "安装包版本低于系统已存在版本，Android 会拒绝降级安装。\n\n安装包：${apkPackageInfo.versionName ?: "未知版本"} / versionCode $apkVersionCode\n已存在：${installedVersion.versionName ?: "主空间已安装版本"} / versionCode ${installedVersion.versionCode}\n\n请改用同版本或更高版本 APK。"
        }
    }

    private fun getInstalledPackageVersion(packageName: String): InstalledPackageVersion? {
        getInstalledPackageInfo(packageName)?.let { packageInfo -> return InstalledPackageVersion(packageInfo.versionName, packageInfo.longVersionCodeCompat()) }
        return getPersonalProfilePackageVersion(packageName)
    }

    private fun getInstalledPackageInfo(packageName: String): PackageInfo? {
        val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, flags)
            }
        }.getOrNull()
    }

    private fun getPersonalProfilePackageVersion(packageName: String): InstalledPackageVersion? {
        val personalUser = workProfileManager.getPersonalProfileUserHandle() ?: return null
        return runCatching {
            launcherApps.getApplicationInfo(packageName, 0, personalUser)
            InstalledPackageVersion(null, null)
        }.onFailure { error -> Log.w(TAG, "Unable to query personal profile package version: $packageName", error) }.getOrNull()
    }

    private data class HomeAppUiKey(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean,
        val showOnHome: Boolean,
        val sortOrder: Int,
        val keepAlive: Boolean,
        val entrySource: AppEntrySource,
        val installVerification: InstallVerification,
        val launchVerification: LaunchVerification,
        val iconStatus: IconStatus,
        val diagnosticReason: String
    ) {
        companion object {
            fun from(app: AppInfo): HomeAppUiKey {
                return HomeAppUiKey(
                    packageName = app.packageName,
                    appName = app.appName,
                    isSystemApp = app.isSystemApp,
                    showOnHome = app.showOnHome,
                    sortOrder = app.sortOrder,
                    keepAlive = app.keepAlive,
                    entrySource = app.entrySource,
                    installVerification = app.installVerification,
                    launchVerification = app.launchVerification,
                    iconStatus = app.iconStatus,
                    diagnosticReason = app.diagnosticReason
                )
            }
        }
    }
    private data class InstalledPackageVersion(val versionName: String?, val versionCode: Long?)

    private fun PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
    }

    fun getPendingApkInstallDiagnostic(): String? = pendingApkInstallDiagnostic

    fun isPendingApkInstallBlocked(): Boolean = pendingApkInstallBlocked

    fun clearPendingApkInstallCandidate() {
        pendingApkInstallCandidate = null
        pendingApkInstallDiagnostic = null
        pendingApkInstallBlocked = false
    }

    fun createDeleteDocumentsIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    fun deleteDocuments(uris: List<Uri>, onComplete: (deleted: Int, failed: Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var deleted = 0
            var failed = 0
            uris.distinct().forEach { uri ->
                val success = try {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    DocumentsContract.deleteDocument(context.contentResolver, uri) || context.contentResolver.delete(uri, null, null) > 0
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting document: $uri", e)
                    false
                }
                if (success) deleted++ else failed++
            }
            withContext(Dispatchers.Main) { onComplete(deleted, failed) }
        }
    }

    fun repairProfileInstallEnvironment(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) { workProfileManager.repairProfileInstallEnvironment() }
            onComplete(success)
        }
    }

    fun launchApp(app: AppInfo): Boolean {
        if (app.installVerification == InstallVerification.CONFIRMED_MISSING) return false
        val preparedApp = prepareAppForLaunch(app)
        val knownPolicyLaunchTool = workProfileManager.isKnownProfileLaunchTool(preparedApp.packageName) ||
            preparedApp.launchVerification == LaunchVerification.POLICY_LAUNCH_ONLY
        if (!preparedApp.canAttemptLaunch && !knownPolicyLaunchTool) return false
        val success = workProfileManager.launchAppInProfile(preparedApp.packageName)
        val installVerification = if (success) {
            InstallVerification.CONFIRMED_INSTALLED
        } else {
            preparedApp.installVerification
        }
        val launchVerification = if (success) {
            workProfileManager.resolveLaunchVerificationInProfile(preparedApp.packageName, installVerification)
        } else if (knownPolicyLaunchTool) {
            LaunchVerification.POLICY_LAUNCH_ONLY
        } else {
            LaunchVerification.NOT_LAUNCHABLE
        }
        ProfileAppStore.updateVerificationState(
            context = context,
            packageName = preparedApp.packageName,
            installVerification = installVerification,
            launchVerification = launchVerification,
            diagnosticReason = when {
                success -> ""
                knownPolicyLaunchTool -> "策略入口启动失败，已保留入口，可重试或使用安装环境修复"
                else -> "启动失败，已保留入口但不会删除缓存记录"
            },
            entrySource = preparedApp.entrySource
        )
        return success
    }

    private fun prepareAppForLaunch(app: AppInfo): AppInfo {
        if (app.entrySource != AppEntrySource.SYSTEM_CANDIDATE || app.installVerification == InstallVerification.CONFIRMED_INSTALLED) {
            return app
        }
        if (app.launchVerification == LaunchVerification.POLICY_LAUNCH_ONLY ||
            workProfileManager.isKnownProfileLaunchTool(app.packageName)
        ) {
            val installed = workProfileManager.isPackageInstalledInProfile(app.packageName)
            val installVerification = if (installed) {
                InstallVerification.CONFIRMED_INSTALLED
            } else {
                app.installVerification
            }
            return app.copy(
                installVerification = installVerification,
                launchVerification = LaunchVerification.POLICY_LAUNCH_ONLY,
                diagnosticReason = buildDiagnostic(installVerification, LaunchVerification.POLICY_LAUNCH_ONLY, app)
            )
        }
        val prepared = workProfileManager.prepareSystemCandidateInProfile(app.packageName)
        val installVerification = if (prepared || workProfileManager.isPackageInstalledInProfile(app.packageName)) {
            InstallVerification.CONFIRMED_INSTALLED
        } else {
            InstallVerification.UNKNOWN
        }
        val launchVerification = workProfileManager.resolveLaunchVerificationInProfile(app.packageName, installVerification)
        val updated = app.copy(
            installVerification = installVerification,
            launchVerification = launchVerification,
            diagnosticReason = buildDiagnostic(installVerification, launchVerification, app)
        )
        ProfileAppStore.updateVerificationState(
            context = context,
            packageName = app.packageName,
            installVerification = installVerification,
            launchVerification = launchVerification,
            diagnosticReason = updated.diagnosticReason,
            entrySource = AppEntrySource.SYSTEM_CANDIDATE
        )
        return updated
    }
}
