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
import android.app.PendingIntent
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

@AndroidEntryPoint
class CompressService : Service() {

    @Inject lateinit var compressUseCase: CompressVideoUseCase
    @Inject lateinit var progressBus: CompressProgressBus

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "compress_channel"
        const val ACTION_COMPRESS_COMPLETE = "com.xuyizhou.videocompressor.COMPRESS_COMPLETE"
        const val ACTION_COMPRESS_ERROR = "com.xuyizhou.videocompressor.COMPRESS_ERROR"
        const val ACTION_PROGRESS_UPDATE = "com.xuyizhou.videocompressor.PROGRESS_UPDATE"
        const val EXTRA_OUTPUT_PATH = "output_path"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_PROGRESS = "progress"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoUri: Uri? = intent?.getParcelableExtra("video_uri")
        val configJson: String? = intent?.getStringExtra("config")

        if (videoUri == null || configJson == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val config = gson.fromJson(configJson, CompressConfig::class.java)
        val videoInfo = VideoInfo(
            uri = videoUri,
            name = "video_${System.currentTimeMillis()}",
            size = 0L,
            durationMs = 0L,
            width = 0,
            height = 0,
            bitrate = 0,
            codec = ""
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0f))

        serviceScope.launch {
            val result = compressUseCase(
                context = this@CompressService,
                videoInfo = videoInfo,
                config = config,
                onProgress = { progress ->
                    val notif = buildNotification(progress)
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notif)

                    progressBus.progress(progress)
                }
            )

            result.fold(
                onSuccess = { path ->
                    progressBus.complete(path)
                    showDoneNotification(path)
                },
                onFailure = { e ->
                    progressBus.error(e.message ?: "未知错误")
                    showErrorNotification(e.message ?: "未知错误")
                }
            )

            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(progress: Float): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("视频压缩中")
            .setContentText("进度：${(progress * 100).toInt()}%")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .build()
    }

    private fun showDoneNotification(path: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("压缩完成")
            .setContentText("视频已保存")
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID + 1, notif)
    }

    private fun showErrorNotification(message: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("压缩失败")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
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
