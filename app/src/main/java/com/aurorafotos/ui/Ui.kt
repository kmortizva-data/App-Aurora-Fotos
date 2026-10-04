package com.aurorafotos.ui

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.aurorafotos.R

/** Tiny helpers so the Views-based UI stays short. */
object Ui {
    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun label(context: Context, text: String, dim: Boolean = false, size: Float = 14f, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(context.getColor(if (dim) R.color.text_dim else R.color.text))
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(context: Context, text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            background = context.getDrawable(
                when {
                    danger -> R.drawable.bg_button_danger
                    primary -> R.drawable.bg_button
                    else -> R.drawable.bg_button_secondary
                }
            )
            setTextColor(context.getColor(if (primary) R.color.bg else R.color.text))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 48)).apply {
                topMargin = dp(context, 10)
            }
            setOnClickListener { onClick() }
        }

    fun card(context: Context, selected: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = context.getDrawable(if (selected) R.drawable.bg_card_selected else R.drawable.bg_card)
        val p = dp(context, 14)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 10)
        }
        isClickable = true
        isFocusable = true
    }

    fun hasPermissions(activity: Activity, perms: Array<String>): Boolean =
        perms.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun show(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }

    val REQUIRED_PERMISSIONS = arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.POST_NOTIFICATIONS)
}
