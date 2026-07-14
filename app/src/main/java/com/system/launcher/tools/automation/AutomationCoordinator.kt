package com.system.launcher.tools.automation

import android.content.Context
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.repository.ProfileAppPolicyStore
import com.system.launcher.tools.data.repository.ProfileAppStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class AutomationSaveResult(
    val config: AutomationConfig?,
    val errors: List<String>,
    val alarmStatus: AlarmScheduleStatus?
)

data class AutomationUiState(
    val config: AutomationConfig,
    val scheduleSnapshot: AutomationScheduleCalculator.Snapshot,
    val alarmStatus: AlarmScheduleStatus,
    val workdayMetadata: WorkdayDataMetadata,
    val lastResult: AutomationExecutionResult?
)

@Singleton
class AutomationCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: AutomationConfigStore,
    private val calculator: AutomationScheduleCalculator,
    private val alarmScheduler: ExactAlarmScheduler,
    private val keepAliveController: ProfileAppKeepAliveController,
    private val notificationController: NotificationPermissionController
) {
    fun saveConfig(draft: AutomationConfig): AutomationSaveResult = synchronized(EXECUTION_LOCK) {
        val now = Instant.now()
        val zoneId = ZoneId.systemDefault()
        val errors = draft.validationErrors().toMutableList()
        if (draft.enabled && draft.dateMode == AutomationDateMode.CHINA_LEGAL_WORKDAY &&
            !calculator.isOfficialDataAvailableFor(LocalDate.now(zoneId))
        ) {
            errors += "当前年份没有中国法定工作日数据，请切换到自定义星期兜底模式"
        }
        if (errors.isNotEmpty()) return@synchronized AutomationSaveResult(null, errors, null)

        val nextRevision = store.loadConfig().revision + 1L
        val versioned = draft.copy(revision = nextRevision)
        // A configuration edit never replays a boundary that happened before Save.
        val baseline = calculator.snapshot(versioned, now, zoneId).latestBoundary
        val saved = store.saveConfig(versioned, baseline)
        val alarmStatus = alarmScheduler.reschedule(saved, now, zoneId)
        AutomationSaveResult(saved, emptyList(), alarmStatus)
    }

    fun recoverAndSchedule(triggerReason: String): AlarmScheduleStatus = synchronized(EXECUTION_LOCK) {
        val now = Instant.now()
        val zoneId = ZoneId.systemDefault()
        val config = store.loadConfig()
        if (!config.enabled || config.validationErrors().isNotEmpty()) {
            return@synchronized alarmScheduler.reschedule(config, now, zoneId)
        }

        val snapshot = calculator.snapshot(config, now, zoneId)
        val latest = snapshot.latestBoundary
        if (latest != null && shouldExecute(latest)) {
            executeBoundary(config, latest, triggerReason, now)
        }
        alarmScheduler.reschedule(config, Instant.now(), zoneId)
    }

    fun loadUiState(): AutomationUiState {
        val now = Instant.now()
        val zoneId = ZoneId.systemDefault()
        val config = store.loadConfig()
        return AutomationUiState(
            config = config,
            scheduleSnapshot = calculator.snapshot(config, now, zoneId),
            alarmStatus = alarmScheduler.reschedule(config, now, zoneId),
            workdayMetadata = calculator.providerMetadata(),
            lastResult = store.loadLastResult()
        )
    }

    fun createExactAlarmPermissionIntent() = alarmScheduler.createExactAlarmPermissionIntent()

    fun availableApps() = ProfileAppStore.loadApps(context)

    fun selectionUnavailableReason(app: AppInfo): String? {
        val policy = ProfileAppPolicyStore.resolvePolicy(context, app.packageName)
        return when {
            app.packageName.endsWith(INTERNAL_FILE_MANAGER_SUFFIX) -> "VeilSpace 内部入口不支持此自动化"
            app.installVerification != InstallVerification.CONFIRMED_INSTALLED -> "未确认安装在工作资料中"
            policy.shouldNeverAutoHide -> "该应用由静态策略保护，不使用 keepAlive 开关"
            !policy.staticPolicy.userKeepAliveAllowed -> "静态策略不允许用户或自动化启用 keepAlive"
            else -> null
        }
    }

    private fun shouldExecute(boundary: AutomationBoundary): Boolean {
        if (!BoundaryExecutionDecider.shouldExecute(boundary.id, store.lastCompletedBoundaryId())) return false
        val completedAt = store.lastCompletedScheduledAt()
        // Moving the system clock backwards must not replay an older boundary.
        return completedAt == null || boundary.scheduledAt.isAfter(completedAt)
    }

    private fun executeBoundary(
        config: AutomationConfig,
        boundary: AutomationBoundary,
        triggerReason: String,
        executedAt: Instant
    ) {
        val enable = BoundaryPolicy.desiredKeepAlive(boundary.type)
        val grantNotifications = BoundaryPolicy.desiredNotificationGrant(boundary.type)
        val results = config.selectedPackages.sorted().map { packageName ->
            runCatching {
                val keepAlive = keepAliveController.setKeepAlive(
                    packageName = packageName,
                    enabled = enable,
                    reason = "automation${boundary.type.name.lowercase().replaceFirstChar(Char::uppercase)}"
                )
                val notifications = notificationController.setNotificationsGranted(packageName, grantNotifications)
                AutomationAppResult(
                    packageName = packageName,
                    keepAliveStatus = keepAlive.status,
                    notificationStatus = notifications.status,
                    detail = listOf(keepAlive.detail, notifications.detail).filter { it.isNotBlank() }.joinToString("；")
                )
            }.getOrElse { error ->
                AutomationAppResult(
                    packageName = packageName,
                    keepAliveStatus = AutomationOperationStatus.FAILED,
                    notificationStatus = AutomationOperationStatus.FAILED,
                    detail = error.message ?: "未预期的应用级错误"
                )
            }
        }
        val retryWhenProfileReturns = results.any { appResult ->
            appResult.keepAliveStatus == AutomationOperationStatus.NO_PROFILE_OWNER ||
                appResult.notificationStatus == AutomationOperationStatus.NO_PROFILE_OWNER
        }
        val executionResult = AutomationExecutionResult(
            boundaryId = boundary.id,
            boundaryType = boundary.type,
            scheduledAt = boundary.scheduledAt,
            executedAt = executedAt,
            triggerReason = triggerReason,
            completed = !retryWhenProfileReturns,
            appResults = results
        )
        if (executionResult.completed) {
            store.markBoundaryCompleted(executionResult)
        } else {
            store.saveAttemptResult(executionResult)
        }
    }

    companion object {
        private val EXECUTION_LOCK = Any()
        private const val INTERNAL_FILE_MANAGER_SUFFIX = ".internal.filemanager"
    }
}
