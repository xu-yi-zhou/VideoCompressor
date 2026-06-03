package com.example.videocompressor.domain.usecase

import android.content.Context
import com.example.videocompressor.data.model.CompressConfig
import com.example.videocompressor.data.model.VideoInfo
import com.example.videocompressor.data.repository.VideoRepository
import com.example.videocompressor.domain.compressor.VideoCompressor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompressVideoUseCase @Inject constructor(
    private val compressor: VideoCompressor,
    private val repository: VideoRepository
) {
    suspend operator fun invoke(
        context: Context,
        videoInfo: VideoInfo,
        config: CompressConfig,
        onProgress: (Float) -> Unit
    ): Result<String> {
        val outputPath = repository.generateOutputPath(videoInfo.name)
        return compressor.compress(context, videoInfo.uri, outputPath, config, onProgress)
    }
}
