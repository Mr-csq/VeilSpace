package com.system.launcher.tools.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

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

                    WorkProfilePackageReceiver().cacheAndHideIfNeeded(context, manager, packageName)
                }
                Intent.ACTION_PACKAGE_REMOVED -> {


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
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registered = true

        } catch (e: Exception) {

        }
    }

    fun stop() {
        if (!registered) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Exception) {

        } finally {
            registered = false
        }
    }

    companion object {
    }
}
