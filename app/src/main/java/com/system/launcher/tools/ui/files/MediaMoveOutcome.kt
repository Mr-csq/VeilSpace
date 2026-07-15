package com.system.launcher.tools.ui.files

data class MediaMoveOutcome(
    val copied: Int,
    val copyFailed: Int,
    val sourceDeleted: Int,
    val sourceDeleteFailed: Int
) {
    val moved: Int = minOf(copied.coerceAtLeast(0), sourceDeleted.coerceAtLeast(0))
    val copiedButRetained: Int = (copied.coerceAtLeast(0) - moved).coerceAtLeast(0)
    val hasFailures: Boolean = copyFailed > 0 || copiedButRetained > 0 || sourceDeleteFailed > 0
}
