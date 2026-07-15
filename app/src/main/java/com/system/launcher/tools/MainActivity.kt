package com.system.launcher.tools

import android.graphics.Color
import android.os.Build
import android.os.Bundle
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

    @Inject
    lateinit var workProfileManager: WorkProfileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        if (workProfileManager.isProfileOwner()) {
            workProfileManager.configureCrossProfileEntry()
        } else {
            workProfileManager.hidePrivacyActionAliasInProfile()
            workProfileManager.hidePrivacySpaceLauncherAliasInProfile()
            workProfileManager.hideGameCenterLauncherAliasInProfile()
            if (workProfileManager.redirectToManagedProfile(this, MainActivity::class.java)) {
                return
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        // A personal-profile instance reaches this point only when redirecting to
        // a VeilSpace-managed profile failed. Never expose the real home locally.
        if (!workProfileManager.isProfileOwner()) {
            navController.navigate(R.id.onboardingFragment)
        }
    }
}
