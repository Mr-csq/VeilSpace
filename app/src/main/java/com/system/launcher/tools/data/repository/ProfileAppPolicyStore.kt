package com.system.launcher.tools.data.repository

import android.content.Context
import com.system.launcher.tools.data.policy.ProfileAppPolicy
import com.system.launcher.tools.data.policy.ProfileAppPolicyTable

object ProfileAppPolicyStore {
    private const val PREFS_NAME = "profile_app_policy"
    private const val KEEP_ALIVE_PACKAGES = "keep_alive_packages"

    data class EffectivePolicy(
        val staticPolicy: ProfileAppPolicy,
        val userKeepAlive: Boolean
    ) {
        val effectiveUserKeepAlive: Boolean
            get() = staticPolicy.userKeepAliveAllowed && userKeepAlive

        val canAutoHide: Boolean
            get() = !staticPolicy.shouldNeverAutoHide && !effectiveUserKeepAlive

        val shouldNeverAutoHide: Boolean
            get() = staticPolicy.shouldNeverAutoHide

        val autoHideBlockReason: String?
            get() = when {
                staticPolicy.shouldNeverAutoHide -> "staticPolicy:${staticPolicy.role}"
                effectiveUserKeepAlive -> "userKeepAlive"
                else -> null
            }
    }

    fun resolvePolicy(context: Context, packageName: String): EffectivePolicy {
        return EffectivePolicy(
            staticPolicy = ProfileAppPolicyTable.resolve(packageName),
            userKeepAlive = isKeepAliveApp(context, packageName)
        )
    }
    fun isKeepAliveApp(context: Context, packageName: String): Boolean {
        return getKeepAlivePackages(context).contains(packageName)
    }

    fun canAutoHideApp(context: Context, packageName: String): Boolean {
        return resolvePolicy(context, packageName).canAutoHide
    }

    fun shouldNeverAutoHide(packageName: String): Boolean {
        return ProfileAppPolicyTable.resolve(packageName).shouldNeverAutoHide
    }

    fun autoHideStatusLabel(context: Context, packageName: String): String {
        val policy = resolvePolicy(context, packageName)
        return when {
            policy.shouldNeverAutoHide -> "系统策略保护"
            policy.effectiveUserKeepAlive -> "允许后台运行"
            else -> "自动隐藏"
        }
    }

    fun setKeepAliveApp(context: Context, packageName: String, keepAlive: Boolean): Set<String> {
        val packages = getKeepAlivePackages(context).toMutableSet()
        val allowed = ProfileAppPolicyTable.resolve(packageName).userKeepAliveAllowed
        if (keepAlive && allowed) {
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
