/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.example.videocompressor.domain.transcribe

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 电脑端转写/烧字幕服务（FastAPI，见 tools/pc-transcribe/server.py）的局域网客户端。
 *
 * 两个接口都返回 **NDJSON**（application/x-ndjson，一行一个 JSON）：
 *  - 进度行：{"stage":"…","progress":0.42}
 *  - 错误行：{"error":"…"}
 *  - 结束行：{"done":true, …}
 *
 * `/transcribe` 的结束行就是完整结果（srt/vtt/text/chapters/summary）；
 * `/burn` 的结束行给出 {"video_url":"/download/<token>"}，再 GET 该地址下载成品 mp4。
 *
 * 超时给得很长：两小时讲座在电脑端可能要转写几十分钟。
 */
@Singleton
class TranscribeClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.MINUTES)   // 上传大视频
        .readTimeout(120, TimeUnit.MINUTES)   // 等电脑端转写/烧录
        .callTimeout(0, TimeUnit.MILLISECONDS) // 不限制整体时长
        .build()

    private val gson = Gson()

    fun interface ProgressListener {
        fun onProgress(stage: String, progress: Float?)
    }

    data class Chapter(val timeSec: Double, val title: String)

    data class SummarySection(val startSec: Double, val endSec: Double, val summary: String)

    data class Summary(val overview: String, val sections: List<SummarySection>)

    data class TranscribeResult(
        val srt: String,
        val vtt: String?,
        val text: String?,
        val chapters: List<Chapter>,
        val summary: Summary?
    )

    fun normalize(server: String): String {
        var s = server.trim()
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            s = "http://$s"
        }
        return s.trimEnd('/')
    }

    fun transcribe(server: String, audio: File, onProgress: ProgressListener): TranscribeResult {
        val base = normalize(server)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val request = Request.Builder().url("$base/transcribe").post(body).build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("电脑服务返回错误：HTTP ${resp.code}")
            val source = resp.body?.source() ?: throw IOException("电脑服务无响应")
            var result: TranscribeResult? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val obj = parseLine(line) ?: continue
                when {
                    obj.has("error") -> throw IOException(obj.get("error").asString)
                    obj.has("done") -> result = parseTranscribeResult(obj)
                    obj.has("stage") -> onProgress.onProgress(
                        obj.get("stage").asString,
                        obj.takeIf { it.has("progress") }?.get("progress")?.asFloat
                    )
                }
            }
            return result ?: throw IOException("电脑服务未返回转写结果")
        }
    }

    /**
     * 上传整段视频，电脑端转写并把字幕硬烧进画面+重编码，下载成品到 [downloadTo]。
     * 转写阶段进度映射到 0~0.8，烧录阶段电脑端再上报到 1.0。
     */
    fun burn(server: String, video: File, downloadTo: File, onProgress: ProgressListener): File {
        val base = normalize(server)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", video.name, video.asRequestBody("video/mp4".toMediaType()))
            .build()
        return executeBurn(base, "$base/burn", body, downloadTo, onProgress)
    }

    /**
     * 上传整段视频 + 已逐句校对的 SRT，电脑端**直接硬烧+重编码**（不再转写），下载成品。
     * 配合 server.py 的 /burn_srt：用户在 App 里确认字幕无误后才导出，所见即所得。
     */
    fun burnWithSrt(
        server: String,
        video: File,
        srt: File,
        downloadTo: File,
        onProgress: ProgressListener
    ): File {
        val base = normalize(server)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", video.name, video.asRequestBody("video/mp4".toMediaType()))
            .addFormDataPart("srt", srt.name, srt.asRequestBody("application/x-subrip".toMediaType()))
            .build()
        return executeBurn(base, "$base/burn_srt", body, downloadTo, onProgress)
    }

    private fun executeBurn(
        base: String,
        url: String,
        body: MultipartBody,
        downloadTo: File,
        onProgress: ProgressListener
    ): File {
        val request = Request.Builder().url(url).post(body).build()
        var videoUrl: String? = null
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("电脑服务返回错误：HTTP ${resp.code}")
            val source = resp.body?.source() ?: throw IOException("电脑服务无响应")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val obj = parseLine(line) ?: continue
                when {
                    obj.has("error") -> throw IOException(obj.get("error").asString)
                    obj.has("done") -> videoUrl = obj.takeIf { it.has("video_url") }?.get("video_url")?.asString
                    obj.has("stage") -> onProgress.onProgress(
                        obj.get("stage").asString,
                        obj.takeIf { it.has("progress") }?.get("progress")?.asFloat
                    )
                }
            }
        }
        val finishedUrl = videoUrl ?: throw IOException("电脑服务未返回成品下载地址")
        onProgress.onProgress("下载成品中…", null)
        downloadFinished("$base$finishedUrl", downloadTo)
        return downloadTo
    }

    private fun downloadFinished(url: String, dest: File) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载成品失败：HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("成品为空")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    private fun parseLine(line: String): JsonObject? =
        runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()

    private fun parseTranscribeResult(obj: JsonObject): TranscribeResult {
        val chapters = obj.takeIf { it.has("chapters") }?.getAsJsonArray("chapters")?.map {
            val c = it.asJsonObject
            Chapter(c.get("time_sec").asDouble, c.get("title").asString)
        } ?: emptyList()

        val summary = obj.get("summary")?.takeIf { !it.isJsonNull }?.asJsonObject?.let { s ->
            val sections = s.getAsJsonArray("sections")?.map {
                val x = it.asJsonObject
                SummarySection(x.get("start_sec").asDouble, x.get("end_sec").asDouble, x.get("summary").asString)
            } ?: emptyList()
            Summary(s.get("overview").asString, sections)
        }

        fun str(key: String) = obj.get(key)?.takeIf { !it.isJsonNull }?.asString
        return TranscribeResult(
            srt = str("srt") ?: "",
            vtt = str("vtt"),
            text = str("text"),
            chapters = chapters,
            summary = summary
        )
    }
}
