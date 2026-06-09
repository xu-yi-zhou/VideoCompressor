/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.example.videocompressor.data.model

/**
 * 设备编码能力画像。由 [com.example.videocompressor.domain.compressor.DeviceCodecProfiler]
 * 在启动时枚举本机真实硬件编码器后生成，用于驱动「设备感知自适应编码」。
 *
 * 针对小米 15S Pro 等搭载自研 **玄戒 O1（Xring O1）** SoC 的机型做了专门识别——
 * 旧逻辑硬编码高通 `c2.qti.*` 编码器名，在这些机型上完全失效。
 */
data class DeviceProfile(
    /** SoC 厂商（API 31+ 可读，否则为空） */
    val socManufacturer: String,
    /** SoC 型号（API 31+ 可读，否则为空） */
    val socModel: String,
    /** 设备型号，如 "Xiaomi 15S Pro" */
    val deviceModel: String,
    /** 是否识别为小米自研玄戒 SoC */
    val isXring: Boolean,

    /** 检测到的硬件 HEVC(H.265) 编码器组件名，找不到为 null */
    val hevcEncoderName: String?,
    /** HEVC 编码器是否支持恒定质量(CQ)码率模式 */
    val hevcSupportsCq: Boolean,
    /** HEVC 编码器是否支持 Main10（10-bit / HDR） */
    val hevcSupports10Bit: Boolean,

    /** 检测到的硬件 AVC(H.264) 编码器组件名 */
    val avcEncoderName: String?,
    /** AVC 编码器是否支持恒定质量(CQ)码率模式 */
    val avcSupportsCq: Boolean,

    /** 默认优先选择的编码 MIME（HEVC 优先） */
    val selectedMime: String,
    /** 默认优先选择的编码器组件名 */
    val selectedEncoderName: String?,
    /** 默认编码器是否支持恒定质量 */
    val supportsConstantQuality: Boolean,
    /** 编码器支持的最大码率(bps)，0 表示未知 */
    val maxBitrate: Int,

    /** 给 UI 展示的 SoC 文案 */
    val displaySoc: String,
    /** 给 UI 展示的多行检测结论 */
    val summaryLines: List<String>
)
