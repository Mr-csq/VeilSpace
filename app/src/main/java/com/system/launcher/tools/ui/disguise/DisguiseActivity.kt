package com.system.launcher.tools.ui.disguise

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.system.launcher.tools.MainActivity
import com.system.launcher.tools.databinding.ActivityDisguiseBinding
import com.system.launcher.tools.work.WorkProfileManager

/**
 * 伪装入口：显示小米游戏中心网页，左上角热区三连击进入隐私空间。
 */
class DisguiseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDisguiseBinding
    private var hasOpenedPrivacySpace = false

    companion object {
        private const val TAG = "DisguiseActivity"
        private const val GAME_CENTER_URL = "https://game.xiaomi.com"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "DisguiseActivity onCreate")
        refreshManagedProfilePoliciesIfNeeded()

        binding = ActivityDisguiseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureGradientSystemBars()
        applyStatusBarInset()
        setupHotZone()
        setupWebView()
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
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE

        var flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun applyStatusBarInset() {
        val statusBarHeight = getStatusBarHeight()
        binding.statusBarGradient.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            statusBarHeight
        )
        (binding.gameCenterWebView.layoutParams as FrameLayout.LayoutParams).topMargin = statusBarHeight
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

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return handleNonHttpUrl(Uri.parse(url))
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(script, null)
        } else {
            webView.loadUrl("javascript:$script")
        }
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

        Log.d(TAG, "Triple tap detected, launching privacy space")
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

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (::binding.isInitialized && binding.gameCenterWebView.canGoBack()) {
            binding.gameCenterWebView.goBack()
        } else {
            finishWithoutAnimation()
        }
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.gameCenterWebView.destroy()
        }
        super.onDestroy()
        Log.d(TAG, "DisguiseActivity onDestroy")
    }
}

