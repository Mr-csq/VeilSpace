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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.data.repository.ProfileAppStore
import com.system.launcher.tools.work.WorkProfileManager
import com.system.launcher.tools.work.WorkProfilePackageReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val workProfileManager: WorkProfileManager,
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


    fun loadProfileApps() {
        viewModelScope.launch {
            _loading.value = true
            val cachedApps = withContext(Dispatchers.IO) {
                ensureInternalFileManagerCached()
                ProfileAppStore.loadHomeApps(context)
            }
            _profileApps.value = cachedApps
            _loading.value = false

            viewModelScope.launch(Dispatchers.IO) {
                refreshCachedAppStates(cacheMissingIcons = true)
                val refreshedApps = ProfileAppStore.loadHomeApps(context)
                withContext(Dispatchers.Main) { _profileApps.value = refreshedApps }
            }
        }
    }

    fun repairHomeAppIcons(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val repaired = withContext(Dispatchers.IO) { refreshCachedAppStates(cacheMissingIcons = true, forceIconRefresh = true) }
            loadProfileApps()
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

            val installed = workProfileManager.isPackageInstalledInProfile(app.packageName)
            val launchable = installed && workProfileManager.canLaunchPackageInProfile(app.packageName)
            val diagnostic = buildDiagnostic(installed, launchable, app)
            ProfileAppStore.updateSystemState(
                context = context,
                packageName = app.packageName,
                installed = installed,
                launchable = launchable,
                diagnosticReason = diagnostic
            )
            changed = true

            if (installed && (forceIconRefresh || (cacheMissingIcons && app.iconStatus != IconStatus.OK))) {
                val cached = receiver.cacheAppMetadata(context, app.packageName)
                changed = changed || cached
            }
        }
        return changed
    }

    private fun buildDiagnostic(installed: Boolean, launchable: Boolean, app: AppInfo): String {
        return when {
            app.packageName == getInternalFileManagerPackageName() -> ""
            !installed -> "应用当前未安装在隐藏空间中"
            !launchable -> "未找到可启动入口，可能是系统组件或启动入口被系统限制"
            app.iconStatus != IconStatus.OK -> "应用图标需要修复"
            else -> ""
        }
    }

    private fun ensureInternalFileManagerCached() {
        ProfileAppStore.upsertApp(
            context,
            AppInfo(
                packageName = getInternalFileManagerPackageName(),
                appName = INTERNAL_FILE_MANAGER_LABEL,
                icon = null,
                isSystemApp = true,
                showOnHome = true,
                installed = true,
                launchable = true,
                diagnosticReason = ""
            )
        )
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

    fun hideFromHome(app: AppInfo, onComplete: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ProfileAppStore.setShowOnHome(context, app.packageName, false) }
            loadProfileApps()
            onComplete()
        }
    }

    fun validateInstallEntry(): String? {
        if (!workProfileManager.canUseWorkProfileFeatures()) {
            return "未检测到可用的工作资料，请先完成创建或授权"
        }
        if (!workProfileManager.isProfileOwner()) {
            workProfileManager.requestManagedProfileAvailable()
            return "当前不在工作资料实例中，请从工作资料入口打开后再添加应用"
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
                    ProfileAppStore.upsertApp(context, app.copy(installed = true, launchable = true, iconStatus = if (app.icon == null) IconStatus.MISSING else IconStatus.OK))
                }
                ProfileAppStore.updateSystemState(
                    context = context,
                    packageName = app.packageName,
                    installed = true,
                    launchable = workProfileManager.canLaunchPackageInProfile(app.packageName),
                    diagnosticReason = ""
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
                installed = false,
                launchable = false,
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
        if (!app.installed) return false
        return workProfileManager.launchAppInProfile(app.packageName)
    }
}






