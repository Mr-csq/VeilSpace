package com.system.launcher.tools.automation

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.system.launcher.tools.work.WorkProfileAdminReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class NotificationPermissionResult(
    val status: AutomationOperationStatus,
    val detail: String = ""
)

@Singleton
class NotificationPermissionController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val devicePolicyManager: DevicePolicyManager
        get() = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun setNotificationsGranted(packageName: String, granted: Boolean): NotificationPermissionResult {
        if (!devicePolicyManager.isProfileOwnerApp(context.packageName)) {
            return NotificationPermissionResult(
                AutomationOperationStatus.NO_PROFILE_OWNER,
                "当前实例不是工作资料 Profile Owner"
            )
        }
        val declared = runCatching {
            val info = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
            info.requestedPermissions.orEmpty().contains(Manifest.permission.POST_NOTIFICATIONS)
        }.getOrElse { error ->
            return NotificationPermissionResult(
                if (error is PackageManager.NameNotFoundException) {
                    AutomationOperationStatus.NOT_INSTALLED
                } else {
                    AutomationOperationStatus.FAILED
                },
                error.message ?: "无法读取应用权限声明"
            )
        }
        if (!declared) {
            return NotificationPermissionResult(
                AutomationOperationStatus.NOT_DECLARED_BY_APP,
                "目标应用未声明 POST_NOTIFICATIONS，未伪造权限操作结果"
            )
        }

        val admin = ComponentName(context, WorkProfileAdminReceiver::class.java)
        val desiredState = if (granted) {
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        } else {
            DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
        }
        return runCatching {
            val accepted = devicePolicyManager.setPermissionGrantState(
                admin,
                packageName,
                Manifest.permission.POST_NOTIFICATIONS,
                desiredState
            )
            val actual = devicePolicyManager.getPermissionGrantState(
                admin,
                packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (accepted && actual == desiredState) {
                NotificationPermissionResult(
                    AutomationOperationStatus.APPLIED,
                    if (granted) "通知权限已开启" else "通知权限已关闭"
                )
            } else {
                NotificationPermissionResult(
                    AutomationOperationStatus.FAILED,
                    "系统或厂商策略未确认通知权限变更"
                )
            }
        }.getOrElse { error ->
            NotificationPermissionResult(
                AutomationOperationStatus.FAILED,
                error.message ?: "通知权限操作失败"
            )
        }
    }
}
