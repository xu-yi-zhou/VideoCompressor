/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
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
import com.example.videocompressor.domain.transcribe.SrtParser
import com.example.videocompressor.domain.transcribe.SubtitleCue
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 应用核心 ViewModel，作用域为 Activity，生命周期跨越所有导航页面。
 *
 * 持有并暴露 [uiState]（[kotlinx.coroutines.flow.StateFlow]），驱动全部 UI 状态：
 * - 压缩状态（[CompressStatus]）：Idle / Running / Done / Error
 * - 字幕转录状态（[TranscribeStatus]）：Idle / Running / Done / DoneVideo / Error
 * - 设备编码能力画像（[com.example.videocompressor.data.model.DeviceProfile]）
 * - 字幕逐句编辑状态（[SubtitleEditState]）
 *
 * 与后台服务的通信通过进程内总线实现：
 * - 压缩进度/结果经 [com.example.videocompressor.service.CompressProgressBus] 传入；
 * - 转录进度/结果经 [com.example.videocompressor.service.TranscribeBus] 传入。
 */
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
        _uiState.update {
            CompressUiState(
                deviceProfile = it.deviceProfile,
                thermalLabel = thermalGovernor.currentLevel().label,
                serverUrl = it.serverUrl
            )
        }
    }

    fun updateServerUrl(url: String) {
        settingsStore.serverUrl = url
        _uiState.update { it.copy(serverUrl = url) }
    }

    fun startTranscribe() = startTranscribeService(TranscribeService.MODE_TRANSCRIBE)

    private fun startTranscribeService(mode: String, srtPath: String? = null) {
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
            srtPath?.let { putExtra(TranscribeService.EXTRA_SRT_PATH, it) }
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

    // ── 字幕逐句编辑 ───────────────────────────────────────

    fun loadSubtitles(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cues = runCatching { SrtParser.parse(java.io.File(path).readText()) }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(subtitleEdit = SubtitleEditState(path = path, cues = cues, dirty = false))
            }
        }
    }

    fun updateCueText(index: Int, text: String) {
        _uiState.update { state ->
            val edit = state.subtitleEdit ?: return@update state
            val cues = edit.cues.toMutableList()
            if (index !in cues.indices) return@update state
            cues[index] = cues[index].copy(text = text)
            state.copy(subtitleEdit = edit.copy(cues = cues, dirty = true, savedAt = null))
        }
    }

    fun saveSubtitles(then: (() -> Unit)? = null) {
        val edit = _uiState.value.subtitleEdit ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = writeSrt(edit)
            _uiState.update {
                val e = it.subtitleEdit ?: return@update it
                it.copy(subtitleEdit = e.copy(dirty = !ok, savedAt = if (ok) System.currentTimeMillis() else null))
            }
            if (ok && then != null) withContext(Dispatchers.Main) { then() }
        }
    }

    fun burnEditedSubtitle() {
        val edit = _uiState.value.subtitleEdit ?: return
        val server = _uiState.value.serverUrl.trim()
        if (server.isBlank()) {
            _uiState.update { it.copy(transcribeStatus = TranscribeStatus.Error("请先填写电脑服务地址")) }
            return
        }
        transcribeBus.reset()
        _uiState.update { it.copy(transcribeStatus = TranscribeStatus.Running("保存字幕…", null)) }
        viewModelScope.launch(Dispatchers.IO) {
            writeSrt(edit)
            _uiState.update {
                val e = it.subtitleEdit ?: return@update it
                it.copy(subtitleEdit = e.copy(dirty = false, savedAt = System.currentTimeMillis()))
            }
            startTranscribeService(TranscribeService.MODE_BURN_SRT, srtPath = edit.path)
        }
    }

    private fun writeSrt(edit: SubtitleEditState): Boolean = runCatching {
        java.io.File(edit.path).writeText(SrtParser.format(edit.cues))
    }.isSuccess
}

data class CompressUiState(
    val videoInfo: VideoInfo? = null,
    val config: CompressConfig = CompressConfig(),
    val status: CompressStatus = CompressStatus.Idle,
    val deviceProfile: DeviceProfile? = null,
    val thermalLabel: String = "未知",
    val serverUrl: String = "",
    val transcribeStatus: TranscribeStatus = TranscribeStatus.Idle,
    val subtitleEdit: SubtitleEditState? = null
)

data class SubtitleEditState(
    val path: String,
    val cues: List<SubtitleCue> = emptyList(),
    val dirty: Boolean = false,
    val savedAt: Long? = null
)

sealed class CompressStatus {
    data object Idle : CompressStatus()
    data class Running(val progress: Float) : CompressStatus()
    data class Done(val outputPath: String) : CompressStatus()
    data class Error(val message: String) : CompressStatus()
}

sealed class TranscribeStatus {
    data object Idle : TranscribeStatus()
    data class Running(val stage: String, val progress: Float?) : TranscribeStatus()
    data class Done(val srtPath: String, val chaptersPath: String?) : TranscribeStatus()
    data class DoneVideo(val videoUri: String) : TranscribeStatus()
    data class Error(val message: String) : TranscribeStatus()
}
