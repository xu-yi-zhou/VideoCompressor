package com.example.videocompressor.data.model

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
