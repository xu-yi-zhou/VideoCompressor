package com.example.videocompressor.domain.compressor

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 热节流调速器。长时间硬件转码会触发 SoC 温控降频，小米/HyperOS 机型尤其激进（甚至杀后台）。
 * 这里读取系统热状态，给压缩管线提供「是否降级 / 是否限速」决策，并供 UI 实时展示。
 */
@Singleton
class ThermalGovernor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class Level(val label: String) {
        NONE("正常"),
        LIGHT("轻微发热"),
        MODERATE("中度发热"),
        SEVERE("高温节流"),
        CRITICAL("严重高温"),
        UNKNOWN("未知")
    }

    private val pm: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    /** 当前系统热状态。API 29 以下不可读，返回 UNKNOWN。 */
    fun currentLevel(): Level {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Level.UNKNOWN
        return when (runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)) {
            PowerManager.THERMAL_STATUS_NONE -> Level.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> Level.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> Level.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> Level.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> Level.CRITICAL
            else -> Level.UNKNOWN
        }
    }

    /** 距离触发温控的余量预测(0..1，越小越烫)，API 30+ 才有，否则 null。 */
    fun headroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { pm.getThermalHeadroom(10) }
            .getOrNull()
            ?.takeIf { !it.isNaN() && it >= 0f }
    }

    /** 高温节流及以上：HEVC 编码负载更高，应降级到更省电的 H.264。 */
    fun shouldDownshiftToAvc(): Boolean = when (currentLevel()) {
        Level.SEVERE, Level.CRITICAL -> true
        else -> false
    }

    /** 严重高温：转码循环应短暂让出，给 SoC 散热窗口。 */
    fun shouldPace(): Boolean = currentLevel() == Level.CRITICAL
}
