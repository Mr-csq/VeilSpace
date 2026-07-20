package com.system.launcher.tools.automation

import android.content.Context
import com.system.launcher.tools.data.repository.ProfileAppPolicyStore
import com.system.launcher.tools.data.repository.ProfileAppStore
import com.system.launcher.tools.work.WorkProfileManager
import com.system.launcher.tools.work.SafeProfileHideResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class KeepAliveChangeResult(
    val status: AutomationOperationStatus,
    val detail: String = ""
)

/** Single domain entry point for both manual and scheduled keepAlive changes. */
@Singleton
class ProfileAppKeepAliveController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workProfileManager: WorkProfileManager
) {
    @Synchronized
    fun setKeepAlive(packageName: String, enabled: Boolean, reason: String): KeepAliveChangeResult {
        if (!workProfileManager.isProfileOwner()) {
            return KeepAliveChangeResult(
                AutomationOperationStatus.NO_PROFILE_OWNER,
                "当前实例不是工作资料 Profile Owner"
            )
        }

        val policy = ProfileAppPolicyStore.resolvePolicy(context, packageName)
        if (!enabled) {
            ProfileAppPolicyStore.setKeepAliveApp(context, packageName, false)
            ProfileAppStore.setKeepAlive(context, packageName, false)
            if (!workProfileManager.isPackageInstalledInProfile(packageName)) {
                return KeepAliveChangeResult(AutomationOperationStatus.NOT_INSTALLED, "应用未安装，已清理本地 keepAlive 状态")
            }
            val policyAfterDisable = ProfileAppPolicyStore.resolvePolicy(context, packageName)
            if (!policyAfterDisable.canAutoHide) {
                return KeepAliveChangeResult(AutomationOperationStatus.APPLIED, "静态策略要求应用保持可见，keepAlive 已关闭")
            }
            return when (workProfileManager.requestSafeHideAfterKeepAliveDisabled(packageName, reason)) {
                SafeProfileHideResult.DEFERRED_UNTIL_BACKGROUND -> KeepAliveChangeResult(
                    AutomationOperationStatus.DEFERRED_UNTIL_BACKGROUND,
                    "应用仍在前台，将在退出后按现有策略隐藏"
                )
                SafeProfileHideResult.HIDDEN -> KeepAliveChangeResult(
                    AutomationOperationStatus.APPLIED,
                    "keepAlive 已关闭"
                )
                SafeProfileHideResult.NOT_APPLICABLE -> KeepAliveChangeResult(
                    AutomationOperationStatus.POLICY_NOT_ALLOWED,
                    "当前策略不允许自动隐藏"
                )
                SafeProfileHideResult.FAILED -> KeepAliveChangeResult(
                    AutomationOperationStatus.FAILED,
                    "系统未确认应用隐藏状态"
                )
            }
        }

        if (policy.shouldNeverAutoHide) {
            ProfileAppPolicyStore.setKeepAliveApp(context, packageName, false)
            ProfileAppStore.setKeepAlive(context, packageName, false)
            if (workProfileManager.isPackageInstalledInProfile(packageName)) {
                workProfileManager.unhideAppInProfile(packageName)
            }
            return KeepAliveChangeResult(
                AutomationOperationStatus.POLICY_NOT_ALLOWED,
                "静态策略已要求应用保持可见，不使用 keepAlive 开关"
            )
        }
        if (!policy.staticPolicy.userKeepAliveAllowed) {
            ProfileAppPolicyStore.setKeepAliveApp(context, packageName, false)
            ProfileAppStore.setKeepAlive(context, packageName, false)
            return KeepAliveChangeResult(
                AutomationOperationStatus.POLICY_NOT_ALLOWED,
                "静态策略不允许为该应用启用 keepAlive"
            )
        }
        if (!workProfileManager.isPackageInstalledInProfile(packageName)) {
            return KeepAliveChangeResult(AutomationOperationStatus.NOT_INSTALLED, "应用未安装在工作资料中")
        }

        return runCatching {
            ProfileAppPolicyStore.setKeepAliveApp(context, packageName, true)
            ProfileAppStore.setKeepAlive(context, packageName, true)
            workProfileManager.unhideAppInProfile(packageName)
            if (workProfileManager.isAppHiddenInProfile(packageName) == false) {
                KeepAliveChangeResult(AutomationOperationStatus.APPLIED, "keepAlive 已启用并取消隐藏")
            } else {
                ProfileAppPolicyStore.setKeepAliveApp(context, packageName, false)
                ProfileAppStore.setKeepAlive(context, packageName, false)
                KeepAliveChangeResult(AutomationOperationStatus.FAILED, "系统未确认取消隐藏，keepAlive 状态已回滚")
            }
        }.getOrElse { error ->
            KeepAliveChangeResult(AutomationOperationStatus.FAILED, error.message ?: "keepAlive 操作失败")
        }
    }
}
