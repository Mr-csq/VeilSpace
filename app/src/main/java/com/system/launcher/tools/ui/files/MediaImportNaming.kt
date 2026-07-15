package com.system.launcher.tools.ui.files

internal object MediaImportNaming {
    fun sanitizeDisplayName(rawName: String, fallbackName: String): String {
        val cleaned = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.isISOControl() }
            .trim()
        if (cleaned.isBlank()) return fallbackName
        if (cleaned.length <= 180) return cleaned

        val dotIndex = cleaned.lastIndexOf('.').takeIf { it > 0 && it < cleaned.lastIndex } ?: -1
        if (dotIndex < 0) return cleaned.take(180)
        val extension = cleaned.substring(dotIndex).take(20)
        return cleaned.substring(0, dotIndex).take(180 - extension.length) + extension
    }

    fun numberedDisplayName(original: String, index: Int): String {
        if (index == 0) return original
        require(index > 0) { "Duplicate index cannot be negative" }
        val dotIndex = original.lastIndexOf('.').takeIf { it > 0 && it < original.lastIndex } ?: -1
        val baseName = if (dotIndex >= 0) original.substring(0, dotIndex) else original
        val extension = if (dotIndex >= 0) original.substring(dotIndex) else ""
        return "$baseName ($index)$extension"
    }
}
