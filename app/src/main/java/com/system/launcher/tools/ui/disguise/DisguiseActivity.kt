package com.system.launcher.tools.ui.disguise

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updateLayoutParams
import com.system.launcher.tools.MainActivity
import com.system.launcher.tools.databinding.ActivityDisguiseBinding
import com.system.launcher.tools.work.WorkProfileManager
import kotlin.math.roundToInt

/**
 * 伪装入口：显示小米游戏中心网页，左上角热区三连击进入隐私空间。
 */
class DisguiseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDisguiseBinding
    private var hasOpenedPrivacySpace = false

    companion object {
        private const val TAG = "DisguiseActivity"
        private const val GAME_CENTER_URL = "https://game.xiaomi.com"
        private const val STATUS_BAR_BLEND_DP = 20f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshManagedProfilePoliciesIfNeeded()

        binding = ActivityDisguiseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureGradientSystemBars()
        applyStatusBarInset()
        setupHotZone()
        setupWebView()
        setupBackNavigation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureGradientSystemBars()
    }

    private fun refreshManagedProfilePoliciesIfNeeded() {
        runCatching {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isProfileOwnerApp(packageName)) return
            WorkProfileManager(this).configureCrossProfileEntry()
            val receiver = ComponentName(packageName, "$packageName.ui.disguise.GameCenterProxyReceiver")
            packageManager.setComponentEnabledSetting(
                receiver,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "Refreshed managed profile policies")
        }.onFailure { error ->
            Log.w(TAG, "Unable to refresh managed profile policies", error)
        }
    }

    private fun configureGradientSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun applyStatusBarInset() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.hotZoneRoot) { _, windowInsets ->
            val insetTop = windowInsets
                .getInsets(WindowInsetsCompat.Type.statusBars())
                .top
                .takeIf { it > 0 }
                ?: getStatusBarHeight()
            val blendHeight = (STATUS_BAR_BLEND_DP * resources.displayMetrics.density).roundToInt()
            binding.statusBarGradient.updateLayoutParams<FrameLayout.LayoutParams> {
                height = insetTop + blendHeight
            }
            binding.gameCenterWebView.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = insetTop
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.hotZoneRoot)
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun setupHotZone() {
        binding.hotZoneRoot.onTripleTap = {
            openPrivacySpace()
        }
    }

    private fun setupWebView() {
        binding.gameCenterWebView.apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    return handleNonHttpUrl(request.url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    injectDownloadBannerCleanup(view)
                }
            }
            loadUrl(GAME_CENTER_URL)
        }
    }

    private fun injectDownloadBannerCleanup(webView: WebView) {
        val script = """
            (function() {
              if (window.__privacyCleanupInstalled) {
                if (window.__privacyCleanupRun) window.__privacyCleanupRun();
                return;
              }
              window.__privacyCleanupInstalled = true;

              function looksLikeBottomDownloadGuide(el) {
                if (!el || el.nodeType !== 1) return false;
                var rect = el.getBoundingClientRect();
                if (!rect || rect.width < window.innerWidth * 0.65) return false;
                if (rect.height < 44 || rect.height > 180) return false;
                if (rect.bottom < window.innerHeight - 6) return false;

                var style = window.getComputedStyle(el);
                var fixedToBottom = style.position === 'fixed' || style.position === 'sticky';
                if (!fixedToBottom && rect.top < window.innerHeight - 220) return false;

                var text = (el.innerText || el.textContent || '').replace(/\s+/g, '');
                return text.indexOf('下载') !== -1 &&
                  (text.indexOf('小米游戏中心') !== -1 || text.indexOf('发现游戏') !== -1);
              }

              function hideBottomDownloadGuide() {
                var nodes = document.body ? document.body.getElementsByTagName('*') : [];
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  if (looksLikeBottomDownloadGuide(el)) {
                    el.style.setProperty('display', 'none', 'important');
                    el.style.setProperty('visibility', 'hidden', 'important');
                    el.style.setProperty('pointer-events', 'none', 'important');
                  }
                }
                if (document.body) {
                  document.body.style.setProperty('padding-bottom', '0px', 'important');
                }
              }

              window.__privacyCleanupRun = hideBottomDownloadGuide;
              hideBottomDownloadGuide();
              new MutationObserver(hideBottomDownloadGuide).observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['class', 'style']
              });
              setInterval(hideBottomDownloadGuide, 1000);
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    private fun handleNonHttpUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return false

        return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure { error ->
            Log.w(TAG, "Unable to open external url=$uri", error)
        }.isSuccess
    }

    private fun openPrivacySpace() {
        if (hasOpenedPrivacySpace) return
        hasOpenedPrivacySpace = true
        val crossProfileIntent = Intent(WorkProfileManager.ACTION_OPEN_PRIVACY_SPACE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        try {
            startActivity(crossProfileIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Cross-profile entry failed, opening local MainActivity", e)
            val localIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(localIntent)
        }
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.gameCenterWebView.canGoBack()) {
                    binding.gameCenterWebView.goBack()
                } else {
                    finishWithoutAnimation()
                }
            }
        })
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.gameCenterWebView.destroy()
        }
        super.onDestroy()
    }
}

