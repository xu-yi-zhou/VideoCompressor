package com.example.videocompressor.domain.compressor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import javax.inject.Inject
import javax.inject.Singleton

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
