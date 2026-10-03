/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.data.model

import com.google.gson.annotations.SerializedName

/**
 * GitHub Releases API（GET /repos/{owner}/{repo}/releases/latest）响应模型。
 * 放在 data.model 包以复用 proguard 的 Gson keep 规则（-keep data.model.** { *; }）。
 */
data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    /** 发布标题，可为空 */
    val name: String? = null,
    /** 更新说明（Markdown 原文，UI 按纯文本展示） */
    val body: String? = null,
    /** 浏览器打开该 release 页面的兜底链接 */
    @SerializedName("html_url") val htmlUrl: String = "",
    val assets: List<GithubAsset> = emptyList()
)

data class GithubAsset(
    val name: String = "",
    val size: Long = 0L,
    @SerializedName("browser_download_url") val browserDownloadUrl: String = ""
)
