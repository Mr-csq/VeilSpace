package com.system.launcher.tools.data.repository

import android.content.Context

object ProfileAppPolicyStore {
    private const val PREFS_NAME = "profile_app_policy"
    private const val KEEP_ALIVE_PACKAGES = "keep_alive_packages"

    fun isKeepAliveApp(context: Context, packageName: String): Boolean {
        return getKeepAlivePackages(context).contains(packageName)
    }

    fun setKeepAliveApp(context: Context, packageName: String, keepAlive: Boolean): Set<String> {
        val packages = getKeepAlivePackages(context).toMutableSet()
        if (keepAlive) {
            packages.add(packageName)
        } else {
            packages.remove(packageName)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEEP_ALIVE_PACKAGES, packages)
            .apply()
        return packages
    }

    fun removePackage(context: Context, packageName: String) {
        setKeepAliveApp(context, packageName, false)
    }

    fun getKeepAlivePackages(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEEP_ALIVE_PACKAGES, emptySet())
            .orEmpty()
    }
}
