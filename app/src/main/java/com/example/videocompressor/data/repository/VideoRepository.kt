package com.example.videocompressor.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.example.videocompressor.data.model.VideoInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
