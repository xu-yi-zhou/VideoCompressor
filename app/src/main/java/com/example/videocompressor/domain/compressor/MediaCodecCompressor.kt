package com.example.videocompressor.domain.compressor

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import com.example.videocompressor.data.model.CompressConfig
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCodecCompressor @Inject constructor() : VideoCompressor {

    companion object {
        private const val TAG = "MediaCodecCompressor"
    }

    @Volatile
    private var cancelled = false

    override suspend fun compress(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        config: CompressConfig,
        onProgress: (Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        cancelled = false
        Log.d(TAG, "=== 开始压缩 ===")
        Log.d(TAG, "输入 URI: $inputUri")
        Log.d(TAG, "输出路径: $outputPath")
        Log.d(TAG, "配置: quality=${config.quality}, resolution=${config.resolution}, encoder=${config.encoder}")

        val tmpInput = copyToCache(context, inputUri)
        if (tmpInput == null) {
            Log.e(TAG, "无法复制文件到缓存")
            return@withContext Result.failure(Exception("无法读取文件"))
        }
        Log.d(TAG, "缓存文件: ${tmpInput.absolutePath} (${tmpInput.length()} bytes)")

        // 清理旧缓存文件（超过 1 小时的临时文件）
        cleanOldCache(context)

        return@withContext try {
            val wakeLock = acquireWakeLock(context)
            Log.d(TAG, "WakeLock 已获取")
            try {
                transcode(tmpInput.absolutePath, outputPath, config, onProgress)
            } finally {
                wakeLock.release()
                Log.d(TAG, "WakeLock 已释放")
            }
            Log.d(TAG, "=== 转码成功, 写入相册 ===")
            val galleryUri = insertToMediaStore(context, outputPath, config)
            java.io.File(outputPath).delete()
            Log.d(TAG, "已保存至相册: $galleryUri")
            Result.success(galleryUri.toString())
        } catch (e: CancellationException) {
            Log.d(TAG, "压缩被取消")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "压缩失败: ${e.message}", e)
            Result.failure(Exception("压缩失败: ${e.message}", e))
        } finally {
            tmpInput.delete()
        }
    }

    override fun cancel() {
        cancelled = true
    }

    // ──────────────────────────────────────────
    // 核心转码逻辑
    // ──────────────────────────────────────────

    @Throws(Exception::class)
    private fun transcode(
        inputPath: String,
        outputPath: String,
        config: CompressConfig,
        onProgress: (Float) -> Unit
    ) {
        Log.d(TAG, "--- transcode 开始 ---")
        val extractor = MediaExtractor().apply { setDataSource(inputPath) }
        Log.d(TAG, "MediaExtractor 已设置数据源, 轨道数: ${extractor.trackCount}")

        // 打印所有轨道信息
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            Log.d(TAG, "  轨道 $i: ${fmt.getString(MediaFormat.KEY_MIME)}")
        }

        val videoIdx = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw Exception("未找到视频轨道")

        val audioIdx = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        Log.d(TAG, "视频轨道索引: $videoIdx, 音频轨道索引: $audioIdx")

        val inputFormat = extractor.getTrackFormat(videoIdx)
        val srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)
            .takeIf { it > 0 } ?: getDurationUs(inputPath)
        Log.d(TAG, "原始分辨率: ${srcWidth}x${srcHeight}, 时长: ${durationUs / 1_000_000}s, MIME: ${inputFormat.getString(MediaFormat.KEY_MIME)}")

        // 输出分辨率
        val (outW, outH) = calcOutputSize(srcWidth, srcHeight, config.resolution)
        Log.d(TAG, "输出分辨率: ${outW}x${outH}")

        // 选择编码器
        val mime = selectEncoderMime(config.encoder)
        Log.d(TAG, "编码器 MIME: $mime")

        val bitrate = calcBitrate(outW, outH, config.quality)
        Log.d(TAG, "目标码率: ${bitrate / 1000} kbps")

        // 创建并配置编码器
        val encoder = MediaCodec.createEncoderByType(mime)
        val encodeFormat = MediaFormat.createVideoFormat(mime, outW, outH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        encoder.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        Log.d(TAG, "编码器已配置: ${encoder.codecInfo.name}")
        val encoderSurface = encoder.createInputSurface()
        encoder.start()
        Log.d(TAG, "编码器已启动")

        // 创建解码器，输出到 encoder 的 surface
        val decoder = MediaCodec.createDecoderByType(
            inputFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
        )
        decoder.configure(inputFormat, encoderSurface, null, 0)
        decoder.start()
        Log.d(TAG, "解码器已启动: ${decoder.codecInfo.name}")

        // Muxer
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            extractor.selectTrack(videoIdx)
            val decoderInputDone = java.util.concurrent.atomic.AtomicBoolean(false)
            val encoderInputDone = java.util.concurrent.atomic.AtomicBoolean(false)
            var muxerStarted = false
            var videoMuxerTrack = -1
            var audioMuxerTrack = -1
            var frameCount = 0

            val encodeBufInfo = MediaCodec.BufferInfo()

            Log.d(TAG, "进入主转码循环, durationUs=$durationUs")

            // 主循环：喂 decoder → 取 encoder → 写 muxer
            while (!cancelled && !encoderInputDone.get()) {
                // 1. 喂 decoder 输入
                if (!decoderInputDone.get()) {
                    val decInIdx = decoder.dequeueInputBuffer(10_000)
                    if (decInIdx >= 0) {
                        val buf = decoder.getInputBuffer(decInIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            Log.d(TAG, "Decoder 输入结束 (EOS)")
                            decoder.queueInputBuffer(decInIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decoderInputDone.set(true)
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(decInIdx, 0, size, pts,
                                extractor.sampleFlags)
                            extractor.advance()
                            frameCount++
                            // 进度
                            if (durationUs > 0) {
                                val progress = (pts.toFloat() / durationUs).coerceAtMost(1f)
                                if (frameCount % 30 == 0) {
                                    Log.d(TAG, "进度: ${(progress * 100).toInt()}%, frame=$frameCount, pts=$pts")
                                }
                                onProgress(progress)
                            }
                        }
                    }
                }

                // 2. 取 decoder 输出（渲染到 encoder surface）
                val decBufInfo = MediaCodec.BufferInfo()
                val decOutIdx = decoder.dequeueOutputBuffer(decBufInfo, 10_000)
                if (decOutIdx >= 0) {
                    decoder.releaseOutputBuffer(decOutIdx, true)
                    if (decBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "Decoder EOS, 通知 encoder")
                        encoder.signalEndOfInputStream()
                    }
                }

                // 3. 取 encoder 输出 → 写入 muxer
                val encOutIdx = encoder.dequeueOutputBuffer(encodeBufInfo, 10_000)
                if (encOutIdx >= 0) {
                    val encBuf = encoder.getOutputBuffer(encOutIdx)!!

                    if (encodeBufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        Log.d(TAG, "收到编码器 CSD 数据")
                        encoder.releaseOutputBuffer(encOutIdx, false)
                        continue
                    }

                    if (encodeBufInfo.size > 0 && muxerStarted) {
                        muxer.writeSampleData(videoMuxerTrack, encBuf, encodeBufInfo)
                    }

                    encoder.releaseOutputBuffer(encOutIdx, false)

                    if (encodeBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "Encoder EOS, 转码完成, 共处理 $frameCount 帧")
                        encoderInputDone.set(true)
                    }
                } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxerStarted) {
                    Log.d(TAG, "Encoder 输出格式已确定, 启动 muxer")
                    videoMuxerTrack = muxer.addTrack(encoder.outputFormat)
                    // 在 muxer.start() 前添加音频轨道
                    if (audioIdx != null) {
                        val audioExt = MediaExtractor().apply { setDataSource(inputPath) }
                        audioMuxerTrack = muxer.addTrack(audioExt.getTrackFormat(audioIdx))
                        audioExt.release()
                        Log.d(TAG, "音频轨道已添加: $audioMuxerTrack")
                    }
                    muxer.start()
                    muxerStarted = true
                }
            }

            if (cancelled) throw CancellationException("用户取消")

            // 视频编码完成后，复制音频帧
            if (audioIdx != null && audioMuxerTrack >= 0) {
                Log.d(TAG, "开始复制音频帧...")
                copyAudioFrames(inputPath, muxer, audioIdx, audioMuxerTrack)
            }

            Log.d(TAG, "停止 muxer...")
            muxer.stop()
            Log.d(TAG, "转码完成, 输出文件: $outputPath")

        } finally {
            encoderSurface.release()
            decoder.stop()
            decoder.release()
            encoder.stop()
            encoder.release()
            muxer.release()
            extractor.release()
        }
    }

    // ──────────────────────────────────────────
    // 音频轨直通复制
    // ──────────────────────────────────────────

    private fun copyAudioFrames(
        inputPath: String,
        muxer: MediaMuxer,
        audioIdx: Int,
        audioMuxerTrack: Int
    ) {
        val audioExtractor = MediaExtractor().apply { setDataSource(inputPath) }
        try {
            audioExtractor.selectTrack(audioIdx)

            val buf = ByteBuffer.allocateDirect(256 * 1024)
            val bufInfo = MediaCodec.BufferInfo()
            var audioFrameCount = 0

            while (!cancelled) {
                buf.clear()
                val size = audioExtractor.readSampleData(buf, 0)
                if (size < 0) break

                bufInfo.offset = 0
                bufInfo.size = size
                bufInfo.presentationTimeUs = audioExtractor.sampleTime
                bufInfo.flags = audioExtractor.sampleFlags

                muxer.writeSampleData(audioMuxerTrack, buf, bufInfo)
                audioExtractor.advance()
                audioFrameCount++
            }
            Log.d(TAG, "音频复制完成, 共 $audioFrameCount 帧")
        } catch (e: Exception) {
            Log.e(TAG, "音频复制失败: ${e.message}", e)
        } finally {
            audioExtractor.release()
        }
    }

    // ──────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────

    private fun calcOutputSize(
        w: Int, h: Int, resolution: CompressConfig.Resolution
    ): Pair<Int, Int> {
        if (resolution == CompressConfig.Resolution.KEEP_ORIGINAL) return w to h
        val parts = resolution.tag.split("x")
        val tw = parts[0].toInt()
        val th = parts[1].toInt()
        // 等比缩放，偶数宽高
        val scale = minOf(tw.toFloat() / w, th.toFloat() / h)
        return ((w * scale).toInt() / 2 * 2) to ((h * scale).toInt() / 2 * 2)
    }

    private fun selectEncoderMime(encoder: CompressConfig.Encoder): String {
        val preferHevc = encoder == CompressConfig.Encoder.HARDWARE_HEVC ||
                encoder == CompressConfig.Encoder.FFMPEG_HEVC ||
                encoder == CompressConfig.Encoder.AUTO
        if (preferHevc && isEncoderAvailable(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            return MediaFormat.MIMETYPE_VIDEO_HEVC
        }
        return MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private fun isEncoderAvailable(mime: String): Boolean {
        return runCatching {
            // 尝试找硬件编码器
            for (codecInfo in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (codecInfo.isEncoder && codecInfo.supportedTypes.contains(mime)) {
                    return true
                }
            }
            false
        }.getOrDefault(false)
    }

    private fun calcBitrate(w: Int, h: Int, quality: CompressConfig.Quality): Int {
        val pixels = w * h
        return when (quality) {
            CompressConfig.Quality.HIGH     -> pixels * 4      // 1080p ≈ 8 Mbps
            CompressConfig.Quality.BALANCED -> pixels * 2      // 1080p ≈ 4 Mbps
            CompressConfig.Quality.SMALL    -> pixels * 1      // 1080p ≈ 2 Mbps
        }
    }

    private fun getDurationUs(path: String): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L) * 1000L
        } finally {
            r.release()
        }
    }

    private fun acquireWakeLock(context: Context): PowerManager.WakeLock {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VideoCompressor:compress"
        ).apply {
            acquire()
        }
    }

    private fun insertToMediaStore(context: Context, filePath: String, config: CompressConfig): Uri {
        val file = java.io.File(filePath)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: throw Exception("无法创建 MediaStore 条目")

        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)

        return uri
    }

    private fun cleanOldCache(context: Context) {
        val cacheDir = context.cacheDir
        val cutoff = System.currentTimeMillis() - 3600_000 // 1 小时前
        cacheDir.listFiles()?.filter { it.name.startsWith("input_") && it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    private fun copyToCache(context: Context, uri: Uri): java.io.File? {
        val tmp = java.io.File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp
        } catch (_: Exception) {
            null
        }
    }
}
