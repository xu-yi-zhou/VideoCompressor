package com.example.videocompressor.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程内字幕/转写进度总线，作用同 [CompressProgressBus]：由 `TranscribeService` 写、
 * `CompressViewModel` 收，避免广播在新版 Android 上的不可靠。
 *
 * 软字幕(SRT) 完成走 [Event.Done]（旁挂文件路径）；烧字幕完成走 [Event.DoneVideo]（相册 Uri）。
 */
@Singleton
class TranscribeBus @Inject constructor() {

    sealed class Event {
        data object Idle : Event()
        /** progress 为 null 表示不确定进度（显示循环进度条）。 */
        data class Running(val stage: String, val progress: Float?) : Event()
        data class Done(val srtPath: String, val chaptersPath: String?) : Event()
        data class DoneVideo(val videoUri: String) : Event()
        data class Error(val message: String) : Event()
    }

    private val _events = MutableStateFlow<Event>(Event.Idle)
    val events: StateFlow<Event> = _events.asStateFlow()

    fun running(stage: String, progress: Float?) { _events.value = Event.Running(stage, progress) }
    fun done(srtPath: String, chaptersPath: String?) { _events.value = Event.Done(srtPath, chaptersPath) }
    fun doneVideo(videoUri: String) { _events.value = Event.DoneVideo(videoUri) }
    fun error(message: String) { _events.value = Event.Error(message) }
    fun reset() { _events.value = Event.Idle }
}
