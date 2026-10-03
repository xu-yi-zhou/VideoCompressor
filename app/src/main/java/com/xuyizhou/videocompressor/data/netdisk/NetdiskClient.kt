/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.data.netdisk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 百度网盘官方 PCS(xpan)API 客户端。
 *
 * 职责：OAuth 令牌换取/刷新、用户信息、远端目录创建、单文件上传。
 * 上传走应用专属目录 `/apps/<应用名>/视频压缩/`（第三方应用默认只能写该目录）。
 * 上传超时放宽：压缩成品可能上 GB，写超时 2 小时。
 */
@Singleton
class NetdiskClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: NetdiskAuthStore
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.HOURS)
        .readTimeout(1, TimeUnit.MINUTES)
        .build()

    private val tokenUrl = "https://openapi.baidu.com/oauth/2.0/token".toHttpUrl()
    private val panBase = "https://pan.baidu.com/rest/2.0/xpan".toHttpUrl()

    // ── OAuth ────────────────────────────────────────────

    /** 用授权码换取 access/refresh token 并持久化。 */
    fun exchangeCode(code: String): Boolean {
        if (!NetdiskConfig.configured) return false
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", NetdiskConfig.APP_KEY)
            .add("client_secret", NetdiskConfig.APP_SECRET)
            .add("redirect_uri", "oob")
            .build()
        val response = post(tokenUrl, body) ?: return false
        return parseAndSaveTokens(response)
    }

    /** access_token 不足 5 分钟到期时自动用 refresh_token 续期。 */
    fun ensureFreshToken(): Boolean {
        if (!store.hasAuth()) return false
        if (System.currentTimeMillis() < store.expiresAt - 5 * 60_000L) return true
        return refreshAccessToken()
    }

    private fun refreshAccessToken(): Boolean {
        if (!NetdiskConfig.configured) return false
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", store.refreshToken)
            .add("client_id", NetdiskConfig.APP_KEY)
            .add("client_secret", NetdiskConfig.APP_SECRET)
            .build()
        val response = post(tokenUrl, body) ?: return false
        val ok = parseAndSaveTokens(response)
        if (!ok) store.clear()
        return ok
    }

    private fun parseAndSaveTokens(response: Response): Boolean {
        return response.use { resp ->
            val obj = runCatching { JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject }
                .getOrNull() ?: return@use false
            if (obj.has("error")) return@use false
            val access = obj.get("access_token")?.asString ?: return@use false
            val refresh = obj.get("refresh_token")?.asString ?: store.refreshToken
            val expiresIn = obj.get("expires_in")?.asLong ?: 0L
            store.saveAuth(access, refresh, expiresIn)
            true
        }
    }

    // ── 用户信息 / 远端目录 ──────────────────────────────

    /** 拉取网盘昵称（授权成功后调用），返回昵称并写入 [NetdiskAuthStore]。 */
    fun getUserInfo(): String? {
        if (!ensureFreshToken()) return null
        val url = panBase.newBuilder()
            .addPathSegment("nas")
            .addQueryParameter("method", "uinfo")
            .addQueryParameter("access_token", store.accessToken)
            .build()
        val request = Request.Builder().url(url).build()
        val name = http.newCall(request).execute().use { resp ->
            val obj = runCatching { JsonParser.parseString(resp.body?.string().orEmpty()).asJsonObject }
                .getOrNull() ?: return null
            obj.get("baidu_name")?.asString
        }
        store.baiduName = name
        return name
    }

    /** 确保远端目录存在（errno 0=新建成功，-8=已存在，均视为成功）。 */
    fun ensureRemoteDir(): Boolean {
        if (!ensureFreshToken()) {
            android.util.Log.w(TAG, "ensureRemoteDir: token 无效或刷新失败")
            return false
        }
        // create 接口要求 path/isdir/size 放在 form body 而非 URL query
        val url = panBase.newBuilder()
            .addPathSegment("file")
            .addQueryParameter("method", "create")
            .addQueryParameter("access_token", store.accessToken)
            .build()
        val body = FormBody.Builder()
            .add("path", remoteDir())
            .add("isdir", "1")
            .add("size", "0")
            .build()
        val request = Request.Builder().url(url).post(body).build()
        val errno = http.newCall(request).execute().use { parseErrno(it.body?.string().orEmpty()) }
        android.util.Log.d(TAG, "ensureRemoteDir(${remoteDir()}): errno=$errno")
        if (errno != 0 && errno != -8) logAppDirListing()
        return errno == 0 || errno == -8
    }

    /** 诊断用：列出 /apps/ 下的实际应用文件夹名（排查 APP_NAME 与开放平台应用名不一致）。 */
    private fun logAppDirListing() {
        val url = panBase.newBuilder()
            .addPathSegment("file")
            .addQueryParameter("method", "list")
            .addQueryParameter("access_token", store.accessToken)
            .addQueryParameter("dir", "/apps/")
            .build()
        val request = Request.Builder().url(url).build()
        val body = http.newCall(request).execute().use { it.body?.string().orEmpty() }
        android.util.Log.d(TAG, "/apps/ listing: ${body.take(500)}")
    }

    // ── 上传 ─────────────────────────────────────────────

    /**
     * 把 MediaStore content [uri] 流式上传到网盘应用目录，成功返回远端路径。
     * errno 111/-6（token 失效）时自动刷新令牌重试一次。[onProgress] 回传 0~1 上传进度。
     */
    /**
     * 上传压缩成品到网盘，走 xpan 官方流程（已对真实接口逐段验证）：
     * 1. precreate（必填 autoinit=1，rtype=3 覆盖同名）→ 返回 uploadid 与需要上传的分片序号
     * 2. locateupload 获取动态上传域名 → 按分片 multipart 上传到 pcs/superfile2（type=tmpfile）
     * 3. create 提交（block_list 使用各分片上传返回的云端 MD5）
     * 普通用户单分片 4MB、分片数 ≤1024；文件超过 4GB 直接报错。
     */
    fun uploadFromUri(uri: Uri, remoteName: String, onProgress: (Float) -> Unit = {}): Result<String> {
        if (!NetdiskConfig.configured) return Result.failure(IllegalStateException("网盘应用未配置"))
        if (!ensureFreshToken()) return Result.failure(IllegalStateException("网盘未授权"))

        val size = querySize(uri)
        if (size > MAX_FILE_SIZE) return Result.failure(IllegalStateException("文件超过 4GB，暂不支持"))
        val path = "${remoteDir()}/$remoteName"

        // 本地按 4MB 分片计算 MD5 列表（小于 4MB 即整体一个分片）
        val localMd5s = computeBlockMd5s(uri, size) ?: return Result.failure(IllegalStateException("无法读取压缩成品"))
        android.util.Log.d(TAG, "upload path=$path size=$size blocks=${localMd5s.size}")

        // 1. precreate
        var pre = precreate(path, size, localMd5s)
        if ((pre.errno == 111 || pre.errno == -6) && refreshAccessToken()) {
            pre = precreate(path, size, localMd5s)
        }
        if (pre.errno != 0) return Result.failure(IllegalStateException("预创建失败（errno=${pre.errno}）"))
        val uploadId = pre.uploadId
        if (uploadId.isEmpty()) {
            // return_type=2 为秒传命中（文件已存在），否则是异常响应
            return if (pre.returnType == 2) Result.success(path)
            else Result.failure(IllegalStateException("预创建未返回 uploadid（return_type=${pre.returnType}）"))
        }
        // block_list 为需要上传的分片序号（空等价于全部即 [0]）
        val needUpload = if (pre.needUpload.isEmpty()) (localMd5s.indices).toList() else pre.needUpload
        if (needUpload.any { it !in localMd5s.indices }) {
            return Result.failure(IllegalStateException("预创建返回非法分片序号 $needUpload"))
        }

        // 2. 分片上传（multipart，云端返回各分片 MD5）
        val cloudMd5s = localMd5s.toMutableList()
        val input = context.contentResolver.openInputStream(uri)
            ?: return Result.failure(IllegalStateException("无法读取压缩成品"))
        try {
            for (partIndex in needUpload) {
                val offset = partIndex * CHUNK_SIZE
                val chunkSize = minOf(CHUNK_SIZE, size - offset)
                val chunk = ByteArray(chunkSize.toInt())
                var read = 0
                while (read < chunk.size) {
                    val n = input.read(chunk, read, chunk.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read < chunk.size) return Result.failure(IllegalStateException("读取压缩成品失败"))

                val upResult = uploadChunk(path, uploadId, partIndex, chunk)
                if (upResult.isFailure) return upResult
                cloudMd5s[partIndex] = upResult.getOrThrow()
                onProgress(((offset + chunkSize).toFloat() / size).coerceIn(0f, 1f))
            }
        } finally {
            runCatching { input.close() }
        }

        // 3. create 提交（block_list 用云端 MD5 按分片序号排列）
        val commitErrno = commitFile(path, size, cloudMd5s, uploadId)
        if (commitErrno != 0) return Result.failure(IllegalStateException("提交文件失败（errno=$commitErrno）"))

        return Result.success(path)
    }

    /** @return (errno, uploadid, return_type, 需要上传的分片序号列表) */
    private fun precreate(path: String, size: Long, blockMd5s: List<String>): PrecreateResult {
        val url = panBase.newBuilder()
            .addPathSegment("file")
            .addQueryParameter("method", "precreate")
            .addQueryParameter("access_token", store.accessToken)
            .build()
        val blockListJson = "[" + blockMd5s.joinToString(",") { "\"$it\"" } + "]"
        val body = FormBody.Builder()
            .add("path", path)
            .add("size", size.toString())
            .add("isdir", "0")
            .add("rtype", "3")
            .add("autoinit", "1")
            .add("block_list", blockListJson)
            .build()
        val request = Request.Builder().url(url).post(body).build()
        val respBody = http.newCall(request).execute().use { it.body?.string().orEmpty() }
        android.util.Log.d(TAG, "precreate resp: ${respBody.take(400)}")
        val obj = runCatching { JsonParser.parseString(respBody).asJsonObject }.getOrNull()
        val uploadId = obj?.get("uploadid")?.asString ?: ""
        val returnType = obj?.get("return_type")?.asInt ?: -1
        val needUpload = obj?.getAsJsonArray("block_list")
            ?.mapNotNull { runCatching { it.asInt }.getOrNull() } ?: emptyList()
        return PrecreateResult(parseErrno(respBody), uploadId, returnType, needUpload)
    }

    private data class PrecreateResult(
        val errno: Int,
        val uploadId: String,
        val returnType: Int,
        val needUpload: List<Int>
    )

    /** 上传单个分片（multipart file 字段），成功返回云端 MD5。 */
    private fun uploadChunk(path: String, uploadId: String, partseq: Int, chunk: ByteArray): Result<String> {
        val host = locateUploadHost()
        val url = "https://$host/rest/2.0/pcs/superfile2".toHttpUrl().newBuilder()
            .addQueryParameter("method", "upload")
            .addQueryParameter("type", "tmpfile")
            .addQueryParameter("access_token", store.accessToken)
            .addQueryParameter("path", path)
            .addQueryParameter("uploadid", uploadId)
            .addQueryParameter("partseq", partseq.toString())
            .build()
        val chunkBody = chunk.toRequestBody("application/octet-stream".toMediaType())
        val multipart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", "part$partseq", chunkBody)
            .build()
        val request = Request.Builder().url(url).post(multipart).build()
        val respBody = http.newCall(request).execute().use { it.body?.string().orEmpty() }
        android.util.Log.d(TAG, "uploadChunk[$partseq] resp: ${respBody.take(200)}")
        val obj = runCatching { JsonParser.parseString(respBody).asJsonObject }.getOrNull()
        val md5 = obj?.get("md5")?.asString
        if (md5.isNullOrBlank()) {
            return Result.failure(IllegalStateException("分片上传失败（$respBody）"))
        }
        return Result.success(md5)
    }

    /** 获取动态上传域名（xpan 要求不得在生产代码固定域名），失败回退 d.pcs.baidu.com。 */
    private fun locateUploadHost(): String {
        val url = panBase.newBuilder()
            .addPathSegment("file")
            .addQueryParameter("method", "locateupload")
            .addQueryParameter("access_token", store.accessToken)
            .build()
        val request = Request.Builder().url(url).build()
        val respBody = runCatching { http.newCall(request).execute().use { it.body?.string().orEmpty() } }
            .getOrDefault("")
        val host = runCatching {
            JsonParser.parseString(respBody).asJsonObject.get("host")?.asString
        }.getOrNull()
        android.util.Log.d(TAG, "locateupload host=$host")
        return host?.takeIf { it.isNotBlank() } ?: "d.pcs.baidu.com"
    }

    private fun commitFile(path: String, size: Long, blockMd5s: List<String>, uploadId: String): Int {
        val url = panBase.newBuilder()
            .addPathSegment("file")
            .addQueryParameter("method", "create")
            .addQueryParameter("access_token", store.accessToken)
            .build()
        val blockListJson = "[" + blockMd5s.joinToString(",") { "\"$it\"" } + "]"
        val body = FormBody.Builder()
            .add("path", path)
            .add("size", size.toString())
            .add("isdir", "0")
            .add("uploadid", uploadId)
            .add("block_list", blockListJson)
            .add("rtype", "3")
            .build()
        val request = Request.Builder().url(url).post(body).build()
        val respBody = http.newCall(request).execute().use { it.body?.string().orEmpty() }
        android.util.Log.d(TAG, "commitFile resp: ${respBody.take(300)}")
        return parseErrno(respBody)
    }

    /** 按 4MB 分片计算 MD5 列表（流式，不回读内存）。 */
    private fun computeBlockMd5s(uri: Uri, size: Long): List<String>? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return try {
            val md5s = mutableListOf<String>()
            val buffer = ByteArray(64 * 1024)
            val digest = java.security.MessageDigest.getInstance("MD5")
            var chunkBytes = 0L
            var remaining = size
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) break
                digest.update(buffer, 0, n)
                chunkBytes += n
                remaining -= n
                if (chunkBytes >= CHUNK_SIZE || remaining == 0L) {
                    md5s.add(digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
                    digest.reset()
                    chunkBytes = 0L
                }
            }
            md5s
        } catch (e: Exception) {
            null
        } finally {
            runCatching { input.close() }
        }
    }

    private companion object {
        const val TAG = "NetdiskClient"
        const val CHUNK_SIZE = 4L * 1024 * 1024 // 普通用户单分片固定 4MB
        const val MAX_FILE_SIZE = CHUNK_SIZE * 1024 // 分片数上限 1024
    }

    // ── 工具 ─────────────────────────────────────────────

    private fun remoteDir() = "/apps/${NetdiskConfig.APP_NAME}/${NetdiskConfig.REMOTE_DIR}"

    private fun post(url: okhttp3.HttpUrl, body: FormBody): Response? =
        runCatching { http.newCall(Request.Builder().url(url).post(body).build()).execute() }
            .getOrNull()

    private fun parseErrno(body: String): Int {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return -999
        val errno = obj.get("errno")?.asInt ?: -999
        if (errno != 0) android.util.Log.w(TAG, "xpan errno=$errno body=${body.take(300)}")
        return errno
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) return cursor.getLong(idx)
            }
        }
        return -1L
    }
}
