package com.system.launcher.tools.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.data.repository.AppRepository
import com.system.launcher.tools.data.repository.ProfileAppPolicyStore
import com.system.launcher.tools.data.repository.ProfileAppStore
import com.system.launcher.tools.work.WorkProfileManager
import com.system.launcher.tools.work.WorkProfilePackageReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class AppManagementViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val workProfileManager: WorkProfileManager,
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

    init {
        loadApps(discover = true)
    }

    fun loadApps(discover: Boolean = false) {
        viewModelScope.launch {
            val cachedApps = withContext(Dispatchers.IO) {
                ensureInternalFileManagerCached()
                ProfileAppStore.loadApps(context)
            }
            _apps.value = cachedApps

            if (!discover) {
                _loading.value = false
                return@launch
            }

            _loading.value = true
            val refreshedApps = withContext(Dispatchers.IO) {
                if (workProfileManager.isProfileOwner()) discoverProfileApps()
                refreshStatuses(cacheMissingIcons = true)
                ProfileAppStore.loadApps(context)
            }
            _apps.value = refreshedApps
            _loading.value = false
        }
    }

    fun refreshAll() {
        loadApps(discover = true)
    }

    fun setShowOnHome(app: AppInfo, show: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProfileAppStore.setShowOnHome(context, app.packageName, show)
            }
            _apps.value = result
        }
    }

    fun setKeepAlive(app: AppInfo, keepAlive: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProfileAppPolicyStore.setKeepAliveApp(context, app.packageName, keepAlive)
                ProfileAppStore.setKeepAlive(context, app.packageName, keepAlive)
                if (keepAlive) {
                    workProfileManager.unhideAppInProfile(app.packageName)
                } else {
                    workProfileManager.hideAppInProfileIfAllowed(app.packageName, "managementKeepAliveDisabled")
                }
                ProfileAppStore.loadApps(context)
            }
            _apps.value = result
        }
    }

    fun move(app: AppInfo, direction: Int) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ProfileAppStore.moveHomeApp(context, app.packageName, direction) }
            _apps.value = result
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
                ProfileAppPolicyStore.removePackage(context, app.packageName)
                ProfileAppStore.removeApp(context, app.packageName)
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
                val installed = workProfileManager.isPackageInstalledInProfile(app.packageName)
                ProfileAppStore.updateSystemState(
                    context = context,
                    packageName = app.packageName,
                    installed = installed,
                    launchable = installed && workProfileManager.canLaunchPackageInProfile(app.packageName),
                    diagnosticReason = if (installed) "卸载未完成，应用仍在隐藏空间中" else "应用已卸载，记录仍保留，可手动移除"
                )
                !installed
            }
            _apps.value = ProfileAppStore.loadApps(context)
            onComplete(removed)
        }
    }

    fun isInternalFileManagerApp(app: AppInfo): Boolean {
        return app.packageName == getInternalFileManagerPackageName()
    }

    private fun discoverProfileApps() {
        val discovered = appRepository.getInstalledProfileApps()
            .filter { it.packageName != context.packageName }
            .map { app ->
                app.copy(
                    showOnHome = ProfileAppStore.containsApp(context, app.packageName) && app.showOnHome,
                    installed = true,
                    launchable = workProfileManager.canLaunchPackageInProfile(app.packageName),
                    diagnosticReason = ""
                )
            }
        ProfileAppStore.upsertApps(context, discovered)
    }

    private fun refreshStatuses(cacheMissingIcons: Boolean): Boolean {
        if (!workProfileManager.isProfileOwner()) return false
        var changed = false
        val receiver = WorkProfilePackageReceiver()
        ProfileAppStore.loadApps(context).forEach { app ->
            if (app.packageName == getInternalFileManagerPackageName()) return@forEach
            val installed = workProfileManager.isPackageInstalledInProfile(app.packageName)
            val launchable = installed && workProfileManager.canLaunchPackageInProfile(app.packageName)
            val diagnostic = when {
                !installed -> "应用当前未安装在隐藏空间中"
                !launchable -> "未找到可启动入口，可能是系统组件或启动入口被系统限制"
                app.iconStatus != IconStatus.OK -> "应用图标需要修复"
                else -> ""
            }
            ProfileAppStore.updateSystemState(context, app.packageName, installed, launchable, diagnostic)
            if (installed && cacheMissingIcons && app.iconStatus != IconStatus.OK) {
                receiver.cacheAppMetadata(context, app.packageName)
            }
            changed = true
        }
        return changed
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
                launchable = true
            )
        )
    }

    private fun getInternalFileManagerPackageName(): String {
        return context.packageName + INTERNAL_FILE_MANAGER_SUFFIX
    }
}

