/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.domain.compressor

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.xuyizhou.videocompressor.data.model.DeviceProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备编码能力探测器：在本机真实枚举硬件视频编码器，查询其码率模式 / 10-bit / 最大码率等能力，
 * 并识别 SoC（重点识别小米自研 **玄戒 O1 / Xring O1**），产出 [DeviceProfile]。
 *
 * 取代旧的 `EncoderDetector`——后者把高通 `c2.qti.*` 编码器名硬编码，在玄戒/联发科机型上必然失配。
 */
@Singleton
class DeviceCodecProfiler @Inject constructor() {

    companion object {
        private const val TAG = "DeviceCodecProfiler"
    }

    val profile: DeviceProfile by lazy { buildProfile() }

    private data class EncoderCaps(
        val name: String,
        val supportsCq: Boolean,
        val supports10Bit: Boolean,
        val maxBitrate: Int,
        val hardware: Boolean
    )

    private fun buildProfile(): DeviceProfile {
        val socMfr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else ""
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""
        val brand = Build.MANUFACTURER.orEmpty()
        val deviceModel = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }.joinToString(" ")

        val isXring = detectXring(socMfr, socModel, brand)

        val hevc = findBestEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val avc = findBestEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)

        val selected = hevc ?: avc
        val selectedMime =
            if (hevc != null) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val displaySoc = when {
            isXring -> "玄戒 ${socModel.ifBlank { "O1" }}".trim()
            socModel.isNotBlank() -> listOf(socMfr, socModel).filter { it.isNotBlank() }.joinToString(" ")
            else -> Build.HARDWARE.orEmpty().ifBlank { "未知" }
        }

        val summary = buildList {
            add("设备：$deviceModel")
            add("芯片：$displaySoc${if (isXring) "（已识别小米自研芯片）" else ""}")
            if (hevc != null) {
                add("H.265 硬编：${shortName(hevc.name)}${if (hevc.hardware) "（硬件）" else "（软件）"}")
            } else {
                add("H.265 硬编：不可用，将使用 H.264")
            }
            add("恒定质量(CQ)：${if (selected?.supportsCq == true) "支持，启用恒定画质编码" else "不支持，使用目标码率"}")
            add("10-bit / HDR：${if (hevc?.supports10Bit == true) "支持，可保真" else "不支持"}")
            if (selected != null && selected.maxBitrate > 0) {
                add("编码器最大码率：${selected.maxBitrate / 1_000_000} Mbps")
            }
        }

        return DeviceProfile(
            socManufacturer = socMfr,
            socModel = socModel,
            deviceModel = deviceModel,
            isXring = isXring,
            hevcEncoderName = hevc?.name,
            hevcSupportsCq = hevc?.supportsCq ?: false,
            hevcSupports10Bit = hevc?.supports10Bit ?: false,
            avcEncoderName = avc?.name,
            avcSupportsCq = avc?.supportsCq ?: false,
            selectedMime = selectedMime,
            selectedEncoderName = selected?.name,
            supportsConstantQuality = selected?.supportsCq ?: false,
            maxBitrate = selected?.maxBitrate ?: 0,
            displaySoc = displaySoc,
            summaryLines = summary
        ).also { Log.d(TAG, "设备编码画像: $it") }
    }

    private fun detectXring(socMfr: String, socModel: String, brand: String): Boolean {
        val soc = "$socMfr $socModel".lowercase()
        if (soc.contains("xring") || socModel.contains("玄戒")) return true
        val isXiaomi = brand.contains("xiaomi", true) ||
                brand.contains("redmi", true) ||
                socMfr.contains("xiaomi", true)
        return isXiaomi && Regex("\\bo1\\b", RegexOption.IGNORE_CASE).containsMatchIn(socModel)
    }

    private fun findBestEncoder(mime: String): EncoderCaps? {
        var softwareFallback: EncoderCaps? = null
        runCatching {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue

                val supportsCq = runCatching {
                    caps.encoderCapabilities?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    ) ?: false
                }.getOrDefault(false)

                val supports10Bit = caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                }

                val maxBitrate = runCatching {
                    caps.videoCapabilities.bitrateRange.upper
                }.getOrDefault(0)

                val item = EncoderCaps(info.name, supportsCq, supports10Bit, maxBitrate, isHardware(info))
                if (item.hardware) return item
                if (softwareFallback == null) softwareFallback = item
            }
        }.onFailure { Log.w(TAG, "枚举 $mime 编码器失败: ${it.message}") }
        return softwareFallback
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val n = info.name.lowercase()
            !n.startsWith("omx.google") && !n.startsWith("c2.android")
        }
    }

    private fun shortName(name: String): String = name.substringAfterLast('.')
}
