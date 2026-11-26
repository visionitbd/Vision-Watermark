package com.visionit.watermark.util

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import com.visionit.watermark.R

object PremiumPopupHelper {
    fun show(context: Context) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_premium_free, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val widthPx = (context.resources.displayMetrics.widthPixels * 0.88f).toInt()
        dialog.window?.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        view.findViewById<TextView>(R.id.btnClosePremium).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }
}
