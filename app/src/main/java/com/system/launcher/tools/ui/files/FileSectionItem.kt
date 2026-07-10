package com.system.launcher.tools.ui.files

sealed class FileSectionItem {
    data class DateHeader(
        val key: String,
        val label: String
    ) : FileSectionItem()

    data class FileRow(
        val item: FileItem
    ) : FileSectionItem()

    companion object {
        fun fromFiles(files: List<FileItem>): List<FileSectionItem> {
            val items = mutableListOf<FileSectionItem>()
            var currentDateKey: String? = null
            files.forEach { file ->
                val dateKey = FileFormatters.dateSectionKey(file.modifiedAt)
                if (dateKey != currentDateKey) {
                    currentDateKey = dateKey
                    items += DateHeader(
                        key = dateKey,
                        label = FileFormatters.formatDateSection(file.modifiedAt)
                    )
                }
                items += FileRow(file)
            }
            return items
        }
    }
}
