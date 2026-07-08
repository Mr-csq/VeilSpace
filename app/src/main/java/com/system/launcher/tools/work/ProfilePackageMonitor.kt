package com.system.launcher.tools.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

class ProfilePackageMonitor(private val context: Context) {
    private val appContext = context.applicationContext
    private val manager = WorkProfileManager(appContext)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val packageName = intent.data?.schemeSpecificPart ?: return
            if (packageName == context.packageName) return
            if (!manager.isProfileOwner()) return

            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_CHANGED -> {
                    Log.i(TAG, "Runtime package event ${intent.action}: $packageName")
                    WorkProfilePackageReceiver().cacheAndHideIfNeeded(context, manager, packageName)
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    Log.i(TAG, "Runtime package removed: $packageName")
                    Log.i(TAG, "Ignoring package removed event for cached/hidden profile package: $packageName")
                }
            }
        }
    }

    fun start() {
        if (registered || !manager.isProfileOwner()) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            registered = true
            Log.i(TAG, "Runtime package monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering runtime package monitor", e)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering runtime package monitor", e)
        } finally {
            registered = false
        }
    }

    companion object {
        private const val TAG = "ProfilePackageMonitor"
    }
}
