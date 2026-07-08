package com.system.launcher.tools.ui.disguise

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Runs in the personal profile and opens the real Xiaomi Game Center there.
 */
class GameCenterProxyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launched = launchRealGameCenter(context)
        Log.i(TAG, "Game Center proxy receiver launch result=$launched action=${intent.action}")
    }

    private fun launchRealGameCenter(context: Context): Boolean {
        return launchByPackage(context) || launchByKnownMainActivity(context)
    }

    private fun launchByPackage(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(XIAOMI_GAME_CENTER_PACKAGE)
            if (intent == null) {
                Log.i(TAG, "Game Center launch intent not found in this profile")
                false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched Game Center by package")
                true
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Game Center package launch activity not found", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Game Center by package", e)
            false
        }
    }

    private fun launchByKnownMainActivity(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(
                    XIAOMI_GAME_CENTER_PACKAGE,
                    XIAOMI_GAME_CENTER_MAIN_ACTIVITY
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Launched Game Center by known component")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Known Game Center component not found", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Game Center by known component", e)
            false
        }
    }

    companion object {
        private const val TAG = "GameCenterProxyReceiver"
        private const val XIAOMI_GAME_CENTER_PACKAGE = "com.xiaomi.gamecenter"
        private const val XIAOMI_GAME_CENTER_MAIN_ACTIVITY = "com.xiaomi.gamecenter.ui.MainTabActivity"
    }
}