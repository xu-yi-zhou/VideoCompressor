/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.example.videocompressor.data.model

import android.net.Uri

/**
 * 视频文件元数据，由 [com.example.videocompressor.data.repository.VideoRepository] 从系统 MediaStore
 * 和 [android.media.MediaMetadataRetriever] 中提取，用于 UI 展示与压缩流程初始化。
 *
 * @property uri        视频文件的 Content URI，由系统文件选择器授权。
 * @property name       原始文件名（含扩展名），用于生成输出文件名。
 * @property size       文件字节大小，用于压缩率计算与结果页展示。
 * @property durationMs 视频时长（毫秒），用于进度百分比换算。
 * @property width      视频宽度（像素）。
 * @property height     视频高度（像素）。
 * @property bitrate    综合码率（bps），包含音视频轨道。
 * @property codec      视频轨道 MIME 类型，如 "video/avc"、"video/hevc"。
 */
data class VideoInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val codec: String
)
