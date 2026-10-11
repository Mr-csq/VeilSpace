package com.system.launcher.tools.ui.home

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.system.launcher.tools.R
import com.system.launcher.tools.automation.ProfileAppKeepAliveController
import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
import com.system.launcher.tools.data.repository.AppRepository
import com.system.launcher.tools.data.repository.ProfileAppPolicyStore
import com.system.launcher.tools.data.repository.ProfileAppStore
import com.system.launcher.tools.work.ProfileAppUninstallCleaner
import com.system.launcher.tools.work.WorkProfileManager
import com.system.launcher.tools.work.WorkProfilePackageReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class AppManagementViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val workProfileManager: WorkProfileManager,
    private val keepAliveController: ProfileAppKeepAliveController,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val INTERNAL_FILE_MANAGER_SUFFIX = ".internal.filemanager"
        private const val INTERNAL_FILE_MANAGER_LABEL = "文件管理"
    }

    private val _apps = MutableLiveData<List<AppInfo>>()
    val apps: LiveData<List<AppInfo>> = _apps

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading
    private var loadAppsJob: Job? = null
    private var appStateRevision = 0L

    init {
        loadApps(discover = true)
    }

    fun loadApps(discover: Boolean = false) {
        loadAppsJob?.cancel()
        val revision = appStateRevision
        loadAppsJob = viewModelScope.launch {
            val cachedApps = withContext(Dispatchers.IO) {
                ensureBaseEntriesCached()
                ProfileAppStore.loadApps(context)
            }
            if (revision != appStateRevision) return@launch
            _apps.value = cachedApps

            if (!discover) {
                _loading.value = false
                return@launch
            }

            _loading.value = true
            val refreshedApps = withContext(Dispatchers.IO) {
                if (workProfileManager.isProfileOwner()) discoverProfileApps()
                discoverSystemCandidates()
                refreshStatuses(cacheMissingIcons = true)
                ProfileAppUninstallCleaner.cleanupConfirmedUninstalledApps(context, workProfileManager)
                ProfileAppStore.loadApps(context)
            }
            if (revision != appStateRevision) return@launch
            _apps.value = refreshedApps
            _loading.value = false
        }
    }

    fun refreshAll() {
        loadApps(discover = true)
    }

    fun setShowOnHome(app: AppInfo, show: Boolean) {
        val revision = beginUserMutation()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ProfileAppStore.setShowOnHome(context, app.packageName, show)
                }.getOrElse {
                    ProfileAppStore.loadApps(context)
                }
            }
            publishUserMutation(revision, result)
        }
    }

    fun setKeepAlive(app: AppInfo, keepAlive: Boolean) {
        val revision = beginUserMutation()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    keepAliveController.setKeepAlive(
                        packageName = app.packageName,
                        enabled = keepAlive,
                        reason = "manualAppManagement"
                    )
                    ProfileAppStore.loadApps(context)
                }.getOrElse {
                    ProfileAppStore.loadApps(context)
                }
            }
            publishUserMutation(revision, result)
        }
    }

    fun refreshIcon(app: AppInfo) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (app.packageName != getInternalFileManagerPackageName() && workProfileManager.isPackageInstalledInProfile(app.packageName)) {
                    WorkProfilePackageReceiver().cacheAppMetadata(context, app.packageName)
                }
                refreshStatuses(cacheMissingIcons = false)
                ProfileAppStore.loadApps(context)
            }
            _apps.value = result
        }
    }

    fun removeRecord(app: AppInfo) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProfileAppUninstallCleaner.removeHiddenSpaceRecord(context, app.packageName)
            }
            _apps.value = result
        }
    }

    fun createUninstallIntent(app: AppInfo): Intent {
        return workProfileManager.createUninstallIntent(app.packageName)
    }

    fun finalizeUninstall(app: AppInfo, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                val installVerification = awaitPostUninstallVerification(app.packageName)
                val launchVerification = if (installVerification == InstallVerification.CONFIRMED_INSTALLED) {
                    workProfileManager.resolveLaunchVerificationInProfile(app.packageName, installVerification)
                } else {
                    LaunchVerification.NOT_LAUNCHABLE
                }
                if (installVerification == InstallVerification.CONFIRMED_INSTALLED) {
                    ProfileAppStore.updateVerificationState(
                        context = context,
                        packageName = app.packageName,
                        installVerification = installVerification,
                        launchVerification = launchVerification,
                        diagnosticReason = "卸载未完成，应用仍在隐藏空间中"
                    )
                } else if (installVerification == InstallVerification.CONFIRMED_MISSING) {
                    // 系统确认卸载成功后，同步清除隐藏空间记录、策略和图标缓存。
                    ProfileAppUninstallCleaner.removeHiddenSpaceRecord(context, app.packageName)
                } else {
                    ProfileAppStore.updateVerificationState(
                        context = context,
                        packageName = app.packageName,
                        installVerification = InstallVerification.UNKNOWN,
                        launchVerification = LaunchVerification.UNKNOWN,
                        diagnosticReason = "系统暂时无法确认卸载结果，请刷新后重试"
                    )
                }
                installVerification == InstallVerification.CONFIRMED_MISSING
            }
            _apps.value = ProfileAppStore.loadApps(context)
            onComplete(removed)
        }
    }

    private fun beginUserMutation(): Long {
        loadAppsJob?.cancel()
        _loading.value = false
        appStateRevision += 1
        return appStateRevision
    }

    private suspend fun awaitPostUninstallVerification(packageName: String): InstallVerification {
        repeat(8) { attempt ->
            val verification = workProfileManager.resolveInstallVerificationInProfile(packageName)
            if (verification != InstallVerification.CONFIRMED_INSTALLED) return verification
            if (attempt < 7) delay(250L)
        }
        return InstallVerification.CONFIRMED_INSTALLED
    }

    private fun publishUserMutation(revision: Long, apps: List<AppInfo>) {
        if (revision != appStateRevision) return
        appStateRevision += 1
        _apps.value = apps
    }

    fun autoHideStatusLabel(app: AppInfo): String {
        return ProfileAppPolicyStore.autoHideStatusLabel(context, app.packageName)
    }

    fun isInternalFileManagerApp(app: AppInfo): Boolean {
        return app.packageName == getInternalFileManagerPackageName()
    }

    private fun discoverProfileApps() {
        val discovered = appRepository.getInstalledProfileApps()
            .filter { it.packageName != context.packageName }
            .map { app ->
                val installVerification = InstallVerification.CONFIRMED_INSTALLED
                val launchVerification = workProfileManager.resolveLaunchVerificationInProfile(app.packageName, installVerification)
                app.copy(
                    showOnHome = ProfileAppStore.containsApp(context, app.packageName) && app.showOnHome,
                    entrySource = AppEntrySource.DISCOVERED_INSTALLED,
                    installVerification = installVerification,
                    launchVerification = launchVerification,
                    diagnosticReason = buildDiagnostic(installVerification, launchVerification, app)
                )
            }
        ProfileAppStore.upsertApps(context, discovered)
    }

    private fun discoverSystemCandidates() {
        ProfileAppStore.upsertApps(context, appRepository.getSystemCandidateApps())
    }

    private fun refreshStatuses(cacheMissingIcons: Boolean): Boolean {
        if (!workProfileManager.isProfileOwner()) return false
        var changed = false
        val receiver = WorkProfilePackageReceiver()
        ProfileAppStore.loadApps(context).forEach { app ->
            if (app.packageName == getInternalFileManagerPackageName()) return@forEach
            val installVerification = workProfileManager.resolveInstallVerificationInProfile(app.packageName)
            val launchVerification = if (installVerification == InstallVerification.CONFIRMED_MISSING) {
                LaunchVerification.NOT_LAUNCHABLE
            } else {
                workProfileManager.resolveLaunchVerificationInProfile(app.packageName, installVerification)
            }
            val diagnostic = buildDiagnostic(installVerification, launchVerification, app)
            ProfileAppStore.updateVerificationState(context, app.packageName, installVerification, launchVerification, diagnostic)
            if (installVerification == InstallVerification.CONFIRMED_INSTALLED && cacheMissingIcons && app.iconStatus != IconStatus.OK) {
                receiver.cacheAppMetadata(context, app.packageName)
            }
            changed = true
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
            installVerification == InstallVerification.CONFIRMED_MISSING -> "应用已卸载，记录仍保留，可手动移除"
            installVerification == InstallVerification.UNKNOWN && app.entrySource == AppEntrySource.SYSTEM_CANDIDATE -> "系统候选入口，当前无法确认是否已安装在隐藏空间中"
            installVerification == InstallVerification.UNKNOWN -> "缓存存在，但当前无法确认应用是否仍安装在隐藏空间中"
            launchVerification == LaunchVerification.NOT_LAUNCHABLE -> "未找到可启动入口，可能是系统组件或启动入口被系统限制"
            app.iconStatus != IconStatus.OK -> "应用图标需要修复"
            else -> ""
        }
    }

    private fun ensureBaseEntriesCached() {
        ensureInternalFileManagerCached()
        discoverSystemCandidates()
    }

    private fun ensureInternalFileManagerCached() {
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
                iconStatus = IconStatus.OK
            )
        )
    }

    private fun getInternalFileManagerPackageName(): String {
        return context.packageName + INTERNAL_FILE_MANAGER_SUFFIX
    }
}

