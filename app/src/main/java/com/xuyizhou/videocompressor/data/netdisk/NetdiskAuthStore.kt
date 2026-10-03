/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.data.netdisk

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 百度网盘 OAuth 令牌本地存储，基于 [android.content.SharedPreferences]（MODE_PRIVATE，仅本应用可读）。
 *
 * 说明：令牌以明文存于应用私有目录，本机 root/备份场景下存在泄露风险；
 * 权衡后不引入额外加密依赖（androidx.security-crypto 已弃用），个人使用可接受。
 * access_token 约 30 天有效，上传前由 [NetdiskClient.ensureFreshToken] 用 refresh_token 自动续期。
 */
@Singleton
class NetdiskAuthStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("netdisk_auth", Context.MODE_PRIVATE)

    var accessToken: String
        get() = prefs.getString(KEY_ACCESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    var refreshToken: String
        get() = prefs.getString(KEY_REFRESH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    /** 过期时间戳（毫秒，UTC epoch） */
    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES, value).apply()

    /** 网盘昵称（授权成功后拉取） */
    var baiduName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_NAME).apply()
            else prefs.edit().putString(KEY_NAME, value).apply()
        }

    fun hasAuth(): Boolean = accessToken.isNotBlank() && refreshToken.isNotBlank()

    /** 「压缩后自动上传」开关（与令牌同仓持久化，避免多一份 prefs 文件）。 */
    var autoUploadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPLOAD, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPLOAD, value).apply()

    fun saveAuth(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_NAME = "baidu_name"
        const val KEY_AUTO_UPLOAD = "auto_upload"
    }
}
