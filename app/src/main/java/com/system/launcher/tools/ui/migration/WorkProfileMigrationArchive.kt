package com.system.launcher.tools.ui.migration

import android.content.Context
import android.util.Log
import java.io.File

object WorkProfileMigrationArchive {
    const val ARCHIVE_NAME = "work-profile-migration.zip"
    private const val MIGRATION_DIR = "migration"
    private const val TAG = "VeilSpaceMigration"

    fun file(context: Context): File {
        return File(File(context.cacheDir, MIGRATION_DIR), ARCHIVE_NAME)
    }

    fun clearStaleArchive(context: Context): Long {
        val directory = File(context.cacheDir, MIGRATION_DIR)
        if (!directory.exists()) return 0L
        val bytes = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
        val deleted = directory.deleteRecursively()
        Log.i(TAG, "CLEANUP bytes=$bytes deleted=$deleted")
        return if (deleted) bytes else 0L
    }
}
