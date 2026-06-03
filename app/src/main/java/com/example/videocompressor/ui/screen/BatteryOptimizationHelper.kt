package com.example.videocompressor.ui.screen

import android.os.Build
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {
    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) {
            val intent = Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
        // 通用方式
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }
    }
}
