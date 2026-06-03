package com.example.videocompressor.data.model

import android.net.Uri

data class VideoInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val codec: String
)
