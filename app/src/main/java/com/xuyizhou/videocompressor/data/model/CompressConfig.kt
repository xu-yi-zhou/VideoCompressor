/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.data.model

/**
 * 视频压缩配置参数，用于序列化传递给后台压缩服务。
 *
 * @property quality    画质档位，决定目标码率系数。
 * @property resolution 输出分辨率，[Resolution.KEEP_ORIGINAL] 表示保持原始分辨率。
 * @property encoder    编码器偏好，最终由 [com.xuyizhou.videocompressor.domain.compressor.MediaCodecCompressor]
 *                      结合设备实际硬件能力做最终决策。
 * @property audioEnabled 是否保留音轨（当前版本始终保留）。
 */
data class CompressConfig(
    val quality: Quality = Quality.BALANCED,
    val resolution: Resolution = Resolution.KEEP_ORIGINAL,
    val encoder: Encoder = Encoder.AUTO,
    val audioEnabled: Boolean = true
) {
    enum class Quality(val crf: Int, val label: String) {
        HIGH(18, "高质量 (~5-8GB)"),
        BALANCED(26, "均衡 (~2-4GB)"),
        SMALL(32, "最小体积 (~1-2GB)")
    }

    enum class Resolution(val tag: String, val label: String) {
        KEEP_ORIGINAL("original", "保持原始"),
        FHD("1920x1080", "1080p"),
        HD("1280x720", "720p"),
        SD("854x480", "480p")
    }

    enum class Encoder(val label: String) {
        AUTO("自动选择"),
        HARDWARE_HEVC("硬件 H.265（骁龙优先）"),
        FFMPEG_HEVC("FFmpeg H.265"),
        FFMPEG_H264("FFmpeg H.264")
    }
}
