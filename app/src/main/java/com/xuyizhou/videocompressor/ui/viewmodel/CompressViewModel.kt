/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xuyizhou.videocompressor.BuildConfig
import com.xuyizhou.videocompressor.data.model.CompressConfig
import com.xuyizhou.videocompressor.data.model.DeviceProfile
import com.xuyizhou.videocompressor.data.model.GithubRelease
import com.xuyizhou.videocompressor.data.model.VideoInfo
import com.xuyizhou.videocompressor.data.netdisk.NetdiskAuthStore
import com.xuyizhou.videocompressor.data.netdisk.NetdiskClient
import com.xuyizhou.videocompressor.data.repository.VideoRepository
import com.xuyizhou.videocompressor.data.update.UpdateClient
import com.xuyizhou.videocompressor.data.update.VersionComparator
import com.xuyizhou.videocompressor.domain.compressor.DeviceCodecProfiler
import com.xuyizhou.videocompressor.domain.compressor.ThermalGovernor
import com.xuyizhou.videocompressor.domain.usecase.CompressVideoUseCase
import com.xuyizhou.videocompressor.service.BatchResult
import com.xuyizhou.videocompressor.service.CompressProgressBus
import com.xuyizhou.videocompressor.service.CompressService
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 应用核心 ViewModel，作用域为 Activity，生命周期跨越所有导航页面。
 *
 * 持有并暴露 [uiState]（[kotlinx.coroutines.flow.StateFlow]），驱动全部 UI 状态：
 * - 待压缩视频列表（[CompressUiState.videos]），支持多选批量压缩
 * - 压缩状态（[CompressStatus]）：Idle / Running / Done / Error
 * - 设备编码能力画像（[com.xuyizhou.videocompressor.data.model.DeviceProfile]）
 *
 * 与后台服务的通信通过进程内总线实现：
 * 压缩进度/结果经 [com.xuyizhou.videocompressor.service.CompressProgressBus] 传入。
 */
@HiltViewModel
class CompressViewModel @Inject constructor(
    private val compressUseCase: CompressVideoUseCase,
    private val repository: VideoRepository,
    private val profiler: DeviceCodecProfiler,
    private val thermalGovernor: ThermalGovernor,
    private val progressBus: CompressProgressBus,
    private val netdiskStore: NetdiskAuthStore,
    private val netdiskClient: NetdiskClient,
    private val updateClient: UpdateClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CompressUiState(
            autoUpload = netdiskStore.autoUploadEnabled,
            netdiskName = if (netdiskStore.hasAuth()) netdiskStore.baiduName else null
        )
    )
    val uiState: StateFlow<CompressUiState> = _uiState.asStateFlow()

    /** 自更新状态（独立于压缩状态），启动时自动检查一次 */
    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = profiler.profile
            _uiState.update { it.copy(deviceProfile = profile, thermalLabel = thermalGovernor.currentLevel().label) }
        }

        // 启动进首页时静默检查一次更新（VM 作用域 Activity，旋转/回首页不重复检查）
        checkForUpdates()

        viewModelScope.launch {
            progressBus.events.collect { event ->
                when (event) {
                    is CompressProgressBus.Event.Progress -> _uiState.update {
                        it.copy(
                            status = CompressStatus.Running(
                                event.overall,
                                event.index,
                                event.count,
                                event.name,
                                event.uploading,
                                event.uploadProgress
                            )
                        )
                    }

                    is CompressProgressBus.Event.Done -> _uiState.update { state ->
                        state.copy(status = CompressStatus.Done(event.results.map { r ->
                            r.copy(inputSize = state.videos.getOrNull(r.index)?.size ?: 0L)
                        }))
                    }

                    is CompressProgressBus.Event.Error -> onCompressError(event.message)
                    CompressProgressBus.Event.Idle -> {}
                }
            }
        }
    }

    fun refreshThermal() {
        _uiState.update { it.copy(thermalLabel = thermalGovernor.currentLevel().label) }
    }

    fun onVideosSelected(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val infos = uris.map { repository.getVideoInfo(it) }
            _uiState.update { it.copy(videos = infos) }
        }
    }

    fun removeVideo(index: Int) {
        _uiState.update { it.copy(videos = it.videos.filterIndexed { i, _ -> i != index }) }
    }

    fun updateConfig(config: CompressConfig) {
        _uiState.update { it.copy(config = config) }
    }

    fun startCompress() {
        val videos = _uiState.value.videos
        if (videos.isEmpty()) return
        val config = _uiState.value.config

        Log.d("CompressVM", "startCompress via CompressService: count=${videos.size}")
        progressBus.reset()
        _uiState.update {
            it.copy(status = CompressStatus.Running(0f, 0, videos.size, videos.first().name))
        }

        val configJson = Gson().toJson(config)
        val intent = Intent(context, CompressService::class.java).apply {
            putStringArrayListExtra("video_uris", ArrayList(videos.map { it.uri.toString() }))
            putStringArrayListExtra("video_names", ArrayList(videos.map { it.name }))
            putExtra("config", configJson)
            putExtra("auto_upload", _uiState.value.autoUpload)
        }

        context.startForegroundService(intent)
        Log.d("CompressVM", "CompressService 已启动")
    }

    // ── 百度网盘 ──────────────────────────────────────────

    fun setAutoUpload(enabled: Boolean) {
        netdiskStore.autoUploadEnabled = enabled
        _uiState.update { it.copy(autoUpload = enabled) }
    }

    /** 授权页完成/解除授权后同步网盘状态到 UI。 */
    fun refreshNetdiskAuth() {
        _uiState.update {
            it.copy(netdiskName = if (netdiskStore.hasAuth()) netdiskStore.baiduName else null)
        }
    }

    /** 授权页回调：授权码换 token（IO），成功后拉取昵称并刷新状态。 */
    suspend fun completeAuth(code: String): Boolean {
        val ok = withContext(Dispatchers.IO) { netdiskClient.exchangeCode(code) }
        if (!ok) return false
        withContext(Dispatchers.IO) { netdiskClient.getUserInfo() }
        refreshNetdiskAuth()
        return true
    }

    fun clearNetdiskAuth() {
        netdiskStore.clear()
        _uiState.update { it.copy(netdiskName = null, autoUpload = false) }
    }

    // ── GitHub Releases 自更新 ─────────────────────────────

    /** 查询最新 release；发现新版弹窗，已最新/失败只反映在卡片状态行（自动与手动共用）。 */
    fun checkForUpdates() {
        val state = _updateState.value
        if (state is UpdateUiState.Checking || state is UpdateUiState.Downloading) return
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            updateClient.fetchLatestRelease().fold(
                onSuccess = { release ->
                    val newer = release?.takeIf {
                        VersionComparator.isNewer(BuildConfig.VERSION_NAME, it.tagName)
                    }
                    _updateState.value = if (newer != null) UpdateUiState.Available(newer)
                    else UpdateUiState.UpToDate
                },
                onFailure = { e ->
                    _updateState.value = UpdateUiState.CheckFailed(e.message ?: "网络异常")
                }
            )
        }
    }

    /** 下载 APK（先清空 cacheDir/update/ 旧文件）；进度回调线程安全地写状态。 */
    fun downloadUpdate(release: GithubRelease) {
        if (_updateState.value is UpdateUiState.Downloading) return
        val asset = release.assets.firstOrNull { it.name.endsWith(".apk") }
        if (asset == null) {
            _updateState.value = UpdateUiState.DownloadFailed("发布包中未找到 APK 附件", release)
            return
        }
        val dir = File(context.cacheDir, "update")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, apkFileName(release.tagName))
        _updateState.value = UpdateUiState.Downloading(0f, 0L, -1L, release)
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val scope = this // Result.fold 非 inline，先捕获 CoroutineScope 供 onFailure 里判断取消
            updateClient.downloadApk(asset, dest) { read, total ->
                val progress = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
                _updateState.value = UpdateUiState.Downloading(progress, read, total, release)
            }.fold(
                onSuccess = { file ->
                    _updateState.value = UpdateUiState.Downloaded(file, release)
                },
                onFailure = { e ->
                    // 取消下载时协程已不活跃，此时不写 DownloadFailed（由 cancelDownload 接管状态）
                    if (scope.isActive) {
                        _updateState.value =
                            UpdateUiState.DownloadFailed(e.message ?: "下载失败", release)
                    }
                }
            )
        }
    }

    /** 取消下载：回到 Available，用户可重新选择下载或暂不。 */
    fun cancelDownload() {
        val state = _updateState.value as? UpdateUiState.Downloading ?: return
        downloadJob?.cancel()
        downloadJob = null
        _updateState.value = UpdateUiState.Available(state.release)
    }

    /** 系统安装器已被拉起，标记防重复触发。 */
    fun markInstallLaunched() {
        val state = _updateState.value
        if (state is UpdateUiState.Downloaded) {
            _updateState.value = state.copy(installLaunched = true)
        }
    }

    /** 「暂不」/「关闭」：弹窗消失回 Idle。 */
    fun dismissUpdate() {
        if (_updateState.value is UpdateUiState.Downloading) return
        _updateState.value = UpdateUiState.Idle
    }

    private fun apkFileName(tag: String): String =
        "update_${tag.replace(Regex("[^0-9A-Za-z._-]"), "_")}.apk"

    fun onCompressError(message: String) {
        Log.e("CompressVM", "压缩失败: $message")
        _uiState.update { it.copy(status = CompressStatus.Error(message)) }
    }

    fun reset() {
        progressBus.reset()
        _uiState.update {
            CompressUiState(
                deviceProfile = it.deviceProfile,
                thermalLabel = thermalGovernor.currentLevel().label,
                autoUpload = it.autoUpload,
                netdiskName = it.netdiskName
            )
        }
    }
}

data class CompressUiState(
    val videos: List<VideoInfo> = emptyList(),
    val config: CompressConfig = CompressConfig(),
    val status: CompressStatus = CompressStatus.Idle,
    val deviceProfile: DeviceProfile? = null,
    val thermalLabel: String = "未知",
    val autoUpload: Boolean = false,
    val netdiskName: String? = null
)

sealed class CompressStatus {
    data object Idle : CompressStatus()
    data class Running(
        val overall: Float,
        val index: Int,
        val count: Int,
        val name: String,
        /** 是否处于网盘上传阶段 */
        val uploading: Boolean = false,
        /** 当前视频的上传进度 0~1 */
        val uploadProgress: Float = 0f
    ) : CompressStatus()

    data class Done(val results: List<BatchResult>) : CompressStatus()
    data class Error(val message: String) : CompressStatus()
}
