/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.example.videocompressor.ui.screen

import android.os.Build
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化豁免辅助工具类。
 *
 * 长时间视频压缩任务在 MIUI/HyperOS 等激进省电策略下极易被后台杀死，
 * 本类封装了引导用户将本应用加入电池优化白名单的逻辑：
 * 小米设备优先跳转至 MIUI 专属省电策略页；其他设备使用系统通用接口。
 */
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
