package com.system.launcher.tools

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.NavHostFragment
import com.system.launcher.tools.databinding.ActivityMainBinding
import com.system.launcher.tools.work.WorkProfileManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    @Inject lateinit var workProfileManager: WorkProfileManager


    private var pendingExternalLaunch: (() -> Unit)? = null

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) revokeSessionAndLeaveToHome()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        excludeCurrentTaskFromRecents()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (!isSessionAuthorized()) {
            showSafeMaskAndLeaveToHome()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        if (workProfileManager.isProfileOwner()) {
            workProfileManager.configureCrossProfileEntry()
        } else {
            workProfileManager.configurePersonalProfileEntry()
            if (workProfileManager.redirectToManagedProfile(this, MainActivity::class.java)) return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
    }


    override fun onStop() {
        unregisterReceiver(screenOffReceiver)
        super.onStop()
    }


    override fun onResume() {
        super.onResume()
        if (!isSessionAuthorized()) showSafeMaskAndLeaveToHome()
    }
    fun closeLauncherFolderBeforeExternalLaunch(afterHomeVisible: () -> Unit) {
        // Launch HOME and the target back-to-back; HyperOS receives both in one turn.
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
        afterHomeVisible()
    }
    fun revokeSessionAfterExternalLaunch() {
        // The target task is already being moved to the front asynchronously. Do not
        // start HOME here or it will immediately background the target app.
        (application as PrivacySpaceApp).privacySession.revoke()
    }

    private fun isSessionAuthorized(): Boolean =
        (application as PrivacySpaceApp).privacySession.isAuthorized()

    private fun revokeSessionAndLeaveToHome() {
        (application as PrivacySpaceApp).privacySession.revoke()
        showSafeMaskAndLeaveToHome()
    }

    private fun showSafeMaskAndLeaveToHome() {
        // Never inflate sensitive content for a restored or expired hidden-space task.
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    private fun excludeCurrentTaskFromRecents() {
        getSystemService(ActivityManager::class.java).appTasks
            .firstOrNull { it.taskInfo.taskId == taskId }
            ?.setExcludeFromRecents(true)
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        if (!workProfileManager.isProfileOwner()) navController.navigate(R.id.onboardingFragment)
    }
}
