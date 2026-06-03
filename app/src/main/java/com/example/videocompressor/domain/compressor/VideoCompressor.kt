package com.example.videocompressor.domain.compressor

import android.content.Context
import android.net.Uri
import com.example.videocompressor.data.model.CompressConfig

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
