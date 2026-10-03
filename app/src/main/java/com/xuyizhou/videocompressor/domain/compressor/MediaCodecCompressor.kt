/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.domain.compressor

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import com.xuyizhou.videocompressor.data.model.CompressConfig
import com.xuyizhou.videocompressor.data.model.DeviceProfile
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Android MediaCodec API 的硬件加速视频压缩器，实现 [VideoCompressor] 接口。
 *
 * 核心转码流程为 **Surface-to-Surface**：解码器的输出 Surface 直接作为编码器的输入 Surface，
 * 省去 YUV 像素缓冲区的中间拷贝，充分利用 SoC 上的硬件视频加速单元。
 *
 * 主要特性：
 * - **设备感知编码**：通过 [DeviceCodecProfiler] 枚举本机真实硬件编码器，优先选择 HEVC，
 *   在玄戒 O1 等非高通 SoC 上同样能正确匹配编码器。
 * - **热节流自适应**：通过 [ThermalGovernor] 读取系统热状态，高温时降级至 H.264 或主动让出。
 * - **10-bit/HDR 保真**：检测输入 Main10 Profile，在支持的硬件上以 10-bit 输出。
 * - **音频直通**：音轨不重新编码，逐帧复制到输出 Muxer，保证音质无损。
 * - **帧率/旋转保真**：从轨道格式和 [android.media.MediaMetadataRetriever] 中读取真实值，
 *   避免 60fps 拍摄被误降速、竖屏视频被转正。
 */
@Singleton
class MediaCodecCompressor @Inject constructor(
    private val profiler: DeviceCodecProfiler,
    private val thermal: ThermalGovernor
) : VideoCompressor {

    companion object {
        private const val TAG = "MediaCodecCompressor"
    }

    private data class EncoderChoice(
        val mime: String,
        val name: String?,
        val supportsCq: Boolean,
        val supports10Bit: Boolean,
        val downshifted: Boolean
    )

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
        return@withContext try {
            val wakeLock = acquireWakeLock(context)
            try {
                transcode(context, inputUri, outputPath, config, onProgress)
            } finally {
                wakeLock.release()
            }
            val galleryUri = insertToMediaStore(context, outputPath, config)
            java.io.File(outputPath).delete()
            Result.success(galleryUri.toString())
        } catch (e: CancellationException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "压缩失败: ${e.message}", e)
            Result.failure(Exception("压缩失败: ${e.message}", e))
        }
    }

    override fun cancel() {
        cancelled = true
    }

    @Throws(Exception::class)
    private fun transcode(
        context: Context,
        inputUri: Uri,
        outputPath: String,
        config: CompressConfig,
        onProgress: (Float) -> Unit
    ) {
        val extractor = MediaExtractor().apply { setDataSource(context, inputUri, null) }

        val videoIdx = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw Exception("未找到视频轨道")

        val audioIdx = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

        val inputFormat = extractor.getTrackFormat(videoIdx)
        val srcWidth = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val srcHeight = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)
            .takeIf { it > 0 } ?: getDurationUs(context, inputUri)

        val (outW, outH) = calcOutputSize(srcWidth, srcHeight, config.resolution)
        val frameRate = resolveFrameRate(inputFormat, context, inputUri)
        val rotation = resolveRotation(inputFormat, context, inputUri)
        val is10BitInput = is10BitHevc(inputFormat)

        val outFps = frameRate
        val keyFrameIntervalSec = 2

        val profile = profiler.profile
        val downshift = thermal.shouldDownshiftToAvc()
        val choice = decideEncoder(config, profile, downshift)

        val sourceBitrate = runCatching {
            if (inputFormat.containsKey(MediaFormat.KEY_BIT_RATE))
                inputFormat.getInteger(MediaFormat.KEY_BIT_RATE) else 0
        }.getOrDefault(0).takeIf { it > 0 } ?: estimateSourceBitrate(context, inputUri, durationUs)

        val bitrate = calcBitrate(
            outW, outH, config.quality, outFps,
            isHevc = choice.mime == MediaFormat.MIMETYPE_VIDEO_HEVC,
            sourceBitrate = sourceBitrate,
            maxBitrate = profile.maxBitrate
        )

        val encoder = choice.name?.let { runCatching { MediaCodec.createByCodecName(it) }.getOrNull() }
            ?: MediaCodec.createEncoderByType(choice.mime)

        val use10Bit = is10BitInput && choice.supports10Bit
        configureEncoderWithFallback(
            encoder, choice, outW, outH, outFps, keyFrameIntervalSec, bitrate, config.quality, use10Bit
        )
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val decoder = MediaCodec.createDecoderByType(
            inputFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
        )
        decoder.configure(inputFormat, encoderSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) runCatching { muxer.setOrientationHint(rotation) }

        try {
            extractor.selectTrack(videoIdx)
            val decoderInputDone = java.util.concurrent.atomic.AtomicBoolean(false)
            val encoderInputDone = java.util.concurrent.atomic.AtomicBoolean(false)
            var muxerStarted = false
            var videoMuxerTrack = -1
            var audioMuxerTrack = -1
            var frameCount = 0
            var renderedCount = 0
            var lastProgressMs = 0L

            val encodeBufInfo = MediaCodec.BufferInfo()

            while (!cancelled && !encoderInputDone.get()) {
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
                            if (frameCount % 120 == 0 && thermal.shouldPace()) {
                                Thread.sleep(80)
                            }
                        }
                    }
                }

                val decBufInfo = MediaCodec.BufferInfo()
                val decOutIdx = decoder.dequeueOutputBuffer(decBufInfo, 10_000)
                if (decOutIdx >= 0) {
                    val isEos = decBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(decOutIdx, true)
                    renderedCount++
                    if (isEos) {
                        encoder.signalEndOfInputStream()
                    }
                }

                val encOutIdx = encoder.dequeueOutputBuffer(encodeBufInfo, 10_000)
                if (encOutIdx >= 0) {
                    val encBuf = encoder.getOutputBuffer(encOutIdx)!!

                    if (encodeBufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        encoder.releaseOutputBuffer(encOutIdx, false)
                        continue
                    }

                    if (encodeBufInfo.size > 0 && muxerStarted) {
                        muxer.writeSampleData(videoMuxerTrack, encBuf, encodeBufInfo)
                        if (durationUs > 0 && encodeBufInfo.presentationTimeUs > 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastProgressMs >= 200) {
                                lastProgressMs = now
                                val progress = (encodeBufInfo.presentationTimeUs.toFloat() / durationUs)
                                    .coerceIn(0f, 0.99f)
                                onProgress(progress)
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(encOutIdx, false)

                    if (encodeBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderInputDone.set(true)
                    }
                } else if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxerStarted) {
                    videoMuxerTrack = muxer.addTrack(encoder.outputFormat)
                    if (audioIdx != null) {
                        val audioExt = MediaExtractor().apply { setDataSource(context, inputUri, null) }
                        audioMuxerTrack = muxer.addTrack(audioExt.getTrackFormat(audioIdx))
                        audioExt.release()
                    }
                    muxer.start()
                    muxerStarted = true
                }
            }

            if (cancelled) throw CancellationException("用户取消")

            if (audioIdx != null && audioMuxerTrack >= 0) {
                copyAudioFrames(context, inputUri, muxer, audioIdx, audioMuxerTrack)
            }

            muxer.stop()

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

    private fun copyAudioFrames(
        context: Context,
        inputUri: Uri,
        muxer: MediaMuxer,
        audioIdx: Int,
        audioMuxerTrack: Int
    ) = copyAudioFramesFrom(MediaExtractor().apply { setDataSource(context, inputUri, null) }, muxer, audioIdx, audioMuxerTrack)

    @SuppressLint("WrongConstant")
    private fun copyAudioFramesFrom(
        audioExtractor: MediaExtractor,
        muxer: MediaMuxer,
        audioIdx: Int,
        audioMuxerTrack: Int
    ) {
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
        } catch (e: Exception) {
            Log.e(TAG, "音频复制失败: ${e.message}", e)
        } finally {
            audioExtractor.release()
        }
    }

    private fun calcOutputSize(
        w: Int, h: Int, resolution: CompressConfig.Resolution
    ): Pair<Int, Int> {
        if (resolution == CompressConfig.Resolution.KEEP_ORIGINAL) return w to h
        val parts = resolution.tag.split("x")
        val tw = parts[0].toInt()
        val th = parts[1].toInt()
        val scale = minOf(tw.toFloat() / w, th.toFloat() / h)
        return ((w * scale).toInt() / 2 * 2) to ((h * scale).toInt() / 2 * 2)
    }

    private fun decideEncoder(
        config: CompressConfig,
        profile: DeviceProfile,
        downshift: Boolean
    ): EncoderChoice {
        val avc = EncoderChoice(
            mime = MediaFormat.MIMETYPE_VIDEO_AVC,
            name = profile.avcEncoderName,
            supportsCq = profile.avcSupportsCq,
            supports10Bit = false,
            downshifted = downshift
        )
        val hevc = profile.hevcEncoderName?.let {
            EncoderChoice(
                mime = MediaFormat.MIMETYPE_VIDEO_HEVC,
                name = it,
                supportsCq = profile.hevcSupportsCq,
                supports10Bit = profile.hevcSupports10Bit,
                downshifted = false
            )
        }
        val wantsAvcOnly = config.encoder == CompressConfig.Encoder.H264
        return when {
            wantsAvcOnly -> avc
            downshift && profile.avcEncoderName != null -> avc
            hevc != null -> hevc
            else -> avc
        }
    }

    @SuppressLint("InlinedApi")
    private fun configureEncoderWithFallback(
        encoder: MediaCodec,
        choice: EncoderChoice,
        outW: Int,
        outH: Int,
        frameRate: Int,
        keyFrameIntervalSec: Int,
        bitrate: Int,
        quality: CompressConfig.Quality,
        use10Bit: Boolean
    ) {
        fun buildFormat(with10Bit: Boolean, withExtras: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(choice.mime, outW, outH).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                if (choice.supportsCq) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    )
                    setInteger(MediaFormat.KEY_QUALITY, mapCqQuality(quality))
                }
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                if (with10Bit) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    )
                }
                if (withExtras) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 1)
                    }
                    setInteger(MediaFormat.KEY_PRIORITY, 1)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                }
            }

        val attempts = linkedSetOf(
            use10Bit to true,
            use10Bit to false,
            false to true,
            false to false
        )
        var lastError: Exception? = null
        for ((tenBit, extras) in attempts) {
            try {
                encoder.configure(buildFormat(tenBit, extras), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.d(TAG, "编码器配置成功 (10bit=$tenBit, extras=$extras)")
                return
            } catch (e: Exception) {
                lastError = e
                runCatching { encoder.reset() }
            }
        }
        throw lastError ?: IllegalStateException("编码器配置失败")
    }

    private fun mapCqQuality(quality: CompressConfig.Quality): Int =
        (90 - (quality.crf - 18) * 3.2).toInt().coerceIn(30, 95)

    private fun calcBitrate(
        w: Int,
        h: Int,
        quality: CompressConfig.Quality,
        frameRate: Int,
        isHevc: Boolean,
        sourceBitrate: Int,
        maxBitrate: Int
    ): Int {
        val pixels = w.toLong() * h
        val base = when (quality) {
            CompressConfig.Quality.HIGH     -> pixels * 4
            CompressConfig.Quality.BALANCED -> pixels * 2
            CompressConfig.Quality.SMALL    -> pixels * 1
        }
        var result = base * (100 + (frameRate - 30).coerceAtLeast(0) * 100 / 60) / 100
        if (isHevc) result = result * 70 / 100
        if (sourceBitrate in 1 until result) result = sourceBitrate.toLong()
        if (maxBitrate in 1 until result) result = maxBitrate.toLong()
        return result.coerceAtLeast(200_000).toInt()
    }

    private fun estimateSourceBitrate(context: Context, uri: Uri, durationUs: Long): Int {
        if (durationUs <= 0) return 0
        val bytes = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: 0L
        if (bytes <= 0) return 0
        return (bytes * 8.0 / (durationUs / 1_000_000.0)).toInt()
    }

    private fun resolveFrameRate(format: MediaFormat, context: Context, uri: Uri): Int {
        runCatching {
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                val fr = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                if (fr in 1..240) return fr
            }
        }
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val cap = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()
                if (cap != null && cap in 1f..240f) return cap.toInt()
            } finally {
                r.release()
            }
        }
        return 30
    }

    private fun resolveRotation(format: MediaFormat, context: Context, uri: Uri): Int {
        runCatching {
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                return format.getInteger(MediaFormat.KEY_ROTATION)
            }
        }
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                if (rot != null) return rot
            } finally {
                r.release()
            }
        }
        return 0
    }

    private fun is10BitHevc(format: MediaFormat): Boolean {
        return runCatching {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            if (!mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) return false
            if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                val p = format.getInteger(MediaFormat.KEY_PROFILE)
                if (p == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    p == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    p == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                ) return true
            }
            if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                val t = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                if (t == MediaFormat.COLOR_TRANSFER_ST2084 || t == MediaFormat.COLOR_TRANSFER_HLG) return true
            }
            false
        }.getOrDefault(false)
    }

    private fun getDurationUs(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
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
            acquire(2 * 60 * 60 * 1000L) // 2 hours max; finally block releases early on completion
        }
    }

    @SuppressLint("InlinedApi")
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

}
