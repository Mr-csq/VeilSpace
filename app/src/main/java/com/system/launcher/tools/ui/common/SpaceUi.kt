package com.system.launcher.tools.ui.common

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.system.launcher.tools.R

object SpaceUi {
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    private const val SAFE_CLICK_INTERVAL_MS = 650L

    fun reveal(view: View) {
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 14f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(380L)
            .setInterpolator(emphasized)
            .start()
    }

    fun applySystemBarInsets(view: View) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    fun attachPressScale(view: View, scale: Float = 0.97f) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> target.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(90L)
                    .setInterpolator(emphasized)
                    .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(emphasized)
                    .start()
            }
            false
        }
    }

    fun setSafeClickListener(
        view: View,
        intervalMs: Long = SAFE_CLICK_INTERVAL_MS,
        onClick: (View) -> Unit
    ) {
        var lastAcceptedClick = 0L
        view.setOnClickListener { clickedView ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastAcceptedClick < intervalMs) return@setOnClickListener
            lastAcceptedClick = now
            onClick(clickedView)
        }
    }

    fun configureList(recyclerView: RecyclerView) {
        recyclerView.itemAnimator = DefaultItemAnimator().apply {
            addDuration = 260L
            removeDuration = 180L
            moveDuration = 280L
            changeDuration = 220L
        }
    }

    fun swap(from: View, to: View) {
        if (to.visibility == View.VISIBLE) return
        to.alpha = 0f
        to.translationY = -to.resources.displayMetrics.density * 8f
        to.visibility = View.VISIBLE
        to.animate().alpha(1f).translationY(0f).setDuration(240L).setInterpolator(emphasized).start()
        from.animate().alpha(0f).translationY(-from.resources.displayMetrics.density * 6f).setDuration(150L)
            .withEndAction {
                from.visibility = View.GONE
                from.alpha = 1f
                from.translationY = 0f
            }.start()
    }

    fun haptic(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
}

fun Fragment.showSpaceMessage(message: CharSequence, long: Boolean = false, error: Boolean = false) {
    val anchor = view ?: return
    Snackbar.make(anchor, message, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
        .setBackgroundTint(ContextCompat.getColor(requireContext(), if (error) R.color.space_error_container else R.color.space_surface_high))
        .setTextColor(ContextCompat.getColor(requireContext(), if (error) R.color.space_error else R.color.space_text_primary))
        .setActionTextColor(ContextCompat.getColor(requireContext(), R.color.space_primary))
        .setAnimationMode(Snackbar.ANIMATION_MODE_SLIDE)
        .show()
}

fun MaterialAlertDialogBuilder.showSpace() = create().also { dialog ->
    dialog.setOnShowListener {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            dialog.window?.attributes = dialog.window?.attributes?.apply { blurBehindRadius = 48 }
        }
    }
    dialog.show()
}
