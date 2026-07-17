package com.system.launcher.tools.automation

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant

class AutomationConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): AutomationConfig {
        val raw = preferences.getString(KEY_CONFIG, null) ?: return AutomationConfig()
        return runCatching {
            val json = JSONObject(raw)
            AutomationConfig(
                revision = json.optLong("revision", 0L),
                enabled = json.optBoolean("enabled", false),
                startMinuteOfDay = json.optInt("startMinuteOfDay", 9 * 60),
                endMinuteOfDay = json.optInt("endMinuteOfDay", 18 * 60),
                dateMode = enumValueOrDefault(
                    json.optString("dateMode"),
                    AutomationDateMode.CHINA_LEGAL_WORKDAY
                ),
                customWeekdays = json.optJSONArray("customWeekdays")
                    .toStringSet()
                    .mapNotNull { value -> runCatching { DayOfWeek.valueOf(value) }.getOrNull() }
                    .toSet()
                    .ifEmpty { AutomationConfig().customWeekdays },
                selectedPackages = json.optJSONArray("selectedPackages").toStringSet()
            )
        }.getOrElse { AutomationConfig() }
    }

    @Synchronized
    @SuppressLint("ApplySharedPref") // Config and baseline must become durable before the old alarm is replaced.
    fun saveConfig(config: AutomationConfig, baselineBoundary: AutomationBoundary?): AutomationConfig {
        val currentRevision = loadConfig().revision
        val saved = config.copy(
            revision = maxOf(currentRevision + 1L, config.revision),
            selectedPackages = config.selectedPackages.filter { it.isNotBlank() }.toSet()
        )
        val editor = preferences.edit()
            .putString(KEY_CONFIG, saved.toJson().toString())
        if (baselineBoundary == null) {
            editor.remove(KEY_LAST_COMPLETED_BOUNDARY)
            editor.remove(KEY_LAST_COMPLETED_SCHEDULED_AT)
        } else {
            editor.putString(KEY_LAST_COMPLETED_BOUNDARY, baselineBoundary.id)
            editor.putLong(KEY_LAST_COMPLETED_SCHEDULED_AT, baselineBoundary.scheduledAt.toEpochMilli())
        }
        editor.commit()
        return saved
    }

    fun lastCompletedBoundaryId(): String? {
        return preferences.getString(KEY_LAST_COMPLETED_BOUNDARY, null)
    }

    fun lastCompletedScheduledAt(): Instant? {
        val value = preferences.getLong(KEY_LAST_COMPLETED_SCHEDULED_AT, Long.MIN_VALUE)
        return value.takeIf { it != Long.MIN_VALUE }?.let(Instant::ofEpochMilli)
    }

    @Synchronized
    @SuppressLint("ApplySharedPref") // Completion must be durable before a duplicate broadcast can arrive.
    fun markBoundaryCompleted(result: AutomationExecutionResult) {
        preferences.edit()
            .putString(KEY_LAST_COMPLETED_BOUNDARY, result.boundaryId)
            .putLong(KEY_LAST_COMPLETED_SCHEDULED_AT, result.scheduledAt.toEpochMilli())
            .putString(KEY_LAST_RESULT, result.toJson().toString())
            .commit()
    }

    fun saveAttemptResult(result: AutomationExecutionResult) {
        preferences.edit().putString(KEY_LAST_RESULT, result.toJson().toString()).apply()
    }

    fun loadLastResult(): AutomationExecutionResult? {
        val raw = preferences.getString(KEY_LAST_RESULT, null) ?: return null
        return runCatching { AutomationExecutionResult.fromJson(JSONObject(raw)) }.getOrNull()
    }

    private fun AutomationConfig.toJson(): JSONObject = JSONObject().apply {
        put("revision", revision)
        put("enabled", enabled)
        put("startMinuteOfDay", startMinuteOfDay)
        put("endMinuteOfDay", endMinuteOfDay)
        put("dateMode", dateMode.name)
        put("customWeekdays", JSONArray().apply { customWeekdays.sorted().forEach { put(it.name) } })
        put("selectedPackages", JSONArray().apply { selectedPackages.sorted().forEach(::put) })
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length())
            .mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
            .toSet()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T {
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }

    companion object {
        private const val PREFS_NAME = "automation_schedule"
        private const val KEY_CONFIG = "config_json"
        private const val KEY_LAST_COMPLETED_BOUNDARY = "last_completed_boundary"
        private const val KEY_LAST_COMPLETED_SCHEDULED_AT = "last_completed_scheduled_at"
        private const val KEY_LAST_RESULT = "last_result_json"
    }
}

enum class AutomationOperationStatus {
    APPLIED,
    DEFERRED_UNTIL_BACKGROUND,
    POLICY_NOT_ALLOWED,
    NOT_INSTALLED,
    NO_PROFILE_OWNER,
    NOT_DECLARED_BY_APP,
    FAILED
}

data class AutomationAppResult(
    val packageName: String,
    val keepAliveStatus: AutomationOperationStatus,
    val notificationStatus: AutomationOperationStatus,
    val detail: String = ""
)

data class AutomationExecutionResult(
    val boundaryId: String,
    val boundaryType: AutomationBoundaryType,
    val scheduledAt: Instant,
    val executedAt: Instant,
    val triggerReason: String,
    val completed: Boolean,
    val appResults: List<AutomationAppResult>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("boundaryId", boundaryId)
        put("boundaryType", boundaryType.name)
        put("scheduledAt", scheduledAt.toEpochMilli())
        put("executedAt", executedAt.toEpochMilli())
        put("triggerReason", triggerReason)
        put("completed", completed)
        put("appResults", JSONArray().apply {
            appResults.forEach { result ->
                put(JSONObject().apply {
                    put("packageName", result.packageName)
                    put("keepAliveStatus", result.keepAliveStatus.name)
                    put("notificationStatus", result.notificationStatus.name)
                    put("detail", result.detail)
                })
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationExecutionResult {
            val results = json.optJSONArray("appResults")
            return AutomationExecutionResult(
                boundaryId = json.getString("boundaryId"),
                boundaryType = AutomationBoundaryType.valueOf(json.getString("boundaryType")),
                scheduledAt = Instant.ofEpochMilli(json.getLong("scheduledAt")),
                executedAt = Instant.ofEpochMilli(json.getLong("executedAt")),
                triggerReason = json.optString("triggerReason"),
                completed = json.optBoolean("completed", true),
                appResults = if (results == null) emptyList() else (0 until results.length()).map { index ->
                    val item = results.getJSONObject(index)
                    AutomationAppResult(
                        packageName = item.getString("packageName"),
                        keepAliveStatus = AutomationOperationStatus.valueOf(item.getString("keepAliveStatus")),
                        notificationStatus = AutomationOperationStatus.valueOf(item.getString("notificationStatus")),
                        detail = item.optString("detail")
                    )
                }
            )
        }
    }
}
