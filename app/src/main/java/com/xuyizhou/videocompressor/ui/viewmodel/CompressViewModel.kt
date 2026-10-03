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
import com.xuyizhou.videocompressor.data.model.CompressConfig
import com.xuyizhou.videocompressor.data.model.DeviceProfile
import com.xuyizhou.videocompressor.data.model.VideoInfo
import com.xuyizhou.videocompressor.data.netdisk.NetdiskAuthStore
import com.xuyizhou.videocompressor.data.netdisk.NetdiskClient
import com.xuyizhou.videocompressor.data.repository.VideoRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CompressUiState(
            autoUpload = netdiskStore.autoUploadEnabled,
            netdiskName = if (netdiskStore.hasAuth()) netdiskStore.baiduName else null
        )
    )
    val uiState: StateFlow<CompressUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = profiler.profile
            _uiState.update { it.copy(deviceProfile = profile, thermalLabel = thermalGovernor.currentLevel().label) }
        }

        viewModelScope.launch {
            progressBus.events.collect { event ->
                when (event) {
                    is CompressProgressBus.Event.Progress -> _uiState.update {
                        it.copy(status = CompressStatus.Running(event.overall, event.index, event.count, event.name))
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
        val name: String
    ) : CompressStatus()

    data class Done(val results: List<BatchResult>) : CompressStatus()
    data class Error(val message: String) : CompressStatus()
}
