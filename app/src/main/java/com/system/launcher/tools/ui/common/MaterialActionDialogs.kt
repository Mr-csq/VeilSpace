package com.system.launcher.tools.ui.common

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.launcher.tools.R

object MaterialActionDialogs {
    data class Action(
        val title: String,
        @DrawableRes val iconRes: Int,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    fun show(context: Context, title: String, actions: List<Action>) {
        fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
        val pendingClicks = mutableListOf<Pair<View, () -> Unit>>()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }

        content.addView(TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.ui_text_primary))
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        })

        actions.forEach { action ->
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(58)
                setPadding(0, dp(4), 0, dp(4))
                foreground = selectableDrawable(context)
            }

            val iconColor = ContextCompat.getColor(
                context,
                if (action.destructive) R.color.ui_error else R.color.ui_on_secondary_container
            )
            row.addView(FrameLayout(context).apply {
                background = ContextCompat.getDrawable(context, R.drawable.bg_action_sheet_icon)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
                addView(ImageView(context).apply {
                    setImageResource(action.iconRes)
                    setColorFilter(iconColor)
                    layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)
                })
            })

            row.addView(TextView(context).apply {
                text = action.title
                setTextColor(ContextCompat.getColor(context, if (action.destructive) R.color.ui_error else R.color.ui_text_primary))
                textSize = 17f
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
            pendingClicks += row to action.onClick
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(content)
            .create()
        pendingClicks.forEach { (row, onClick) ->
            row.setOnClickListener {
                dialog.dismiss()
                onClick()
            }
        }
        dialog.show()
    }

    private fun selectableDrawable(context: Context) = TypedValue().let { value ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        ContextCompat.getDrawable(context, value.resourceId)
    }
}



