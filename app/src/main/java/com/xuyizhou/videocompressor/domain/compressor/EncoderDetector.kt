/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.domain.compressor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 硬件 HEVC 编码器探测器（旧版实现，仅适用于高通 SoC）。
 *
 * 该类通过枚举已知的高通编码器组件名称来探测最优 HEVC 编码器，
 * 在非高通 SoC（如小米玄戒、联发科等）上无法正确工作。
 *
 * 已被 [DeviceCodecProfiler] 取代，后者通过 [MediaCodecList] 动态枚举，
 * 可适配任意 SoC 厂商的编码器组件名。本类保留以备参考。
 */
@Singleton
class EncoderDetector @Inject constructor() {

    val bestHevcEncoder: String by lazy { detectBestHevcEncoder() }

    private fun detectBestHevcEncoder(): String {
        val qualcommEncoders = listOf(
            "c2.qti.hevc.encoder",
            "OMX.qcom.video.encoder.hevc",
            "c2.qti.hevc.encoder.avc"
        )
        return qualcommEncoders.firstOrNull { isEncoderAvailable(it) }
            ?: "libx265"
    }

    private fun isEncoderAvailable(name: String): Boolean {
        return runCatching {
            MediaCodec.createByCodecName(name).release()
            true
        }.getOrDefault(false)
    }
}
