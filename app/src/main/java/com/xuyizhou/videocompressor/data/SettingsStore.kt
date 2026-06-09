/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用轻量级持久化设置仓库，基于 [android.content.SharedPreferences] 实现。
 *
 * 当前持久化内容：
 * - [serverUrl]：局域网内电脑转写服务的地址（如 `192.168.1.20:8000`），
 *   避免用户每次进入字幕页都要重新输入。
 *
 * 通过 Hilt 以单例形式注入，保证全局唯一实例。
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_SERVER_URL, value).apply()
        }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
    }
}
