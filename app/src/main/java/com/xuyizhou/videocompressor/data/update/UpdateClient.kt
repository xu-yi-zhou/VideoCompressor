/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.data.update

import android.util.Log
import com.google.gson.Gson
import com.xuyizhou.videocompressor.BuildConfig
import com.xuyizhou.videocompressor.data.model.GithubAsset
import com.xuyizhou.videocompressor.data.model.GithubRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GitHub Releases 自更新客户端（公开仓库，无需鉴权）。
 *
 * 两个独立 OkHttp 实例：API 查询有读超时；APK 下载不设读超时
 * （大文件不能按固定时限判失败），卡死由协程取消兜底。
 */
@Singleton
class UpdateClient @Inject constructor() {

    private val gson = Gson()

    private companion object {
        const val TAG = "UpdateClient"
    }

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun apiUrl(): String =
        "https://api.github.com/repos/${BuildConfig.UPDATE_OWNER}/${BuildConfig.UPDATE_REPO}/releases/latest"

    /**
     * 拉取最新 release。
     * 远端尚无 release（404）返回 success(null)，由调用方按「已是最新」处理；
     * 网络 / 解析 / 限流失败返回 failure。
     */
    fun fetchLatestRelease(): Result<GithubRelease?> = runCatching {
        // GitHub API 对无 User-Agent 的请求返回 403，必须显式携带
        val request = Request.Builder()
            .url(apiUrl())
            .header("User-Agent", "VideoCompressor")
            .header("Accept", "application/vnd.github+json")
            .build()
        Log.d(TAG, "fetchLatestRelease url=${apiUrl()}")
        apiClient.newCall(request).execute().use { resp ->
            Log.d(TAG, "fetchLatestRelease resp=${resp.code}")
            when {
                resp.code == 404 -> null
                !resp.isSuccessful -> throw IllegalStateException("GitHub API HTTP ${resp.code}")
                else -> gson.fromJson(resp.body?.string(), GithubRelease::class.java)
            }
        }
    }

    /**
     * 下载 APK 到 [destFile]（先写 .part 临时文件，成功后改名，失败/取消删除）。
     * [onProgress] 回传 (bytesRead, total)，total 可能为 -1（无 Content-Length）。
     *
     * 协程取消不会中断 OkHttp 的阻塞读，故用 [runInterruptible] 让取消能中断线程；
     * 读循环内的 [ensureActive] 保证取消尽快生效。
     */
    suspend fun downloadApk(
        asset: GithubAsset,
        destFile: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result<File> {
        // runInterruptible 的 lambda 无 CoroutineScope receiver，先捕获协程上下文供循环内检查取消
        val ctx = kotlin.coroutines.coroutineContext
        return try {
            Result.success(runInterruptible(Dispatchers.IO) {
                val part = File(destFile.parentFile, destFile.name + ".part")
                try {
                    val request = Request.Builder()
                        .url(asset.browserDownloadUrl)
                        .header("User-Agent", "VideoCompressor")
                        .build()
                    Log.d(TAG, "downloadApk start url=${asset.browserDownloadUrl}")
                    downloadClient.newCall(request).execute().use { resp ->
                        Log.d(TAG, "downloadApk resp=${resp.code} len=${resp.body?.contentLength()}")
                        if (!resp.isSuccessful) throw IllegalStateException("下载失败 HTTP ${resp.code}")
                        val body = resp.body ?: throw IllegalStateException("下载失败：空响应体")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            part.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var read = 0L
                                while (true) {
                                    ctx.ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    read += n
                                    if (read == n.toLong()) Log.d(TAG, "downloadApk 首块到达 ${n}B")
                                    onProgress(read, total)
                                }
                            }
                        }
                    }
                    if (!part.renameTo(destFile)) throw IllegalStateException("临时文件移动失败")
                    destFile
                } catch (e: Throwable) {
                    part.delete()
                    throw e
                }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}
