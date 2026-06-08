package com.example.videocompressor.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.videocompressor.data.SettingsStore
import com.example.videocompressor.data.model.CompressConfig
import com.example.videocompressor.data.model.DeviceProfile
import com.example.videocompressor.data.model.VideoInfo
import com.example.videocompressor.data.repository.VideoRepository
import com.example.videocompressor.domain.compressor.DeviceCodecProfiler
import com.example.videocompressor.domain.compressor.ThermalGovernor
import com.example.videocompressor.domain.usecase.CompressVideoUseCase
import com.example.videocompressor.service.CompressProgressBus
import com.example.videocompressor.service.CompressService
import com.example.videocompressor.service.TranscribeBus
import com.example.videocompressor.service.TranscribeService
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
    private val transcribeBus: TranscribeBus,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CompressUiState(serverUrl = settingsStore.serverUrl))
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

        // 监听字幕/转录进度总线
        viewModelScope.launch {
            transcribeBus.events.collect { event ->
                val status = when (event) {
                    TranscribeBus.Event.Idle -> TranscribeStatus.Idle
                    is TranscribeBus.Event.Running -> TranscribeStatus.Running(event.stage, event.progress)
                    is TranscribeBus.Event.Done -> TranscribeStatus.Done(event.srtPath, event.chaptersPath)
                    is TranscribeBus.Event.DoneVideo -> TranscribeStatus.DoneVideo(event.videoUri)
                    is TranscribeBus.Event.Error -> TranscribeStatus.Error(event.message)
                }
                _uiState.update { it.copy(transcribeStatus = status) }
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
        // 保留已探测的设备画像、电脑服务地址，避免重置后相关卡片/输入消失
        _uiState.update {
            CompressUiState(
                deviceProfile = it.deviceProfile,
                thermalLabel = thermalGovernor.currentLevel().label,
                serverUrl = it.serverUrl
            )
        }
    }

    // ── 字幕 / 节点（电脑端转录）────────────────────────────

    fun updateServerUrl(url: String) {
        settingsStore.serverUrl = url
        _uiState.update { it.copy(serverUrl = url) }
    }

    /** 软字幕(SRT)：抽音频上传电脑端转写，写旁挂文件。 */
    fun startTranscribe() = startTranscribeService(TranscribeService.MODE_TRANSCRIBE)

    /** 烧进视频：上传整段视频，电脑端烧字幕+重编码后保存到相册。 */
    fun startBurn() = startTranscribeService(TranscribeService.MODE_BURN)

    private fun startTranscribeService(mode: String) {
        val videoInfo = _uiState.value.videoInfo ?: return
        val server = _uiState.value.serverUrl.trim()
        if (server.isBlank()) {
            _uiState.update { it.copy(transcribeStatus = TranscribeStatus.Error("请先填写电脑服务地址")) }
            return
        }

        transcribeBus.reset()
        _uiState.update { it.copy(transcribeStatus = TranscribeStatus.Running("准备中…", null)) }

        val intent = Intent(context, TranscribeService::class.java).apply {
            putExtra(TranscribeService.EXTRA_VIDEO_URI, videoInfo.uri)
            putExtra(TranscribeService.EXTRA_SERVER_URL, server)
            putExtra(TranscribeService.EXTRA_DISPLAY_NAME, videoInfo.name)
            putExtra(TranscribeService.EXTRA_MODE, mode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.d("CompressVM", "TranscribeService 已启动: mode=$mode")
    }

    fun resetTranscribe() {
        transcribeBus.reset()
        _uiState.update { it.copy(transcribeStatus = TranscribeStatus.Idle) }
    }
}

data class CompressUiState(
    val videoInfo: VideoInfo? = null,
    val config: CompressConfig = CompressConfig(),
    val status: CompressStatus = CompressStatus.Idle,
    val deviceProfile: DeviceProfile? = null,
    val thermalLabel: String = "未知",
    val serverUrl: String = "",
    val transcribeStatus: TranscribeStatus = TranscribeStatus.Idle
)

sealed class CompressStatus {
    data object Idle : CompressStatus()
    data class Running(val progress: Float) : CompressStatus()
    data class Done(val outputPath: String) : CompressStatus()
    data class Error(val message: String) : CompressStatus()
}

sealed class TranscribeStatus {
    data object Idle : TranscribeStatus()
    /** progress 为 null 表示不确定进度。 */
    data class Running(val stage: String, val progress: Float?) : TranscribeStatus()
    data class Done(val srtPath: String, val chaptersPath: String?) : TranscribeStatus()
    data class DoneVideo(val videoUri: String) : TranscribeStatus()
    data class Error(val message: String) : TranscribeStatus()
}
