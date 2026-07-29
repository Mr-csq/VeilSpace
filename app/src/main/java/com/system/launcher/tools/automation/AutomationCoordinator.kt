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

data class AutomationOneTimePauseUpdateResult(
    val pause: AutomationOneTimePause?,
    val error: String?,
    val alarmStatus: AlarmScheduleStatus
)

data class AutomationUiState(
    val config: AutomationConfig,
    val scheduleSnapshot: AutomationScheduleCalculator.Snapshot,
    val alarmStatus: AlarmScheduleStatus,
    val workdayMetadata: WorkdayDataMetadata,
    val workdayDataWarning: String?,
    val lastResult: AutomationExecutionResult?,
    val oneTimePause: AutomationOneTimePause?
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

        val currentPause = store.loadOneTimePause()
        val nextRevision = store.loadConfig().revision + 1L
        val versioned = draft.copy(revision = nextRevision)
        // A configuration edit never replays a boundary that happened before Save.
        val baseline = calculator.snapshot(versioned, now, zoneId).latestBoundary
        val pauseAfterSave = oneTimePauseAfterConfigSave(currentPause, versioned, now, zoneId)
        val saved = store.saveConfig(versioned, baseline, pauseAfterSave)
        val alarmStatus = alarmScheduler.reschedule(saved, now, zoneId)
        AutomationSaveResult(saved, emptyList(), alarmStatus)
    }

    fun recoverAndSchedule(triggerReason: String): AlarmScheduleStatus = synchronized(EXECUTION_LOCK) {
        val now = Instant.now()
        val zoneId = ZoneId.systemDefault()
        val config = store.loadConfig()
        if (!config.enabled || config.validationErrors().isNotEmpty()) {
            store.saveOneTimePause(null)
            return@synchronized alarmScheduler.reschedule(config, now, zoneId)
        }

        val snapshot = calculator.snapshot(config, now, zoneId)
        val latest = snapshot.latestBoundary
        val pause = store.loadOneTimePause()
        if (latest != null) {
            val pauseDecision = AutomationOneTimePausePolicy.decide(pause, latest)
            if (pause != null && !pauseDecision.skipBoundary && pauseDecision.pauseAfterBoundary == null) {
                store.saveOneTimePause(null)
            }
            if (shouldExecute(latest)) {
                if (pauseDecision.skipBoundary) {
                    recordSkippedBoundary(latest, triggerReason, now, pauseDecision.pauseAfterBoundary)
                } else {
                    executeBoundary(
                        config,
                        latest,
                        triggerReason,
                        now,
                        pauseDecision.pauseAfterBoundary
                    )
                }
            }
        }
        alarmScheduler.reschedule(config, Instant.now(), zoneId)
    }

    fun setOneTimePause(enabled: Boolean): AutomationOneTimePauseUpdateResult =
        synchronized(EXECUTION_LOCK) {
            val now = Instant.now()
            val zoneId = ZoneId.systemDefault()
            val config = store.loadConfig()
            val existing = store.loadOneTimePause()

            if (!enabled) {
                if (existing?.switchArmed == false) {
                    return@synchronized AutomationOneTimePauseUpdateResult(
                        pause = existing,
                        error = "本次工作时段已停用，结束边界后才能再次设置",
                        alarmStatus = alarmScheduler.reschedule(config, now, zoneId)
                    )
                }
                store.saveOneTimePause(null)
                return@synchronized AutomationOneTimePauseUpdateResult(
                    pause = null,
                    error = null,
                    alarmStatus = alarmScheduler.reschedule(config, now, zoneId)
                )
            }

            if (existing != null) {
                return@synchronized AutomationOneTimePauseUpdateResult(
                    pause = existing,
                    error = if (existing.switchArmed) null else "本次工作时段已停用，结束边界后才能再次设置",
                    alarmStatus = alarmScheduler.reschedule(config, now, zoneId)
                )
            }

            val error = when {
                !config.enabled -> "请先保存并启用工作模式"
                config.validationErrors().isNotEmpty() -> "已保存的工作模式配置无效，请先修正并保存"
                else -> null
            }
            val nextStart = if (error == null) {
                calculator.snapshot(config, now, zoneId).nextStartBoundary
            } else {
                null
            }
            if (error != null || nextStart == null) {
                return@synchronized AutomationOneTimePauseUpdateResult(
                    pause = null,
                    error = error ?: "当前没有可停用的下一次工作时段",
                    alarmStatus = alarmScheduler.reschedule(config, now, zoneId)
                )
            }

            val pause = AutomationOneTimePause(
                workDate = nextStart.workDate,
                requestedAt = now
            )
            store.saveOneTimePause(pause)
            AutomationOneTimePauseUpdateResult(
                pause = pause,
                error = null,
                alarmStatus = alarmScheduler.reschedule(config, now, zoneId)
            )
        }

    fun loadUiState(): AutomationUiState {
        val now = Instant.now()
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val config = store.loadConfig()
        return AutomationUiState(
            config = config,
            scheduleSnapshot = calculator.snapshot(config, now, zoneId),
            alarmStatus = alarmScheduler.reschedule(config, now, zoneId),
            workdayMetadata = calculator.providerMetadata(),
            workdayDataWarning = calculator.unsupportedYearRequiringAttention(today)?.let { year ->
                if (year > today.year) {
                    year.toString() + " 年法定节假日安排尚未内置。请在国务院发布后更新应用；跨入 " +
                        year + " 年前若仍未更新，请先改用自定义星期。"
                } else {
                    "当前缺少 " + year + " 年法定节假日数据，法定工作日模式不会猜测日期，请改用自定义星期。"
                }
            },
            lastResult = store.loadLastResult(),
            oneTimePause = store.loadOneTimePause()
        )
    }

    fun createExactAlarmPermissionIntent() = alarmScheduler.createExactAlarmPermissionIntent()

    fun availableApps() = ProfileAppStore.loadHomeApps(context)

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

    private fun oneTimePauseAfterConfigSave(
        current: AutomationOneTimePause?,
        config: AutomationConfig,
        now: Instant,
        zoneId: ZoneId
    ): AutomationOneTimePause? {
        if (current == null || !config.enabled) return null
        return when (current.phase) {
            AutomationOneTimePausePhase.PENDING -> {
                calculator.snapshot(config, now, zoneId).nextStartBoundary?.let { nextStart ->
                    current.copy(
                        workDate = nextStart.workDate,
                        phase = AutomationOneTimePausePhase.PENDING,
                        switchArmed = true
                    )
                }
            }
            AutomationOneTimePausePhase.ACTIVE -> {
                val targetStillScheduled = calculator.isEligibleWorkDate(config, current.workDate)
                val targetEndIsFuture = targetStillScheduled &&
                    calculator.boundaryFor(
                        config,
                        current.workDate,
                        AutomationBoundaryType.END,
                        zoneId
                    ).scheduledAt.isAfter(now)
                current.takeIf { targetEndIsFuture }
            }
        }
    }

    private fun shouldExecute(boundary: AutomationBoundary): Boolean {
        if (!BoundaryExecutionDecider.shouldExecute(boundary.id, store.lastCompletedBoundaryId())) return false
        val completedAt = store.lastCompletedScheduledAt()
        // Moving the system clock backwards must not replay an older boundary.
        return completedAt == null || boundary.scheduledAt.isAfter(completedAt)
    }

    private fun recordSkippedBoundary(
        boundary: AutomationBoundary,
        triggerReason: String,
        executedAt: Instant,
        oneTimePauseAfter: AutomationOneTimePause?
    ) {
        val result = AutomationExecutionResult(
            boundaryId = boundary.id,
            boundaryType = boundary.type,
            scheduledAt = boundary.scheduledAt,
            executedAt = executedAt,
            triggerReason = triggerReason,
            completed = true,
            appResults = emptyList(),
            outcome = AutomationExecutionOutcome.SKIPPED_ONCE
        )
        store.markBoundaryCompleted(result, oneTimePauseAfter)
    }

    private fun executeBoundary(
        config: AutomationConfig,
        boundary: AutomationBoundary,
        triggerReason: String,
        executedAt: Instant,
        oneTimePauseAfter: AutomationOneTimePause?
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
            store.markBoundaryCompleted(executionResult, oneTimePauseAfter)
        } else {
            store.saveAttemptResult(executionResult)
        }
    }

    companion object {
        private val EXECUTION_LOCK = Any()
        private const val INTERNAL_FILE_MANAGER_SUFFIX = ".internal.filemanager"
    }
}
