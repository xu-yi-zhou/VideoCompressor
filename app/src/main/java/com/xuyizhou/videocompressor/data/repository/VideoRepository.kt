/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.xuyizhou.videocompressor.data.model.VideoInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频文件数据仓库，负责以下两项职责：
 * 1. 从系统 [android.provider.OpenableColumns] 和 [MediaMetadataRetriever] 中提取视频元数据；
 * 2. 根据原始文件名生成压缩输出文件的本地路径（存放于应用外部 Movies 目录）。
 *
 * 通过 Hilt 以单例形式注入，避免多次重复创建 [MediaMetadataRetriever] 开销。
 */
@Singleton
class VideoRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getVideoInfo(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)

        val name = getFileName(uri)
        val size = getFileSize(uri)
        val duration = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 0
        val bitrate = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_BITRATE
        )?.toIntOrNull() ?: 0
        val codec = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_MIMETYPE
        ) ?: "unknown"

        retriever.release()

        return VideoInfo(uri, name, size, duration, width, height, bitrate, codec)
    }

    fun generateOutputPath(originalName: String): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val nameWithoutExt = originalName.substringBeforeLast(".")
        return "${dir.absolutePath}/${nameWithoutExt}_compressed.mp4"
    }

    private fun getFileName(uri: Uri): String {
        var name = "video"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    name = cursor.getString(idx)
                }
            }
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) {
                    size = cursor.getLong(idx)
                }
            }
        }
        return size
    }
}
