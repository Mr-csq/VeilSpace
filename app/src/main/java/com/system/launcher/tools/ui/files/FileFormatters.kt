package com.system.launcher.tools.ui.files

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object FileFormatters {
    fun formatSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "--:--"
        val totalSeconds = durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatModifiedAt(timeMillis: Long): String {
        if (timeMillis <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timeMillis))
    }

    fun dateSectionKey(timeMillis: Long): String {
        if (timeMillis <= 0L) return "unknown"
        return SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(timeMillis))
    }

    fun formatDateSection(timeMillis: Long): String {
        if (timeMillis <= 0L) return "未知日期"
        val itemDay = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = timeMillis }
        val today = Calendar.getInstance(Locale.CHINA)
        if (isSameDay(itemDay, today)) return "今天"

        val yesterday = Calendar.getInstance(Locale.CHINA).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        if (isSameDay(itemDay, yesterday)) return "昨天"

        return SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(timeMillis))
    }

    private fun isSameDay(left: Calendar, right: Calendar): Boolean {
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
            left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
    }
}
