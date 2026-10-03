/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.domain.usecase

import android.content.Context
import com.xuyizhou.videocompressor.data.model.CompressConfig
import com.xuyizhou.videocompressor.data.model.VideoInfo
import com.xuyizhou.videocompressor.data.repository.VideoRepository
import com.xuyizhou.videocompressor.domain.compressor.VideoCompressor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频压缩用例，封装"生成输出路径 → 调用压缩器"的完整业务流程。
 *
 * 按照整洁架构（Clean Architecture）的用例（Use Case）模式设计：
 * - 对外隐藏路径生成与压缩器调用的细节；
 * - 通过 operator fun invoke 支持函数式调用风格（`compressUseCase(...)`）。
 *
 * 由 Hilt 以单例形式注入。
 */
@Singleton
class CompressVideoUseCase @Inject constructor(
    private val compressor: VideoCompressor,
    private val repository: VideoRepository
) {
    suspend operator fun invoke(
        context: Context,
        videoInfo: VideoInfo,
        config: CompressConfig,
        occurrence: Int = 1,
        onProgress: (Float) -> Unit
    ): Result<String> {
        val outputPath = repository.generateOutputPath(videoInfo.name, occurrence)
        return compressor.compress(context, videoInfo.uri, outputPath, config, onProgress)
    }
}
