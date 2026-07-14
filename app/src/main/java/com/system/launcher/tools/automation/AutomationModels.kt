package com.system.launcher.tools.automation

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

enum class AutomationDateMode {
    CHINA_LEGAL_WORKDAY,
    CUSTOM_WEEKDAYS
}

enum class AutomationBoundaryType {
    START,
    END
}

data class AutomationConfig(
    val revision: Long = 0L,
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = 9 * 60,
    val endMinuteOfDay: Int = 18 * 60,
    val dateMode: AutomationDateMode = AutomationDateMode.CHINA_LEGAL_WORKDAY,
    val customWeekdays: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY
    ),
    val selectedPackages: Set<String> = emptySet()
) {
    fun validationErrors(): List<String> = buildList {
        if (startMinuteOfDay !in 0..1439 || endMinuteOfDay !in 0..1439) {
            add("开始和结束时间必须是有效的 24 小时时间")
        }
        if (startMinuteOfDay == endMinuteOfDay) {
            add("开始时间和结束时间不能相同")
        }
        if (dateMode == AutomationDateMode.CUSTOM_WEEKDAYS && customWeekdays.isEmpty()) {
            add("自定义星期至少选择一天")
        }
        if (enabled && selectedPackages.isEmpty()) {
            add("启用自动化前至少选择一个应用")
        }
    }
}

data class AutomationBoundary(
    val id: String,
    val revision: Long,
    val type: AutomationBoundaryType,
    val workDate: LocalDate,
    val scheduledAt: Instant
)

enum class WorkdayClassification {
    WORKDAY,
    HOLIDAY,
    UNKNOWN
}

data class WorkdayDataMetadata(
    val supportedYears: Set<Int>,
    val updatedAt: LocalDate,
    val sourceName: String,
    val sourceUrl: String
)

interface WorkdayProvider {
    val metadata: WorkdayDataMetadata

    fun classify(date: LocalDate): WorkdayClassification
}

object BoundaryExecutionDecider {
    fun shouldExecute(boundaryId: String, lastCompletedBoundaryId: String?): Boolean {
        return boundaryId.isNotBlank() && boundaryId != lastCompletedBoundaryId
    }
}

object BoundaryPolicy {
    fun desiredKeepAlive(type: AutomationBoundaryType): Boolean {
        return type == AutomationBoundaryType.START
    }

    fun desiredNotificationGrant(type: AutomationBoundaryType): Boolean {
        return type == AutomationBoundaryType.START
    }
}
