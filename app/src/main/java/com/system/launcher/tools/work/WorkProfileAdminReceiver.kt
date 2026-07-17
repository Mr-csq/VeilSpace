package com.system.launcher.tools.work

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Work Profile 设备管理接收器
 * 处理 Profile 生命周期回调
 */
class WorkProfileAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "WorkProfileAdmin"
    }

    /**
     * Profile 激活成功
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Work Profile enabled")

        // 保存激活状态
        saveProfileState(context, true)
        WorkProfileManager(context).configureCrossProfileEntry()

        // 可选：显示提示
        // Toast.makeText(context, "安全工作空间已激活", Toast.LENGTH_SHORT).show()
    }

    /**
     * Profile 被停用
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Work Profile disabled")

        // 更新状态
        saveProfileState(context, false)

        // Toast.makeText(context, "安全工作空间已停用", Toast.LENGTH_SHORT).show()
    }

    /**
     * Profile 创建完成
     * Android 16 工作资料创建完成回调
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning complete")

        // 标记 Profile 已创建
        saveProfileState(context, true)
        markProvisioningComplete(context)
        WorkProfileManager(context).configureCrossProfileEntry()
    }

    /**
     * 锁定任务模式启动
     */
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.i(TAG, "Lock task mode entering: $pkg")
    }

    /**
     * 锁定任务模式退出
     */
    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.i(TAG, "Lock task mode exiting")
    }

    /**
     * 保存 Profile 状态到 SharedPreferences
     */
    private fun saveProfileState(context: Context, enabled: Boolean) {
        context.getSharedPreferences("work_profile_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("profile_enabled", enabled)
            .putLong("last_update_time", System.currentTimeMillis())
            .apply()
    }

    /**
     * 标记 Provisioning 已完成
     */
    private fun markProvisioningComplete(context: Context) {
        context.getSharedPreferences("work_profile_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("provisioning_complete", true)
            .putLong("provisioning_time", System.currentTimeMillis())
            .apply()
    }
}

