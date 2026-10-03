/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xuyizhou.videocompressor.data.model.CompressConfig
import com.xuyizhou.videocompressor.data.model.VideoInfo
import com.xuyizhou.videocompressor.data.netdisk.NetdiskAuthStore
import com.xuyizhou.videocompressor.data.netdisk.NetdiskClient
import com.xuyizhou.videocompressor.domain.usecase.CompressVideoUseCase
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 视频压缩前台服务，一次运行顺序压缩一个视频列表（单视频即列表长度为 1）。
 *
 * 批量循环放在服务内部而非由 ViewModel 逐个拉起：Android 12+ 禁止应用在后台
 * 启动前台服务，若 App 退到后台时由 ViewModel 启动下一轮会抛
 * `ForegroundServiceStartNotAllowedException`；服务内循环则全程保持同一个前台服务。
 *
 * 进度与结果经 [CompressProgressBus] 回传 ViewModel：
 * 整体进度 = (当前序号 + 单视频进度) / 总数，单项失败不中断后续视频。
 *
 * 开启自动上传（Intent extra `auto_upload`）时，每个视频压缩成功后立即
 * 经 [NetdiskClient] 上传到百度网盘应用目录；单视频进度按压缩段 0~0.9、
 * 上传段 0.9~1.0 划分，上传失败记为 [BatchResult.uploadError]，不中断后续视频。
 */
@AndroidEntryPoint
class CompressService : Service() {

    @Inject lateinit var compressUseCase: CompressVideoUseCase
    @Inject lateinit var progressBus: CompressProgressBus
    @Inject lateinit var netdiskStore: NetdiskAuthStore
    @Inject lateinit var netdiskClient: NetdiskClient

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "compress_channel"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uriStrings: List<String> = intent?.getStringArrayListExtra("video_uris") ?: emptyList()
        val names: List<String> = intent?.getStringArrayListExtra("video_names") ?: emptyList()
        val configJson: String? = intent?.getStringExtra("config")
        val autoUpload = intent?.getBooleanExtra("auto_upload", false) ?: false

        if (uriStrings.isEmpty() || configJson == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val config = gson.fromJson(configJson, CompressConfig::class.java)
        val count = uriStrings.size

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0f, 0, count, names.firstOrNull() ?: ""))

        serviceScope.launch {
            var doneCount = 0
            var failedCount = 0
            val results = mutableListOf<BatchResult>()
            val seenStems = mutableMapOf<String, Int>()

            for ((index, uriString) in uriStrings.withIndex()) {
                val displayName = names.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: "video_$index"
                // 输出名 = 源文件名 + _compressed；批量中同名视频按出现次序加序号避免相互覆盖
                val stem = displayName.substringBeforeLast('.')
                val occurrence = seenStems.merge(stem, 1, Int::plus) ?: 1
                val outputFileName = if (occurrence > 1) "${stem}_compressed_$occurrence.mp4"
                else "${stem}_compressed.mp4"
                val videoInfo = VideoInfo(
                    uri = Uri.parse(uriString),
                    name = displayName,
                    size = 0L,
                    durationMs = 0L,
                    width = 0,
                    height = 0,
                    bitrate = 0,
                    codec = ""
                )

                // 单视频进度：压缩段 0~0.9，上传段 0.9~1.0。
                // 通知与总线按 500ms 节流（1GB 上传会产生上万次回调），到 100% 时强制发射。
                var lastEmit = 0L
                fun emitProgress(
                    itemProgress: Float,
                    uploading: Boolean,
                    uploadFrac: Float,
                    force: Boolean = false
                ) {
                    val now = System.currentTimeMillis()
                    if (!force && itemProgress < 1f && now - lastEmit < 500L) return
                    lastEmit = now
                    val overall = (index + itemProgress.coerceIn(0f, 1f)) / count
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(overall, index, count, displayName, uploading))
                    progressBus.progress(overall, index, count, displayName, uploading, uploadFrac)
                }

                val result = compressUseCase(
                    context = this@CompressService,
                    videoInfo = videoInfo,
                    config = config,
                    occurrence = occurrence,
                    onProgress = { p -> emitProgress(p * 0.9f, false, 0f) }
                )

                result.fold(
                    onSuccess = { path ->
                        doneCount++
                        var uploadPath: String? = null
                        var uploadError: String? = null
                        if (autoUpload) {
                            emitProgress(0.9f, true, 0f, force = true)
                            if (!netdiskStore.hasAuth()) {
                                uploadError = "网盘未授权"
                            } else if (!netdiskClient.ensureRemoteDir()) {
                                uploadError = "创建网盘目录失败"
                            } else {
                                netdiskClient.uploadFromUri(Uri.parse(path), outputFileName) { up ->
                                    emitProgress(0.9f + 0.1f * up, true, up)
                                }.fold(
                                    onSuccess = { uploadPath = it },
                                    onFailure = { e -> uploadError = e.message ?: "上传失败" }
                                )
                            }
                        }
                        results += BatchResult(
                            index = index,
                            name = displayName,
                            outputUri = path,
                            uploadPath = uploadPath,
                            uploadError = uploadError
                        )
                    },
                    onFailure = { e ->
                        failedCount++
                        results += BatchResult(index = index, name = displayName, error = e.message ?: "未知错误")
                    }
                )
            }

            progressBus.done(results.sortedBy { it.index })
            showDoneNotification(doneCount, failedCount)

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(
        overall: Float,
        index: Int,
        count: Int,
        name: String,
        uploading: Boolean = false
    ): Notification {
        val pct = (overall * 100).toInt()
        val phase = if (uploading) " · 上传中" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("视频压缩中（第 ${index + 1}/$count 个）")
            .setContentText("$name · $pct%$phase")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .build()
    }

    private fun showDoneNotification(doneCount: Int, failedCount: Int) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("压缩完成")
            .setContentText("成功 $doneCount 个，失败 $failedCount 个")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID + 1, notif)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "视频压缩",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
