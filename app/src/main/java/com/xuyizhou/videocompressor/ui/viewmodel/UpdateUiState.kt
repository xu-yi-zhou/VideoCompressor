/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.ui.viewmodel

import com.xuyizhou.videocompressor.data.model.GithubRelease
import java.io.File

/**
 * 自更新状态机（独立于压缩状态，见 [CompressViewModel.updateState]）。
 *
 * Idle → Checking → Available（弹窗）/ UpToDate / CheckFailed（卡片状态行展示）
 * Available → Downloading → Downloaded（安装）/ DownloadFailed（重试）
 * Downloaded 点安装 → installLaunched=true（防重复拉起系统安装器）。
 */
sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    data object UpToDate : UpdateUiState()

    /** 发现新版本，弹窗提示 */
    data class Available(val release: GithubRelease) : UpdateUiState()

    data class CheckFailed(val message: String) : UpdateUiState()

    data class Downloading(
        val progress: Float,
        val bytesRead: Long,
        /** -1 表示远端未提供 Content-Length */
        val total: Long,
        val release: GithubRelease
    ) : UpdateUiState()

    data class Downloaded(
        val file: File,
        val release: GithubRelease,
        /** 是否已触发系统安装器（防重复拉起） */
        val installLaunched: Boolean = false
    ) : UpdateUiState()

    data class DownloadFailed(
        val message: String,
        val release: GithubRelease
    ) : UpdateUiState()
}
