package com.system.launcher.tools.ui.files

data class FileItem(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val type: FileType,
    val durationMs: Long = 0L,
    val thumbnailPath: String? = null,
    val contentUri: String? = null
)

enum class FileType {
    IMAGE,
    VIDEO,
    OTHER
}

enum class FileTab {
    IMAGES,
    VIDEOS,
    ALL
}

data class FileSpaceState(
    val images: List<FileItem> = emptyList(),
    val videos: List<FileItem> = emptyList(),
    val allFiles: List<FileItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

