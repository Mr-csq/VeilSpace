package com.system.launcher.tools.ui.disguise

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Runs in the personal profile and opens the real Xiaomi Game Center there.
 */
class GameCenterProxyActivity : AppCompatActivity() {

    companion object {
        private const val XIAOMI_GAME_CENTER_PACKAGE = "com.xiaomi.gamecenter"
        private const val XIAOMI_GAME_CENTER_MAIN_ACTIVITY = "com.xiaomi.gamecenter.ui.MainTabActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launched = launchRealGameCenter()

        finishWithoutAnimation()
    }

    private fun launchRealGameCenter(): Boolean {
        return launchByPackage() || launchByKnownMainActivity()
    }

    private fun launchByPackage(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(XIAOMI_GAME_CENTER_PACKAGE)
            if (intent == null) {

                false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)

                true
            }
        } catch (e: ActivityNotFoundException) {

            false
        } catch (e: Exception) {

            false
        }
    }

    private fun launchByKnownMainActivity(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(
                    XIAOMI_GAME_CENTER_PACKAGE,
                    XIAOMI_GAME_CENTER_MAIN_ACTIVITY
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            true
        } catch (e: ActivityNotFoundException) {

            false
        } catch (e: Exception) {

            false
        }
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }
}
