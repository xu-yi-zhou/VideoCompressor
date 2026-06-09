/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.example.videocompressor.domain.compressor

import android.content.Context
import android.net.Uri
import com.example.videocompressor.data.model.CompressConfig

/**
 * 视频压缩器接口，定义视频转码的标准契约。
 *
 * 当前唯一实现为 [MediaCodecCompressor]，使用 Android 系统的 MediaCodec API
 * 完成 Surface-to-Surface 硬件加速转码。通过接口隔离，便于未来扩展其他实现
 * （如 FFmpeg 软件编码、云端转码等）。
 */
interface VideoCompressor {

    suspend fun compress(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        config: CompressConfig,
        onProgress: (Float) -> Unit
    ): Result<String>

    fun cancel()
}
