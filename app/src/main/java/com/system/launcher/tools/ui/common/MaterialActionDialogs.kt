package com.system.launcher.tools.ui.common

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.system.launcher.tools.R

object MaterialActionDialogs {
    data class Action(
        val title: String,
        @DrawableRes val iconRes: Int,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    fun show(context: Context, title: String, actions: List<Action>): BottomSheetDialog {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
        val dialog = BottomSheetDialog(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.space_surface_high))
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dp(1), ContextCompat.getColor(context, R.color.space_outline))
            }
        }

        content.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.space_text_tertiary))
                cornerRadius = dp(3).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(22)
        })

        content.addView(TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.space_text_primary))
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        actions.forEach { action ->
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(64)
                setPadding(dp(4), dp(5), dp(4), dp(5))
                isClickable = true
                isFocusable = true
            }
            val iconColor = ContextCompat.getColor(context, if (action.destructive) R.color.space_error else R.color.space_secondary_bright)
            row.addView(FrameLayout(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_action_sheet_icon)
                addView(ImageView(context).apply {
                    setImageResource(action.iconRes)
                    setColorFilter(iconColor)
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                })
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(16) })
            row.addView(TextView(context).apply {
                text = action.title
                setTextColor(ContextCompat.getColor(context, if (action.destructive) R.color.space_error else R.color.space_text_primary))
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            SpaceUi.attachPressScale(row, 0.985f)
            SpaceUi.setSafeClickListener(row) {
                dialog.dismiss()
                action.onClick()
            }
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        }

        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.window?.apply {
                setDimAmount(0.62f)
                navigationBarColor = ContextCompat.getColor(context, R.color.space_background_deep)
            }
            SpaceUi.reveal(content)
        }
        dialog.show()
        return dialog
    }
}
