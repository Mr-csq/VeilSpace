package com.system.launcher.tools.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.system.launcher.tools.data.model.AppEntrySource
import com.system.launcher.tools.data.model.AppInfo
import com.system.launcher.tools.data.model.IconStatus
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.model.LaunchVerification
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

object ProfileAppStore {
    private const val TAG = "ProfileAppStore"
    private const val PROFILE_APPS_PREFS = "profile_apps"
    private const val PROFILE_APP_ITEMS = "app_items"
    private const val PROFILE_APP_ITEMS_JSON = "app_items_json"
    private const val ITEM_SEPARATOR = "\t"
    private const val ICON_DIR = "profile_app_icons"
    private const val SORT_STEP = 10

    fun saveApps(context: Context, apps: List<AppInfo>) {
        val previousEntries = loadEntries(context).associateBy { it.packageName }
        val entries = apps
            .filter { it.packageName.isNotBlank() && it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .mapIndexed { index, app ->
                val previous = previousEntries[app.packageName]
                val expectedIconFileName = iconFileNameForPackage(app.packageName)
                val iconFileName = when {
                    app.icon != null -> saveAppIcon(context, app).takeIf { it.isNotBlank() } ?: previous?.iconFileName.orEmpty()
                    iconFileExists(context, expectedIconFileName) -> expectedIconFileName
                    else -> previous?.iconFileName.orEmpty()
                }
                val iconStatus = when {
                    iconFileName.isNotBlank() && iconFileExists(context, iconFileName) -> IconStatus.OK
                    app.iconStatus == IconStatus.STALE -> IconStatus.STALE
                    else -> IconStatus.MISSING
                }
                StoredApp(
                    packageName = app.packageName,
                    appName = app.appName.ifBlank { previous?.appName ?: app.packageName },
                    iconFileName = iconFileName,
                    isSystemApp = app.isSystemApp,
                    showOnHome = app.showOnHome,
                    sortOrder = app.sortOrder.takeIf { it != Int.MAX_VALUE }
                        ?: previous?.sortOrder
                        ?: ((index + 1) * SORT_STEP),
                    keepAlive = app.keepAlive,
                    entrySource = app.entrySource,
                    installVerification = app.installVerification,
                    launchVerification = app.launchVerification,
                    iconStatus = iconStatus,
                    launcherComponentNames = app.launcherComponentNames.ifEmpty { previous?.launcherComponentNames.orEmpty() },
                    lastSeenAt = app.lastSeenAt,
                    diagnosticReason = app.diagnosticReason
                )
            }

        val legacySerialized = entries.map { entry ->
            entry.packageName + ITEM_SEPARATOR + entry.appName + ITEM_SEPARATOR + entry.iconFileName
        }.toSet()
        val json = JSONArray().apply {
            entries.forEach { entry -> put(entry.toJson()) }
        }.toString()

        context.getSharedPreferences(PROFILE_APPS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(PROFILE_APP_ITEMS, legacySerialized)
            .putString(PROFILE_APP_ITEMS_JSON, json)
            .apply()

        cleanupUnusedIcons(context, entries.mapTo(hashSetOf()) { it.iconFileName })
    }

    fun loadApps(context: Context): List<AppInfo> {
        return loadEntries(context)
            .map { entry -> entry.toAppInfo(context) }
            .filter { it.packageName != context.packageName }
            .sortedWith(compareBy<AppInfo> { it.sortOrder }.thenBy { it.appName })
    }

    fun loadHomeApps(context: Context): List<AppInfo> {
        return loadApps(context)
            .filter { it.showOnHome && it.installVerification != InstallVerification.CONFIRMED_MISSING }
            .sortedWith(compareBy<AppInfo> { it.sortOrder }.thenBy { it.appName })
    }

    fun containsApp(context: Context, packageName: String): Boolean {
        return loadEntries(context).any { it.packageName == packageName }
    }

    fun upsertApp(context: Context, app: AppInfo): List<AppInfo> {
        val existingApps = loadApps(context)
        val existing = existingApps.firstOrNull { it.packageName == app.packageName }
        val mergedApp = mergeAppInfo(context, existing, app)
        val apps = (existingApps.filter { it.packageName != app.packageName } + mergedApp)
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
        saveApps(context, apps)
        return loadApps(context)
    }

    fun upsertApps(context: Context, incomingApps: List<AppInfo>): List<AppInfo> {
        val currentByPackage = loadApps(context).associateBy { it.packageName }.toMutableMap()
        incomingApps
            .filter { it.packageName.isNotBlank() && it.packageName != context.packageName }
            .forEach { app -> currentByPackage[app.packageName] = mergeAppInfo(context, currentByPackage[app.packageName], app) }
        saveApps(context, currentByPackage.values.toList())
        return loadApps(context)
    }

    fun removeApp(context: Context, packageName: String): List<AppInfo> {
        val entries = loadEntries(context)
        entries.firstOrNull { it.packageName == packageName }?.let { entry -> deleteIconFile(context, entry.iconFileName) }
        deleteIconFile(context, iconFileNameForPackage(packageName))
        val apps = loadApps(context).filter { it.packageName != packageName }
        saveApps(context, apps)
        return loadApps(context)
    }

    fun setShowOnHome(context: Context, packageName: String, showOnHome: Boolean): List<AppInfo> {
        val apps = loadApps(context).map { app ->
            if (app.packageName == packageName) {
                app.copy(
                    showOnHome = showOnHome,
                    sortOrder = when {
                        !showOnHome -> Int.MAX_VALUE
                        app.sortOrder == Int.MAX_VALUE -> nextHomeSortOrder(context)
                        else -> app.sortOrder
                    }
                )
            } else {
                app
            }
        }
        saveApps(context, apps)
        return loadApps(context)
    }

    fun setKeepAlive(context: Context, packageName: String, keepAlive: Boolean): List<AppInfo> {
        val apps = loadApps(context).map { app ->
            if (app.packageName == packageName) app.copy(keepAlive = keepAlive) else app
        }
        saveApps(context, apps)
        return loadApps(context)
    }

    fun reorderHomeApps(context: Context, orderedPackageNames: List<String>): List<AppInfo> {
        val orderedPackages = orderedPackageNames.distinct()
        val orderByPackage = orderedPackages.mapIndexed { index, packageName ->
            packageName to ((index + 1) * SORT_STEP)
        }.toMap()
        var fallbackOrder = (orderedPackages.size + 1) * SORT_STEP
        val apps = loadApps(context).map { app ->
            val explicitOrder = orderByPackage[app.packageName]
            when {
                explicitOrder != null && app.showOnHome && app.installVerification != InstallVerification.CONFIRMED_MISSING -> {
                    app.copy(sortOrder = explicitOrder)
                }
                app.showOnHome && app.installVerification != InstallVerification.CONFIRMED_MISSING -> {
                    val order = fallbackOrder
                    fallbackOrder += SORT_STEP
                    app.copy(sortOrder = order)
                }
                else -> app.copy(sortOrder = Int.MAX_VALUE)
            }
        }
        saveApps(context, apps)
        return loadApps(context)
    }

    fun updateVerificationState(
        context: Context,
        packageName: String,
        installVerification: InstallVerification,
        launchVerification: LaunchVerification,
        diagnosticReason: String,
        entrySource: AppEntrySource? = null,
        lastSeenAt: Long = System.currentTimeMillis()
    ): List<AppInfo> {
        val existingApps = loadApps(context)
        var changed = false
        val apps = existingApps.map { app ->
            if (app.packageName == packageName) {
                val updated = app.copy(
                    entrySource = entrySource ?: app.entrySource,
                    installVerification = installVerification,
                    launchVerification = launchVerification,
                    diagnosticReason = diagnosticReason,
                    lastSeenAt = lastSeenAt
                )
                if (app.entrySource != updated.entrySource ||
                    app.installVerification != updated.installVerification ||
                    app.launchVerification != updated.launchVerification ||
                    app.diagnosticReason != updated.diagnosticReason
                ) {
                    changed = true
                    updated
                } else {
                    app
                }
            } else {
                app
            }
        }
        if (!changed) return existingApps
        saveApps(context, apps)
        return loadApps(context)
    }

    private fun mergeAppInfo(context: Context, existing: AppInfo?, incoming: AppInfo): AppInfo {
        if (existing == null) {
            val sortOrder = incoming.sortOrder.takeIf { it != Int.MAX_VALUE }
                ?: if (incoming.showOnHome) nextHomeSortOrder(context) else Int.MAX_VALUE
            return incoming.copy(sortOrder = sortOrder)
        }
        return AppInfo(
            packageName = incoming.packageName,
            appName = incoming.appName.takeIf { it.isNotBlank() } ?: existing.appName,
            icon = incoming.icon ?: existing.icon,
            isSystemApp = incoming.isSystemApp || existing.isSystemApp,
            showOnHome = existing.showOnHome,
            sortOrder = existing.sortOrder,
            keepAlive = existing.keepAlive || incoming.keepAlive,
            entrySource = mergeEntrySource(existing.entrySource, incoming.entrySource),
            installVerification = mergeInstallVerification(existing.installVerification, incoming.installVerification),
            launchVerification = mergeLaunchVerification(existing.launchVerification, incoming.launchVerification),
            iconStatus = if (incoming.icon != null) IconStatus.OK else existing.iconStatus,
            launcherComponentNames = incoming.launcherComponentNames.ifEmpty { existing.launcherComponentNames },
            lastSeenAt = incoming.lastSeenAt,
            diagnosticReason = incoming.diagnosticReason.ifBlank { existing.diagnosticReason }
        )
    }

    private fun mergeEntrySource(existing: AppEntrySource, incoming: AppEntrySource): AppEntrySource {
        return when {
            existing == AppEntrySource.INTERNAL || incoming == AppEntrySource.INTERNAL -> AppEntrySource.INTERNAL
            existing == AppEntrySource.SYSTEM_CANDIDATE || incoming == AppEntrySource.SYSTEM_CANDIDATE -> AppEntrySource.SYSTEM_CANDIDATE
            incoming == AppEntrySource.DISCOVERED_INSTALLED -> AppEntrySource.DISCOVERED_INSTALLED
            else -> existing
        }
    }

    private fun mergeInstallVerification(existing: InstallVerification, incoming: InstallVerification): InstallVerification {
        return if (incoming == InstallVerification.UNKNOWN) existing else incoming
    }

    private fun mergeLaunchVerification(existing: LaunchVerification, incoming: LaunchVerification): LaunchVerification {
        return if (incoming == LaunchVerification.UNKNOWN) existing else incoming
    }

    private fun nextHomeSortOrder(context: Context): Int {
        val maxOrder = loadApps(context)
            .filter { it.showOnHome && it.sortOrder != Int.MAX_VALUE }
            .maxOfOrNull { it.sortOrder }
            ?: 0
        return maxOrder + SORT_STEP
    }

    private fun loadEntries(context: Context): List<StoredApp> {
        val prefs = context.getSharedPreferences(PROFILE_APPS_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(PROFILE_APP_ITEMS_JSON, null)
        if (!json.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(json)
                return (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    StoredApp.fromJson(item, index)
                }
            }.onFailure { error -> Log.e(TAG, "Error loading JSON profile app store", error) }
        }

        return prefs.getStringSet(PROFILE_APP_ITEMS, emptySet())
            .orEmpty()
            .mapIndexedNotNull { index, raw ->
                val parts = raw.split(ITEM_SEPARATOR)
                if (parts.size < 2 || parts[0].isBlank()) {
                    null
                } else {
                    StoredApp(
                        packageName = parts[0],
                        appName = parts[1],
                        iconFileName = parts.getOrNull(2).orEmpty().ifBlank { iconFileNameForPackage(parts[0]) },
                        isSystemApp = false,
                        showOnHome = true,
                        sortOrder = (index + 1) * SORT_STEP,
                        keepAlive = false,
                        entrySource = AppEntrySource.CACHED,
                        installVerification = InstallVerification.CONFIRMED_INSTALLED,
                        launchVerification = LaunchVerification.LAUNCHABLE,
                        iconStatus = IconStatus.OK,
                        launcherComponentNames = emptySet(),
                        lastSeenAt = System.currentTimeMillis(),
                        diagnosticReason = ""
                    )
                }
            }
    }

    private fun saveAppIcon(context: Context, app: AppInfo): String {
        val icon = app.icon ?: return ""
        return try {
            val dir = iconDir(context)
            val fileName = iconFileNameForPackage(app.packageName)
            val file = File(dir, fileName)
            val tempFile = File(dir, "$fileName.tmp")
            FileOutputStream(tempFile).use { output ->
                val bitmap = drawableToBitmap(icon)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            }
            if (!tempFile.renameTo(file)) {
                file.delete()
                tempFile.renameTo(file)
            }
            fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error saving app icon: ${app.packageName}", e)
            ""
        }
    }

    private fun loadAppIcon(context: Context, fileName: String): Drawable? {
        if (fileName.isBlank()) return null
        return try {
            val file = File(iconDir(context), fileName)
            if (!file.exists()) return null
            Drawable.createFromPath(file.absolutePath) ?: run {
                file.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading app icon: $fileName", e)
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val size = 192
        if (drawable is BitmapDrawable && drawable.bitmap.width == size && drawable.bitmap.height == size) {
            return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val oldBounds = drawable.bounds
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        drawable.setBounds(oldBounds)
        return bitmap
    }

    private fun iconDir(context: Context): File {
        return File(context.filesDir, ICON_DIR).apply { mkdirs() }
    }

    private fun iconFileNameForPackage(packageName: String): String {
        return packageName.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".png"
    }

    private fun iconFileExists(context: Context, fileName: String): Boolean {
        return fileName.isNotBlank() && File(iconDir(context), fileName).exists()
    }

    private fun deleteIconFile(context: Context, fileName: String) {
        if (fileName.isBlank()) return
        runCatching { File(iconDir(context), fileName).delete() }
    }

    private fun cleanupUnusedIcons(context: Context, usedFileNames: Set<String>) {
        val used = usedFileNames.filterTo(hashSetOf()) { it.isNotBlank() }
        iconDir(context).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "png" && it.name !in used }
            .forEach { file -> runCatching { file.delete() } }
    }

    private data class StoredApp(
        val packageName: String,
        val appName: String,
        val iconFileName: String,
        val isSystemApp: Boolean,
        val showOnHome: Boolean,
        val sortOrder: Int,
        val keepAlive: Boolean,
        val entrySource: AppEntrySource,
        val installVerification: InstallVerification,
        val launchVerification: LaunchVerification,
        val iconStatus: IconStatus,
        val launcherComponentNames: Set<String>,
        val lastSeenAt: Long,
        val diagnosticReason: String
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("packageName", packageName)
                put("appName", appName)
                put("iconFileName", iconFileName)
                put("isSystemApp", isSystemApp)
                put("showOnHome", showOnHome)
                put("sortOrder", sortOrder)
                put("keepAlive", keepAlive)
                put("entrySource", entrySource.name)
                put("installVerification", installVerification.name)
                put("launchVerification", launchVerification.name)
                put("installed", installVerification == InstallVerification.CONFIRMED_INSTALLED)
                put("launchable", launchVerification == LaunchVerification.LAUNCHABLE || launchVerification == LaunchVerification.POLICY_LAUNCH_ONLY)
                put("iconStatus", iconStatus.name)
                put("launcherComponentNames", JSONArray().apply { launcherComponentNames.forEach(::put) })
                put("lastSeenAt", lastSeenAt)
                put("diagnosticReason", diagnosticReason)
            }
        }

        fun toAppInfo(context: Context): AppInfo {
            val icon = loadAppIcon(context, iconFileName)
            val resolvedIconStatus = when {
                icon != null -> IconStatus.OK
                iconStatus == IconStatus.STALE -> IconStatus.STALE
                else -> IconStatus.MISSING
            }
            return AppInfo(
                packageName = packageName,
                appName = appName,
                icon = icon,
                isSystemApp = isSystemApp,
                showOnHome = showOnHome,
                sortOrder = sortOrder,
                keepAlive = keepAlive,
                entrySource = entrySource,
                installVerification = installVerification,
                launchVerification = launchVerification,
                iconStatus = resolvedIconStatus,
                launcherComponentNames = launcherComponentNames,
                lastSeenAt = lastSeenAt,
                diagnosticReason = diagnosticReason
            )
        }

        companion object {
            fun fromJson(item: JSONObject, index: Int): StoredApp? {
                val packageName = item.optString("packageName")
                if (packageName.isBlank()) return null
                val storedIcon = item.optString("iconFileName")
                val diagnosticReason = item.optString("diagnosticReason")
                val installVerification = parseInstallVerification(item, diagnosticReason)
                return StoredApp(
                    packageName = packageName,
                    appName = item.optString("appName").takeIf { it.isNotBlank() } ?: packageName,
                    iconFileName = storedIcon.ifBlank { iconFileNameForPackage(packageName) },
                    isSystemApp = item.optBoolean("isSystemApp", false),
                    showOnHome = item.optBoolean("showOnHome", true),
                    sortOrder = item.optInt("sortOrder", (index + 1) * SORT_STEP),
                    keepAlive = item.optBoolean("keepAlive", false),
                    entrySource = parseEnum(item.optString("entrySource"), AppEntrySource.CACHED),
                    installVerification = installVerification,
                    launchVerification = parseLaunchVerification(item, installVerification),
                    iconStatus = parseEnum(item.optString("iconStatus"), IconStatus.OK),
                    launcherComponentNames = parseStringSet(item.optJSONArray("launcherComponentNames")),
                    lastSeenAt = item.optLong("lastSeenAt", System.currentTimeMillis()),
                    diagnosticReason = diagnosticReason
                )
            }

            private fun parseInstallVerification(item: JSONObject, diagnosticReason: String): InstallVerification {
                val parsed = parseEnumOrNull<InstallVerification>(item.optString("installVerification"))
                if (parsed != null) return parsed
                if (!item.has("installed")) return InstallVerification.UNKNOWN
                if (item.optBoolean("installed", true)) return InstallVerification.CONFIRMED_INSTALLED
                return if (diagnosticReason.contains("已卸载")) {
                    InstallVerification.CONFIRMED_MISSING
                } else {
                    InstallVerification.UNKNOWN
                }
            }

            private fun parseStringSet(array: JSONArray?): Set<String> {
                if (array == null) return emptySet()
                return (0 until array.length())
                    .mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
                    .toSet()
            }

            private fun parseLaunchVerification(item: JSONObject, installVerification: InstallVerification): LaunchVerification {
                val parsed = parseEnumOrNull<LaunchVerification>(item.optString("launchVerification"))
                if (parsed != null) return parsed
                if (!item.has("launchable")) return LaunchVerification.UNKNOWN
                if (item.optBoolean("launchable", false)) return LaunchVerification.LAUNCHABLE
                return if (installVerification == InstallVerification.CONFIRMED_MISSING) {
                    LaunchVerification.NOT_LAUNCHABLE
                } else {
                    LaunchVerification.UNKNOWN
                }
            }

            private inline fun <reified T : Enum<T>> parseEnum(raw: String?, defaultValue: T): T {
                return parseEnumOrNull<T>(raw) ?: defaultValue
            }

            private inline fun <reified T : Enum<T>> parseEnumOrNull(raw: String?): T? {
                val normalized = raw?.trim().orEmpty()
                if (normalized.isBlank()) return null
                return enumValues<T>().firstOrNull { enumValue ->
                    enumValue.name.equals(normalized, ignoreCase = true)
                }
            }
        }
    }
}

