package com.example.videocompressor.domain.transcribe

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 把视频里的音轨原样抽成 m4a（不重新编码），用于上传到电脑端转写。
 *
 * 比上传整段视频小得多：一节两小时的课，视频几个 GB，抽出的 AAC 音频通常只有几十 MB，
 * 局域网上传秒级完成。转写本身只需要音频，所以软字幕(SRT)走这条路；
 * "烧进视频"才需要上传原视频（见 [com.example.videocompressor.service.TranscribeService]）。
 */
object AudioExtractor {

    private const val TAG = "AudioExtractor"

    fun extractToM4a(context: Context, uri: Uri): File {
        val out = File(context.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
        val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        try {
            val audioIdx = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("视频里没有音轨，无法转写")

            val format = extractor.getTrackFormat(audioIdx)
            extractor.selectTrack(audioIdx)

            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrack = muxer.addTrack(format)
            muxer.start()

            val maxInput = runCatching {
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            }.getOrDefault(0).takeIf { it > 0 } ?: (256 * 1024)

            val buffer = ByteBuffer.allocate(maxInput)
            val info = MediaCodec.BufferInfo()
            var frames = 0
            try {
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxTrack, buffer, info)
                    extractor.advance()
                    frames++
                }
            } finally {
                runCatching { muxer.stop() }
                muxer.release()
            }
            Log.d(TAG, "音频抽取完成: ${out.name}, $frames 帧, ${out.length() / 1024}KB")
            return out
        } finally {
            extractor.release()
        }
    }
}
