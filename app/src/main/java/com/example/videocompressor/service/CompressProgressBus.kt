package com.example.videocompressor.service

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
 */
@Singleton
class CompressProgressBus @Inject constructor() {

    sealed class Event {
        data object Idle : Event()
        data class Progress(val value: Float) : Event()
        data class Complete(val outputPath: String) : Event()
        data class Error(val message: String) : Event()
    }

    private val _events = MutableStateFlow<Event>(Event.Idle)
    val events: StateFlow<Event> = _events.asStateFlow()

    fun progress(value: Float) { _events.value = Event.Progress(value) }
    fun complete(outputPath: String) { _events.value = Event.Complete(outputPath) }
    fun error(message: String) { _events.value = Event.Error(message) }
    fun reset() { _events.value = Event.Idle }
}
