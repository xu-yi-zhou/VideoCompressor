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
 */
@AndroidEntryPoint
class CompressService : Service() {

    @Inject lateinit var compressUseCase: CompressVideoUseCase
    @Inject lateinit var progressBus: CompressProgressBus

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

            for ((index, uriString) in uriStrings.withIndex()) {
                val displayName = names.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: "video_$index"
                // 批量时输出名加序号后缀，避免同名视频相互覆盖
                val outputName = if (count > 1) uniquify(displayName, index) else displayName
                val videoInfo = VideoInfo(
                    uri = Uri.parse(uriString),
                    name = outputName,
                    size = 0L,
                    durationMs = 0L,
                    width = 0,
                    height = 0,
                    bitrate = 0,
                    codec = ""
                )

                val result = compressUseCase(
                    context = this@CompressService,
                    videoInfo = videoInfo,
                    config = config,
                    onProgress = { progress ->
                        val overall = (index + progress.coerceIn(0f, 1f)) / count
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(overall, index, count, displayName))
                        progressBus.progress(overall, index, count, displayName)
                    }
                )

                result.fold(
                    onSuccess = { path ->
                        doneCount++
                        results += BatchResult(index = index, name = displayName, outputUri = path)
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

    /** 「name.mp4」→「name_2.mp4」：加序号后缀防批量重名冲突。 */
    private fun uniquify(name: String, index: Int): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return "${stem}_${index + 1}$ext"
    }

    private fun buildNotification(overall: Float, index: Int, count: Int, name: String): Notification {
        val pct = (overall * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("视频压缩中（第 ${index + 1}/$count 个）")
            .setContentText("$name · $pct%")
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
