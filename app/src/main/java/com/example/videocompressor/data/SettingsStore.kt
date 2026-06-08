package com.example.videocompressor.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 轻量持久化设置。目前只存"电脑服务地址"，避免每次进字幕页都要重新输入局域网 IP。
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
