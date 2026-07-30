package com.system.launcher.tools.ui.disguise

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Runs in the personal profile and opens the real Xiaomi Game Center there.
 */
class GameCenterProxyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launched = launchRealGameCenter(context)

    }

    private fun launchRealGameCenter(context: Context): Boolean {
        return launchByPackage(context) || launchByKnownMainActivity(context)
    }

    private fun launchByPackage(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(XIAOMI_GAME_CENTER_PACKAGE)
            if (intent == null) {

                false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                true
            }
        } catch (e: ActivityNotFoundException) {

            false
        } catch (e: Exception) {

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

            true
        } catch (e: ActivityNotFoundException) {

            false
        } catch (e: Exception) {

            false
        }
    }

    companion object {
        private const val XIAOMI_GAME_CENTER_PACKAGE = "com.xiaomi.gamecenter"
        private const val XIAOMI_GAME_CENTER_MAIN_ACTIVITY = "com.xiaomi.gamecenter.ui.MainTabActivity"
    }
}