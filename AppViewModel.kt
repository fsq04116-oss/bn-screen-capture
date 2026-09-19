package com.baining.str

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baining.str.manager.AppLogger
import com.baining.str.manager.RecordingStateHolder
import com.baining.str.manager.RootCaptureManager
import com.baining.str.service.FloatingWindowService
import com.baining.str.service.ScreenCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {

    //── SharedPreferences（持久化设置）────────────────────────
    private val prefs: SharedPreferences =
        app.getSharedPreferences("bn_prefs", Context.MODE_PRIVATE)

    //── Root状态 ───────────────────────────────────────────────
    private val _isRooted = MutableStateFlow<Boolean?>(null)
    val isRooted: StateFlow<Boolean?> = _isRooted

    //── 服务状态 ───────────────────────────────────────────────
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning

    private val _serviceStatus = MutableStateFlow<ScreenCaptureService.ServiceStatus>(
        ScreenCaptureService.ServiceStatus.Idle
    )
    val serviceStatus: StateFlow<ScreenCaptureService.ServiceStatus> = _serviceStatus

    //── Bug6：全局录屏状态，悬浮窗和主面板共享 ─────────────────
    val globalRecording = RecordingStateHolder.isRecording

    //── 悬浮窗 ────────────────────────────────────────────────
    private val _floatEnabled = MutableStateFlow(false)
    val floatEnabled: StateFlow<Boolean> = _floatEnabled
    val isFloatingVisible = FloatingWindowService.isFloatingVisible

    //── 最近文件 ───────────────────────────────────────────────
    private val _recentFiles = MutableStateFlow<List<File>>(emptyList())
    val recentFiles: StateFlow<List<File>> = _recentFiles

    //── 截图模式：源码模式使用系统接口，模块模式通过 Root 读取显示设备节点 ──
    private val _captureMode = MutableStateFlow(
        RootCaptureManager.CaptureMode.entries.getOrElse(prefs.getInt("capture_mode", 0)) {
            RootCaptureManager.CaptureMode.SOURCE
        }
    )
    val captureMode: StateFlow<RootCaptureManager.CaptureMode> = _captureMode

    fun toggleCaptureMode() {
        setCaptureMode(if (_captureMode.value == RootCaptureManager.CaptureMode.SOURCE)
            RootCaptureManager.CaptureMode.MODULE else RootCaptureManager.CaptureMode.SOURCE)
    }

    fun setCaptureMode(mode: RootCaptureManager.CaptureMode) {
        _captureMode.value = mode
        prefs.edit().putInt("capture_mode", mode.ordinal).apply()
        AppLogger.log(AppLogger.Category.SETTINGS, "截图模式修改", mode.name)
    }

    private val _agreementAccepted = MutableStateFlow(prefs.getBoolean("agreement_v2_accepted", false))
    val agreementAccepted: StateFlow<Boolean> = _agreementAccepted
    private var runtimeInitialized = false

    fun acceptUserAgreement() {
        if (_agreementAccepted.value) return
        prefs.edit().putBoolean("agreement_v2_accepted", true).apply()
        _agreementAccepted.value = true
        initializeRuntime()
    }

    //── 录屏帧率（滑动范围 24~120fps，持久化）─────────────────
    // screenrecord 在多数 ROM 上不能强制写入 fps；该值会驱动码率与厂商参数探测。
    private val _fps = MutableStateFlow(prefs.getInt("fps", 30).coerceIn(24, 120))
    val fps: StateFlow<Int> = _fps

    //── 当前设备信息 ───────────────────────────────────────────
    val deviceInfo: String = buildString {
        append(Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
        append(" · ")
        append(Build.MODEL)
        append(" · Android ")
        append(Build.VERSION.RELEASE)
    }

    val cacheDirPath: String = app.externalCacheDir?.absolutePath ?: app.cacheDir.absolutePath
    val galleryDirPath: String = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "ScreenCapturePro"
    ).absolutePath

    //── 日志、主题与自我检测 ──────────────────────────────────
    private val _logEnabled = MutableStateFlow(prefs.getBoolean("log_enabled", false))
    val logEnabled: StateFlow<Boolean> = _logEnabled

    private val _themeColor = MutableStateFlow(prefs.getLong("theme_color", 0xFFBEDDF1L).toULong())
    val themeColor: StateFlow<ULong> = _themeColor

    private val _glassEnabled = MutableStateFlow(prefs.getBoolean("glass_enabled", true))
    val glassEnabled: StateFlow<Boolean> = _glassEnabled
    private val _glassIntensity = MutableStateFlow(prefs.getFloat("glass_intensity", 0.62f).coerceIn(0.25f, 0.90f))
    val glassIntensity: StateFlow<Float> = _glassIntensity

    private val _operationBusy = MutableStateFlow(false)
    val operationBusy: StateFlow<Boolean> = _operationBusy

    private val _diagnosticReport = MutableStateFlow("尚未执行自我检测")
    val diagnosticReport: StateFlow<String> = _diagnosticReport
    val logDirPath: String = AppLogger.LOG_DIR_PATH

    fun setLogEnabled(enabled: Boolean) {
        _logEnabled.value = enabled
        AppLogger.setEnabled(getApplication(), enabled)
    }

    fun setThemeColor(argb: ULong) {
        _themeColor.value = argb
        prefs.edit().putLong("theme_color", argb.toLong()).apply()
        AppLogger.log(AppLogger.Category.SETTINGS, "主题色修改", "ARGB=0x${argb.toString(16)}")
    }

    fun setGlassEnabled(enabled: Boolean) {
        _glassEnabled.value = enabled
        prefs.edit().putBoolean("glass_enabled", enabled).apply()
        AppLogger.log(AppLogger.Category.SETTINGS, "玻璃质感", if (enabled) "开启" else "关闭")
    }

    fun setGlassIntensity(value: Float) {
        val safe = value.coerceIn(0.25f, 0.90f)
        _glassIntensity.value = safe
        prefs.edit().putFloat("glass_intensity", safe).apply()
    }

    fun readLogs(): String = AppLogger.readLatest()

    fun runDiagnostics() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val root = RootCaptureManager.isRooted()
            val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            val audio = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val notifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val overlay = Settings.canDrawOverlays(app)
            val screenrecordHelp = try {
                val p = ProcessBuilder("su", "-c", "screenrecord --help 2>&1").start()
                p.inputStream.bufferedReader().readText().take(1000)
            } catch (e: Exception) { "无法运行 screenrecord：${e.message}" }
            val screenrecordAvailable = screenrecordHelp.contains("screenrecord", true) ||
                screenrecordHelp.contains("bit-rate", true) || screenrecordHelp.contains("size", true)
            val outputWritable = try {
                _saveDir.value.mkdirs()
                val probe = File(_saveDir.value, ".bn_write_test")
                probe.writeText("ok"); probe.delete(); true
            } catch (_: Exception) { false }
            val issues = mutableListOf<String>()
            if (!root) issues += "Root 未授予，Root 截图/录屏不可用"
            if (!storage) issues += "全部文件访问未授予，公共目录写入可能失败"
            if (!outputWritable) issues += "当前保存目录不可写"
            if (!screenrecordAvailable) issues += "未检测到可用的 screenrecord"
            if (_audioSource.value != RootCaptureManager.AudioSource.NONE && !audio) issues += "已选择录音但录音权限未授予"
            val report = buildString {
                appendLine("设备：$deviceInfo")
                appendLine("Root：${if (root) "已授予" else "未授予"}")
                appendLine("全部文件访问：${if (storage) "已授予" else "未授予"}")
                appendLine("录音权限：${if (audio) "已授予" else "未授予"}")
                appendLine("悬浮窗权限：${if (overlay) "已授予" else "未授予"}")
                appendLine("通知权限：${if (notifications) "已授予" else "未授予"}")
                appendLine("保存目录可写：${if (outputWritable) "是" else "否"}")
                appendLine("screenrecord：${if (screenrecordAvailable) "可用" else "不可用/无法确认"}")
                appendLine("缓存目录：$cacheDirPath")
                appendLine("相册目录：$galleryDirPath")
                appendLine("日志目录：$logDirPath")
                append("结论：${if (issues.isEmpty()) "未发现明显的录制阻断项" else issues.joinToString("；")}")
            }
            _diagnosticReport.value = report
            AppLogger.diagnostic("手动自我检测", "$report\n\nscreenrecord摘要：\n$screenrecordHelp")
        }
    }

    //── 录屏音频源（持久化）──────────────────────────────────
    private val _audioSource = MutableStateFlow(
        RootCaptureManager.AudioSource.values().getOrElse(
            prefs.getInt("audio_source", 0)
        ) { RootCaptureManager.AudioSource.NONE }
    )
    val audioSource: StateFlow<RootCaptureManager.AudioSource> = _audioSource

    fun setAudioSource(src: RootCaptureManager.AudioSource) {
        _audioSource.value = src
        prefs.edit().putInt("audio_source", src.ordinal).apply()
        AppLogger.log(AppLogger.Category.SETTINGS, "录屏音频修改", src.name)
    }

    //── 码率设置（持久化）─────────────────────────────────────
    private val _bitrateMbps = MutableStateFlow(prefs.getInt("bitrate_mbps", 8))
    val bitrateMbps: StateFlow<Int> = _bitrateMbps

    //── Bug5：自定义保存路径（持久化）────────────────────────
    private val _saveDir = MutableStateFlow(
        prefs.getString("save_dir", null)?.let { File(it) }?: File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "ScreenCapturePro"
            )
    )
    val saveDir: StateFlow<File> = _saveDir

    //── 服务绑定 ───────────────────────────────────────────────
    private var captureService: ScreenCaptureService? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            captureService = (binder as? ScreenCaptureService.LocalBinder)?.getService()
            _serviceRunning.value = true
            viewModelScope.launch {
                captureService?.status?.collect { status ->
                    _serviceStatus.value = status
                    _operationBusy.value = status is ScreenCaptureService.ServiceStatus.Capturing
                    // Bug6：同步全局录屏状态
                    when (status) {
                        is ScreenCaptureService.ServiceStatus.Recording ->
                            RecordingStateHolder.setRecording(true)
                        is ScreenCaptureService.ServiceStatus.RecordSuccess -> {
                            RecordingStateHolder.setRecording(false)
                            refreshRecentFiles()
                        }
                        is ScreenCaptureService.ServiceStatus.CaptureSuccess ->
                            refreshRecentFiles()
                        is ScreenCaptureService.ServiceStatus.Idle ->
                            RecordingStateHolder.setRecording(false)
                        else -> {}
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            _serviceRunning.value = false
        }
    }

    init {
        AppLogger.initialize(app)
        if (_agreementAccepted.value) initializeRuntime()
    }

    private fun initializeRuntime() {
        if (runtimeInitialized) return
        runtimeInitialized = true
        checkRoot()
        startAndBindService()
        refreshRecentFiles()
    }

    //── Root检测 ──────────────────────────────────────────────

    private fun checkRoot() {
        viewModelScope.launch {
            _isRooted.value = RootCaptureManager.isRooted()
        }
    }

    //── 服务管理 ───────────────────────────────────────────────

    private fun startAndBindService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, ScreenCaptureService::class.java)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    fun screenshot() {
        if (_operationBusy.value || RecordingStateHolder.isRecording.value) return
        val service = captureService
        if (service == null) {
            _serviceStatus.value = ScreenCaptureService.ServiceStatus.Error("捕获服务尚未连接，请稍后重试")
            return
        }
        if (!ensureOutputDirectory()) return
        _operationBusy.value = true
        service.doScreenshot(_saveDir.value, _captureMode.value)
    }

    /** 业务级互斥：防止按钮连续点击重复启动多个 screenrecord。 */
    fun startRecord() {
        if (_operationBusy.value || RecordingStateHolder.isRecording.value) return
        val service = captureService
        if (service == null) {
            _serviceStatus.value = ScreenCaptureService.ServiceStatus.Error("录屏服务尚未连接，请稍后重试")
            return
        }
        if (!ensureOutputDirectory()) return
        _operationBusy.value = true
        service.doStartRecord(_saveDir.value, _bitrateMbps.value, _fps.value, _audioSource.value)
    }

    fun stopRecord() {
        if (_operationBusy.value || !RecordingStateHolder.isRecording.value) return
        val service = captureService ?: run {
            _serviceStatus.value = ScreenCaptureService.ServiceStatus.Error("录屏服务连接已断开")
            return
        }
        _operationBusy.value = true
        service.doStopRecord()
    }

    private fun ensureOutputDirectory(): Boolean {
        val dir = _saveDir.value
        val writable = try {
            (dir.exists() || dir.mkdirs()) && dir.isDirectory && dir.canWrite()
        } catch (_: Exception) { false }
        if (!writable) {
            _serviceStatus.value = ScreenCaptureService.ServiceStatus.Error("保存目录不可写：${dir.absolutePath}")
            AppLogger.log(AppLogger.Category.ERROR, "保存目录不可写", dir.absolutePath)
        }
        return writable
    }

    val isRecording: Boolean get() = RecordingStateHolder.isRecording.value

    //── 悬浮窗 ────────────────────────────────────────────────

    fun setFloatEnabled(enabled: Boolean) {
        _floatEnabled.value = enabled
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, FloatingWindowService::class.java).apply {
            action = if (enabled) FloatingWindowService.ACTION_SHOW
            else FloatingWindowService.ACTION_HIDE
        }
        ctx.startForegroundService(intent)
    }

    fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(getApplication())

    //── Bug1：帧率设置 ─────────────────────────────────────────

    fun setFps(fps: Int) {
        val v = fps.coerceIn(24, 120)
        _fps.value = v
        prefs.edit().putInt("fps", v).apply()
        AppLogger.log(AppLogger.Category.SETTINGS, "录屏帧率修改", "$v fps")
    }

    //── 码率设置 ───────────────────────────────────────────────

    fun setBitrate(mbps: Int) {
        val v = mbps.coerceIn(1, 40)
        _bitrateMbps.value = v
        prefs.edit().putInt("bitrate_mbps", v).apply()
        AppLogger.log(AppLogger.Category.SETTINGS, "录屏码率修改", "$v Mbps")
    }

    //── Bug5：自定义保存路径 ───────────────────────────────────

    /** 接收系统目录选择器返回的 Uri，解析为 File 并持久化 */
    fun setSaveDirFromUri(uri: Uri) {
        val path = uri.path?.replace("/tree/primary:", "")?.let {
            File(Environment.getExternalStorageDirectory(), it)
        } ?: return
        _saveDir.value = path.also { it.mkdirs() }
        prefs.edit().putString("save_dir", path.absolutePath).apply()
        refreshRecentFiles()
    }

    /** 直接设置路径（字符串，用于简单场景）*/
    fun setSaveDir(dir: File) {
        val valid = try { (dir.exists() || dir.mkdirs()) && dir.isDirectory && dir.canWrite() } catch (_: Exception) { false }
        if (!valid) {
            _serviceStatus.value = ScreenCaptureService.ServiceStatus.Error("无法使用该保存目录：${dir.absolutePath}")
            AppLogger.log(AppLogger.Category.ERROR, "保存目录设置失败", dir.absolutePath)
            return
        }
        _saveDir.value = dir
        prefs.edit().putString("save_dir", dir.absolutePath).apply()
        AppLogger.log(AppLogger.Category.SETTINGS, "保存目录修改", dir.absolutePath)
        refreshRecentFiles()
    }

    //── 文件列表 ───────────────────────────────────────────────

    fun refreshRecentFiles() {
        viewModelScope.launch {
            val dir = _saveDir.value
            val files = dir.listFiles()?.filter { it.isFile && (it.name.endsWith(".png") || it.name.endsWith(".mp4")) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(20)
                ?: emptyList()
            _recentFiles.value = files
        }
    }

    fun deleteFile(file: File) {
        file.delete()
        refreshRecentFiles()
    }

    override fun onCleared() {
        try { getApplication<Application>().unbindService(conn) } catch (_: Exception) {}
        super.onCleared()
    }
}

