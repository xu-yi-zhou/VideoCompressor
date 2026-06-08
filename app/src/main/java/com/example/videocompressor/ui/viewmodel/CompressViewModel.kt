package com.example.videocompressor.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.videocompressor.data.model.CompressConfig
import com.example.videocompressor.data.model.DeviceProfile
import com.example.videocompressor.data.model.VideoInfo
import com.example.videocompressor.data.repository.VideoRepository
import com.example.videocompressor.domain.compressor.DeviceCodecProfiler
import com.example.videocompressor.domain.compressor.ThermalGovernor
import com.example.videocompressor.domain.usecase.CompressVideoUseCase
import com.example.videocompressor.service.CompressProgressBus
import com.example.videocompressor.service.CompressService
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
        // 启动即在后台完成本机编码能力探测，供首页展示
        viewModelScope.launch(Dispatchers.IO) {
            val profile = profiler.profile
            _uiState.update { it.copy(deviceProfile = profile, thermalLabel = thermalGovernor.currentLevel().label) }
        }

        // 监听压缩进度/结果总线（替代广播）
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

    /** 供进度页轮询实时温度状态，体现热节流自适应。 */
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
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
        // 保留已探测的设备画像，避免重置后首页相关卡片消失
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
