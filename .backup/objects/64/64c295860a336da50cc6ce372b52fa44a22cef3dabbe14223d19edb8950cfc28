package com.baining.str.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Bug6 修复：全局录屏状态单例
 * 主面板（AppViewModel）和悬浮窗（FloatingWindowService）共享同一StateFlow，
 * 任意一端写入，两端 UI 自动同步刷新。
 */
object RecordingStateHolder {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _currentFile = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> = _currentFile

    fun setRecording(recording: Boolean, file: File? = null) {
        _isRecording.value = recording
        if (file != null) _currentFile.value = file
        if (!recording) _currentFile.value = null
    }
}
