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
import com.xuyizhou.videocompressor.data.repository.VideoRepository
import com.xuyizhou.videocompressor.domain.compressor.DeviceCodecProfiler
import com.xuyizhou.videocompressor.domain.compressor.ThermalGovernor
import com.xuyizhou.videocompressor.domain.usecase.CompressVideoUseCase
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
import javax.inject.Inject

/**
 * 应用核心 ViewModel，作用域为 Activity，生命周期跨越所有导航页面。
 *
 * 持有并暴露 [uiState]（[kotlinx.coroutines.flow.StateFlow]），驱动全部 UI 状态：
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CompressUiState())
    val uiState: StateFlow<CompressUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = profiler.profile
            _uiState.update { it.copy(deviceProfile = profile, thermalLabel = thermalGovernor.currentLevel().label) }
        }

        viewModelScope.launch {
            progressBus.events.collect { event ->
                when (event) {
                    is CompressProgressBus.Event.Progress -> onProgressUpdate(event.value)
                    is CompressProgressBus.Event.Complete -> onCompressComplete(event.outputPath)
                    is CompressProgressBus.Event.Error -> onCompressError(event.message)
                    CompressProgressBus.Event.Idle -> {}
                }
            }
        }
    }

    fun refreshThermal() {
        _uiState.update { it.copy(thermalLabel = thermalGovernor.currentLevel().label) }
    }

    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val info = repository.getVideoInfo(uri)
            _uiState.update { it.copy(videoInfo = info) }
        }
    }

    fun updateConfig(config: CompressConfig) {
        _uiState.update { it.copy(config = config) }
    }

    fun startCompress() {
        val videoInfo = _uiState.value.videoInfo ?: return
        val config = _uiState.value.config

        Log.d("CompressVM", "startCompress via CompressService: uri=${videoInfo.uri}, name=${videoInfo.name}")
        progressBus.reset()
        _uiState.update { it.copy(status = CompressStatus.Running(0f)) }

        val configJson = Gson().toJson(config)
        val intent = Intent(context, CompressService::class.java).apply {
            putExtra("video_uri", videoInfo.uri)
            putExtra("config", configJson)
        }

        context.startForegroundService(intent)
        Log.d("CompressVM", "CompressService 已启动")
    }

    fun onProgressUpdate(progress: Float) {
        _uiState.update { it.copy(status = CompressStatus.Running(progress)) }
    }

    fun onCompressComplete(path: String) {
        Log.d("CompressVM", "压缩完成: $path")
        _uiState.update { it.copy(status = CompressStatus.Done(path)) }
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
                thermalLabel = thermalGovernor.currentLevel().label
            )
        }
    }
}

data class CompressUiState(
    val videoInfo: VideoInfo? = null,
    val config: CompressConfig = CompressConfig(),
    val status: CompressStatus = CompressStatus.Idle,
    val deviceProfile: DeviceProfile? = null,
    val thermalLabel: String = "未知"
)

sealed class CompressStatus {
    data object Idle : CompressStatus()
    data class Running(val progress: Float) : CompressStatus()
    data class Done(val outputPath: String) : CompressStatus()
    data class Error(val message: String) : CompressStatus()
}
