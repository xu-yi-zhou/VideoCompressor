package com.example.videocompressor.domain.compressor

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import com.example.videocompressor.data.model.CompressConfig
import com.example.videocompressor.data.model.DeviceProfile
import java.nio.ByteBuffer
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCodecCompressor @Inject constructor(
    private val profiler: DeviceCodecProfiler,
    private val thermal: ThermalGovernor
) : VideoCompressor {

    companion object {
        private const val TAG = "MediaCodecCompressor"
    }

    /** 编码器选择结果（含针对该编码器探测到的能力）。 */
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
        Log.d(TAG, "=== 开始压缩 ===")
        Log.d(TAG, "输入 URI: $inputUri")
        Log.d(TAG, "输出路径: $outputPath")
        Log.d(TAG, "配置: quality=${config.quality}, resolution=${config.resolution}, encoder=${config.encoder}")

        return@withContext try {
            val wakeLock = acquireWakeLock(context)
            Log.d(TAG, "WakeLock 已获取")
            try {
                transcode(context, inputUri, outputPath, config, onProgress)
            } finally {
                wakeLock.release()
                Log.d(TAG, "WakeLock 已释放")
            }
            Log.d(TAG, "=== 转码成功, 写入相册 ===")
            val outBytes = java.io.File(outputPath).length()
            Log.d(TAG, "输出文件: ${outBytes / 1_000_000}MB")
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
        context: Context,
        inputUri: Uri,
        outputPath: String,
        config: CompressConfig,
        onProgress: (Float) -> Unit
    ) {
        Log.d(TAG, "--- transcode 开始 ---")
        val extractor = MediaExtractor().apply { setDataSource(context, inputUri, null) }
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
            .takeIf { it > 0 } ?: getDurationUs(context, inputUri)
        Log.d(TAG, "原始分辨率: ${srcWidth}x${srcHeight}, 时长: ${durationUs / 1_000_000}s, MIME: ${inputFormat.getString(MediaFormat.KEY_MIME)}")

        // 输出分辨率
        val (outW, outH) = calcOutputSize(srcWidth, srcHeight, config.resolution)
        Log.d(TAG, "输出分辨率: ${outW}x${outH}")

        // 保真：读取真实帧率（旧逻辑硬编码 30fps，会把小米常见的 60fps 拍摄压成慢动作）
        val frameRate = resolveFrameRate(inputFormat, context, inputUri)
        // 保真：读取旋转角度，竖屏视频不再被转正
        val rotation = resolveRotation(inputFormat, context, inputUri)
        // 保真：检测 10-bit / HDR 输入（小米默认杜比视界/10-bit 录制）
        val is10BitInput = is10BitHevc(inputFormat)

        val outFps = frameRate
        val keyFrameIntervalSec = 2
        Log.d(TAG, "帧率: $frameRate, 旋转: $rotation°, 10-bit输入: $is10BitInput, " +
                "关键帧间隔: ${keyFrameIntervalSec}s")

        // 设备感知：根据本机真实硬件编码器画像 + 当前热状态决定编码器
        val profile = profiler.profile
        val downshift = thermal.shouldDownshiftToAvc()
        val choice = decideEncoder(config, profile, downshift)
        Log.d(TAG, "编码决策: mime=${choice.mime}, name=${choice.name}, CQ=${choice.supportsCq}, " +
                "10bit=${choice.supports10Bit}, 高温降级=${choice.downshifted}, 热状态=${thermal.currentLevel().label}")

        // 源码率：优先读视频轨道，读不到则按 文件大小/时长 估算
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
        Log.d(TAG, "目标码率: ${bitrate / 1000} kbps (源 ${sourceBitrate / 1000} kbps, ${outFps}fps)")

        // 创建并配置编码器（优先按组件名锁定到探测出的硬件编码器）
        val encoder = choice.name?.let { runCatching { MediaCodec.createByCodecName(it) }.getOrNull() }
            ?: MediaCodec.createEncoderByType(choice.mime)

        val use10Bit = is10BitInput && choice.supports10Bit
        // 10-bit 配置在个别机型可能失败，做一次安全回退到 8-bit
        configureEncoderWithFallback(
            encoder, choice, outW, outH, outFps, keyFrameIntervalSec, bitrate, config.quality, use10Bit
        )
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
        // 保真：写入旋转角度，必须在 muxer.start() 之前
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
            // 进度回调节流：上次回调的时间戳（毫秒）
            var lastProgressMs = 0L

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
                            // 热节流限速：严重高温时周期性让出，给 SoC 散热窗口，避免被系统降频/杀进程
                            if (frameCount % 120 == 0 && thermal.shouldPace()) {
                                Log.w(TAG, "检测到严重高温，转码限速让出 80ms")
                                Thread.sleep(80)
                            }
                        }
                    }
                }

                // 2. 取 decoder 输出（渲染到 encoder surface）
                val decBufInfo = MediaCodec.BufferInfo()
                val decOutIdx = decoder.dequeueOutputBuffer(decBufInfo, 10_000)
                if (decOutIdx >= 0) {
                    val isEos = decBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(decOutIdx, true)
                    renderedCount++
                    if (isEos) {
                        Log.d(TAG, "Decoder EOS, 通知 encoder (渲染 $renderedCount 帧)")
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
                        // 进度基于编码器输出 PTS，反映实际已完成的编码量。
                        // 关键：必须节流——长视频几十万帧，若每帧都回调，Service 端每帧重建通知 +
                        // 向 system_server 发 binder，会把转码线程拖到近乎停滞（2 小时视频卡在 0%）。
                        if (durationUs > 0 && encodeBufInfo.presentationTimeUs > 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastProgressMs >= 200) {
                                lastProgressMs = now
                                val progress = (encodeBufInfo.presentationTimeUs.toFloat() / durationUs)
                                    .coerceIn(0f, 0.99f)
                                Log.d(TAG, "进度: ${(progress * 100).toInt()}%, frame=$frameCount, encPts=${encodeBufInfo.presentationTimeUs}")
                                onProgress(progress)
                            }
                        }
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
                        val audioExt = MediaExtractor().apply { setDataSource(context, inputUri, null) }
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
                copyAudioFrames(context, inputUri, muxer, audioIdx, audioMuxerTrack)
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
        context: Context,
        inputUri: Uri,
        muxer: MediaMuxer,
        audioIdx: Int,
        audioMuxerTrack: Int
    ) = copyAudioFramesFrom(MediaExtractor().apply { setDataSource(context, inputUri, null) }, muxer, audioIdx, audioMuxerTrack)

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

    /**
     * 设备感知编码决策：用本机真实探测出的编码器画像 + 当前热状态，决定走 HEVC 还是 AVC。
     * - 用户强制 H.264，或高温节流 → AVC（编码负载更低、更省电）
     * - 否则优先 HEVC（同画质体积更小），无 HEVC 硬编时退回 AVC
     */
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
        val wantsAvcOnly = config.encoder == CompressConfig.Encoder.FFMPEG_H264
        return when {
            wantsAvcOnly -> avc
            downshift && profile.avcEncoderName != null -> avc
            hevc != null -> hevc
            else -> avc
        }
    }

    /** 配置编码器；10-bit 在个别机型可能 configure 失败，自动回退 8-bit 重试。 */
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
                // 关键帧间隔 2s
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                if (choice.supportsCq) {
                    // 恒定质量编码：让此前形同虚设的 crf 真正生效，质量/体积比优于固定码率
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
                    )
                    setInteger(MediaFormat.KEY_QUALITY, mapCqQuality(quality))
                }
                // 即便走 CQ 也给出码率上限提示，避免极端场景体积失控
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                if (with10Bit) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    )
                }
                if (withExtras) {
                    // B 帧：同画质下进一步减小体积（部分编码器不支持，失败会自动回退）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 1)
                    }
                    // 转码是离线任务，按非实时最高吞吐运行以加速
                    setInteger(MediaFormat.KEY_PRIORITY, 1)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                }
            }

        // 依次降级尝试：完整 → 去加速项 → 去 10-bit → 最朴素，确保各机型都能配置成功
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
                Log.w(TAG, "编码器配置失败 (10bit=$tenBit, extras=$extras)，降级重试: ${e.message}")
                runCatching { encoder.reset() }
            }
        }
        throw lastError ?: IllegalStateException("编码器配置失败")
    }

    /** crf(越小越好) → CQ 的 KEY_QUALITY(越大越好) 单调映射。 */
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
            CompressConfig.Quality.HIGH     -> pixels * 4      // 1080p@30 ≈ 8 Mbps
            CompressConfig.Quality.BALANCED -> pixels * 2      // 1080p@30 ≈ 4 Mbps
            CompressConfig.Quality.SMALL    -> pixels * 1      // 1080p@30 ≈ 2 Mbps
        }
        // 帧率缩放：以 30fps 为基准，高帧率亚线性增加（60fps ≈ 1.5×），避免高帧率视频被压糊
        var result = base * (100 + (frameRate - 30).coerceAtLeast(0) * 100 / 60) / 100
        // HEVC 同画质比 H.264 省约 30%，目标码率相应下调，吃满 HEVC 的体积优势
        if (isHevc) result = result * 70 / 100
        // 不超过源码率：再编码到比原视频更高的码率只会徒增体积、无画质收益
        if (sourceBitrate in 1 until result) result = sourceBitrate.toLong()
        // 不超过编码器声明上限
        if (maxBitrate in 1 until result) result = maxBitrate.toLong()
        // 下限保护，避免极低码率花屏
        return result.coerceAtLeast(200_000).toInt()
    }

    /** 无法从轨道读到源码率时，用 文件大小×8 / 时长 估算（含音频，作为上限足够保守）。 */
    private fun estimateSourceBitrate(context: Context, uri: Uri, durationUs: Long): Int {
        if (durationUs <= 0) return 0
        val bytes = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: 0L
        if (bytes <= 0) return 0
        return (bytes * 8.0 / (durationUs / 1_000_000.0)).toInt()
    }

    /** 读取真实帧率：优先轨道格式，其次元数据拍摄帧率，最后兜底 30。 */
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

    /** 读取旋转角度：优先轨道格式，其次元数据。 */
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

    /** 检测输入是否为 10-bit HEVC（Main10）或 HDR 传输特性。 */
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)
            ) {
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

}
