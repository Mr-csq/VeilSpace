package com.system.launcher.tools

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(this, R.color.space_background_deep)
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
        if (!workProfileManager.canUseWorkProfileFeatures()) {
            navController.navigate(R.id.onboardingFragment)
        }
    }
}
