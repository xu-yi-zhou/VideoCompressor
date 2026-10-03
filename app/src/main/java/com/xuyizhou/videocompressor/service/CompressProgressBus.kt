/*
 * 软件名称：视频压缩工具箱（VideoCompressor）
 * 版权所有 © 2025 XU Yizhou。保留所有权利。
 * 本软件受《中华人民共和国著作权法》保护，未经著作权人书面许可，
 * 不得擅自复制、修改、传播或用于商业用途。
 */
package com.xuyizhou.videocompressor.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内压缩进度/结果总线。
 *
 * `CompressService` 与 `CompressViewModel` 运行在同一进程，旧实现用 `sendBroadcast` 回传进度，
 * 在 Android 14/15 上隐式广播 + 动态注册接收器并不可靠，会导致进度条/百分比不更新。
 * 这里改用单例共享的 [StateFlow]，由 Service 写、ViewModel 收，进度更新确定可达。
 *
 * 事件按「批量压缩」建模（单视频即 count=1 的特例）：
 * - [Event.Progress] 携带整体进度（0~1）、当前视频序号与总数，丢失中间值无妨；
 * - [Event.Done] 一次性携带全部逐项结果 —— [StateFlow] 会合并中间值，逐项结果
 *   若作为独立事件连发可能被吞掉，故由 Service 内部累积后在终点统一发出；
 * - [Event.Error] 仅用于服务级致命错误（如 config 解析失败）。
 */
@Singleton
class CompressProgressBus @Inject constructor() {

    sealed class Event {
        data object Idle : Event()
        data class Progress(
            val overall: Float,
            val index: Int,
            val count: Int,
            val name: String
        ) : Event()

        data class Done(val results: List<BatchResult>) : Event()
        data class Error(val message: String) : Event()
    }

    private val _events = MutableStateFlow<Event>(Event.Idle)
    val events: StateFlow<Event> = _events.asStateFlow()

    fun progress(overall: Float, index: Int, count: Int, name: String) {
        _events.value = Event.Progress(overall, index, count, name)
    }

    fun done(results: List<BatchResult>) { _events.value = Event.Done(results) }
    fun error(message: String) { _events.value = Event.Error(message) }
    fun reset() { _events.value = Event.Idle }
}

/** 单个视频的压缩结果。[outputUri] 与 [error] 互斥；[inputSize] 由 ViewModel 按 [index] 补全。 */
data class BatchResult(
    val index: Int,
    val name: String,
    val outputUri: String? = null,
    val error: String? = null,
    val inputSize: Long = 0L
)
