/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.example.videocompressor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.videocompressor.domain.transcribe.AudioExtractor
import com.example.videocompressor.domain.transcribe.TranscribeClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 前台服务：把字幕/转录工作甩给电脑端处理。两种模式：
 *  - [MODE_TRANSCRIBE] 软字幕：抽音频 → 上传转写 → 写旁挂文件（SRT/章节/总结）。
 *  - [MODE_BURN] 烧字幕：上传整段视频 → 电脑端烧字幕+重编码 → 下载成品保存到相册。
 *
 * 进度/结果经 [TranscribeBus] 回传 ViewModel（同 [CompressProgressBus] 的进程内总线思路）。
 */
@AndroidEntryPoint
class TranscribeService : Service() {

    @Inject lateinit var client: TranscribeClient
    @Inject lateinit var bus: TranscribeBus

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "transcribe_channel"
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SRT_PATH = "srt_path"
        const val MODE_TRANSCRIBE = "transcribe"
        const val MODE_BURN = "burn"
        const val MODE_BURN_SRT = "burn_srt"

        private const val TAG = "TranscribeService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoUri: Uri? = intent?.getParcelableExtra(EXTRA_VIDEO_URI)
        val server = intent?.getStringExtra(EXTRA_SERVER_URL)?.trim()
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: "video_${System.currentTimeMillis()}"
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_TRANSCRIBE
        val srtPath = intent?.getStringExtra(EXTRA_SRT_PATH)

        if (videoUri == null || server.isNullOrBlank()) {
            bus.error("未选择视频或未填写电脑服务地址")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("准备中…"))

        serviceScope.launch {
            try {
                when (mode) {
                    MODE_BURN -> runBurn(videoUri, server, displayName)
                    MODE_BURN_SRT -> runBurnWithSrt(videoUri, server, displayName, srtPath)
                    else -> runTranscribe(videoUri, server, displayName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "字幕任务失败: ${e.message}", e)
                bus.error(e.message ?: "未知错误")
                showResultNotification("字幕任务失败", e.message ?: "未知错误")
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runTranscribe(videoUri: Uri, server: String, displayName: String) {
        bus.running("抽取音频…", null)
        updateNotification("抽取音频…")
        val audio = AudioExtractor.extractToM4a(this, videoUri)
        try {
            val result = client.transcribe(server, audio) { stage, progress ->
                bus.running(stage, progress)
                updateNotification(stage, progress)
            }
            val srt = saveSidecars(displayName, result)
            val chapters = srt.parentFile?.let { File(it, "${stem(displayName)}.chapters.txt") }
                ?.takeIf { it.exists() }
            bus.done(srt.absolutePath, chapters?.absolutePath)
            showResultNotification("字幕已生成", srt.name)
        } finally {
            audio.delete()
        }
    }

    private fun runBurn(videoUri: Uri, server: String, displayName: String) {
        bus.running("准备上传…", null)
        updateNotification("准备上传…")
        val input = copyUriToCache(videoUri)
        val output = File(cacheDir, "burn_out_${System.currentTimeMillis()}.mp4")
        try {
            client.burn(server, input, output) { stage, progress ->
                bus.running(stage, progress)
                updateNotification(stage, progress)
            }
            val galleryUri = insertVideoToGallery(output, "${stem(displayName)}_subtitled.mp4")
            bus.doneVideo(galleryUri.toString())
            showResultNotification("烧字幕完成", "已保存到相册")
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun runBurnWithSrt(videoUri: Uri, server: String, displayName: String, srtPath: String?) {
        val srtFile = srtPath?.let { File(it) }
        if (srtFile == null || !srtFile.exists()) {
            bus.error("找不到字幕文件，无法烧录")
            return
        }
        bus.running("准备上传…", null)
        updateNotification("准备上传…")
        val input = copyUriToCache(videoUri)
        val output = File(cacheDir, "burn_out_${System.currentTimeMillis()}.mp4")
        try {
            client.burnWithSrt(server, input, srtFile, output) { stage, progress ->
                bus.running(stage, progress)
                updateNotification(stage, progress)
            }
            val galleryUri = insertVideoToGallery(output, "${stem(displayName)}_subtitled.mp4")
            bus.doneVideo(galleryUri.toString())
            showResultNotification("烧字幕完成", "已保存到相册")
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun saveSidecars(displayName: String, result: TranscribeClient.TranscribeResult): File {
        val dir = (getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: cacheDir).apply { mkdirs() }
        val stem = stem(displayName)

        val srt = File(dir, "$stem.srt").apply { writeText(result.srt) }
        result.vtt?.takeIf { it.isNotBlank() }?.let { File(dir, "$stem.vtt").writeText(it) }
        result.text?.takeIf { it.isNotBlank() }?.let { File(dir, "$stem.txt").writeText(it) }

        if (result.chapters.isNotEmpty()) {
            val text = result.chapters.joinToString("\n") { "${formatTs(it.timeSec)}  ${it.title}" }
            File(dir, "$stem.chapters.txt").writeText(text)
        }
        result.summary?.let { s ->
            val sb = StringBuilder()
            sb.append("【全课概要】\n").append(s.overview).append("\n\n【时间线】\n")
            s.sections.forEach { sb.append("${formatTs(it.startSec)} - ${formatTs(it.endSec)}  ${it.summary}\n") }
            File(dir, "$stem.summary.txt").writeText(sb.toString())
        }
        return srt
    }

    private fun stem(name: String): String = name.substringBeforeLast('.').ifBlank { name }

    private fun formatTs(sec: Double): String {
        val total = sec.toInt()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun copyUriToCache(uri: Uri): File {
        val file = File(cacheDir, "burn_in_${System.currentTimeMillis()}.mp4")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("无法读取所选视频")
        return file
    }

    private fun insertVideoToGallery(file: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: throw IllegalStateException("无法创建相册条目")

        contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun buildNotification(text: String, progress: Float? = null): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("字幕 / 节点处理中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
        if (progress != null) builder.setProgress(100, (progress * 100).toInt(), false)
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Float? = null) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun showResultNotification(title: String, text: String) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, notif)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "字幕 / 节点",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
