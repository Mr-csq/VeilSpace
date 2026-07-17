package com.system.launcher.tools.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class AlarmScheduleStatus(
    val exactAlarmAvailable: Boolean,
    val scheduledExactly: Boolean,
    val nextBoundary: AutomationBoundary?,
    val message: String
)

@Singleton
class ExactAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calculator: AutomationScheduleCalculator
) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun exactAlarmAvailable(): Boolean {
        return alarmManager.canScheduleExactAlarms()
    }

    fun createExactAlarmPermissionIntent(): Intent? {
        if (exactAlarmAvailable()) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun reschedule(
        config: AutomationConfig,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): AlarmScheduleStatus {
        cancel()
        if (!config.enabled) {
            return AlarmScheduleStatus(exactAlarmAvailable(), false, null, "自动化已关闭")
        }
        val errors = config.validationErrors()
        if (errors.isNotEmpty()) {
            return AlarmScheduleStatus(exactAlarmAvailable(), false, null, errors.joinToString("；"))
        }
        val next = calculator.snapshot(config, now, zoneId).nextBoundary
            ?: return AlarmScheduleStatus(
                exactAlarmAvailable(),
                false,
                null,
                "当前法定工作日数据范围内没有可安排的下一边界"
            )
        val pendingIntent = boundaryPendingIntent(next)
        val exact = exactAlarmAvailable()
        val exactScheduled = if (exact) {
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next.scheduledAt.toEpochMilli(),
                    pendingIntent
                )
            }.onFailure { error ->
                Log.w(TAG, "Exact alarm rejected; falling back to an inexact alarm", error)
            }.isSuccess
        } else {
            false
        }
        val inexactScheduled = if (exactScheduled) {
            false
        } else {
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next.scheduledAt.toEpochMilli(),
                    pendingIntent
                )
            }.onFailure { error ->
                Log.e(TAG, "Unable to schedule automation alarm", error)
            }.isSuccess
        }
        return AlarmScheduleStatus(
            exactAlarmAvailable = exact,
            scheduledExactly = exactScheduled,
            nextBoundary = next,
            message = when {
                exactScheduled -> "下一边界已使用精确闹钟安排"
                inexactScheduled && exact -> "系统拒绝精确闹钟，已安全降级，执行可能延迟"
                inexactScheduled -> "未授权精确闹钟，系统可能延迟执行"
                else -> "系统未能安排下一边界，请检查闹钟权限或厂商限制"
            }
        )
    }

    fun cancel() {
        runCatching { alarmManager.cancel(boundaryPendingIntent(null)) }
            .onFailure { error -> Log.w(TAG, "Unable to cancel previous automation alarm", error) }
    }

    private fun boundaryPendingIntent(boundary: AutomationBoundary?): PendingIntent {
        val intent = Intent(context, AutomationBoundaryReceiver::class.java).apply {
            action = ACTION_AUTOMATION_BOUNDARY
            boundary?.let {
                putExtra(EXTRA_BOUNDARY_ID, it.id)
                putExtra(EXTRA_BOUNDARY_REVISION, it.revision)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BOUNDARY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_AUTOMATION_BOUNDARY = "com.system.launcher.tools.action.AUTOMATION_BOUNDARY"
        const val EXTRA_BOUNDARY_ID = "boundary_id"
        const val EXTRA_BOUNDARY_REVISION = "boundary_revision"
        private const val REQUEST_CODE_BOUNDARY = 41021
        private const val TAG = "AutomationAlarm"
    }
}
