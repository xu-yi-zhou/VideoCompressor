/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装工具：自更新下载完成后调系统安装器安装。
 * Android 8+ 首次会弹「允许安装未知应用」系统确认，属正常流程。
 */
object ApkInstaller {

    /** 本应用是否已获得「安装未知应用」授权（minSdk 26 恒可用） */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 跳系统设置页开启「允许安装未知应用」 */
    fun openInstallSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }
    }

    /**
     * 调系统安装器安装 [file]（经 FileProvider 暴露的 cacheDir/update/ 下 APK）。
     * 不做 resolveActivity 判空：Android 11+ 包可见性会使其返回 null 即使安装器
     * 存在，直接尝试启动，失败返回 false 由调用方走浏览器兜底。
     */
    fun installApk(context: Context, file: File): Boolean {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 兜底：浏览器打开 release 页面让用户手动下载安装 */
    fun openInBrowser(context: Context, url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
