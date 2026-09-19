package com.baining.str.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baining.str.AppViewModel
import com.baining.str.manager.RootCaptureManager
import com.baining.str.service.ScreenCaptureService
import com.baining.str.ui.theme.DreamBlue
import com.baining.str.ui.theme.DreamInkStrong
import com.baining.str.ui.theme.DreamPink
import com.baining.str.ui.theme.DreamPurple
import com.baining.str.ui.theme.DreamYellow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class GlassStyle(val enabled: Boolean, val intensity: Float)
private val LocalGlassStyle = staticCompositionLocalOf { GlassStyle(true, 0.62f) }

private enum class WorkspaceTab(val title: String, val icon: ImageVector) {
    CAPTURE("截图", Icons.Filled.PhotoCamera),
    RECORD("录制", Icons.Filled.Videocam),
    SYSTEM("系统", Icons.Filled.Tune)
}

@Composable
fun MainScreenV2(vm: AppViewModel) {
    val status by vm.serviceStatus.collectAsState()
    val rooted by vm.isRooted.collectAsState()
    val files by vm.recentFiles.collectAsState()
    val floating by vm.floatEnabled.collectAsState()
    val recording by vm.globalRecording.collectAsState()
    val mode by vm.captureMode.collectAsState()
    val bitrate by vm.bitrateMbps.collectAsState()
    val fps by vm.fps.collectAsState()
    val audio by vm.audioSource.collectAsState()
    val saveDir by vm.saveDir.collectAsState()
    val logEnabled by vm.logEnabled.collectAsState()
    val themeColor by vm.themeColor.collectAsState()
    val diagnostics by vm.diagnosticReport.collectAsState()
    val glassEnabled by vm.glassEnabled.collectAsState()
    val glassIntensity by vm.glassIntensity.collectAsState()
    val operationBusy by vm.operationBusy.collectAsState()

    var tab by remember { mutableStateOf(WorkspaceTab.CAPTURE) }
    var showPath by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showLegal by remember { mutableStateOf(false) }

    if (showPath) PathDialogV2(saveDir.absolutePath, { vm.setSaveDir(File(it)); showPath = false }, { showPath = false })
    if (showLogs) TextReportDialog("详细日志", vm.readLogs(), { showLogs = false })
    if (showLegal) LegalDocumentsDialog { showLegal = false }

    CompositionLocalProvider(LocalGlassStyle provides GlassStyle(glassEnabled, glassIntensity)) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.background,
                    DreamPink.copy(alpha = .46f),
                    DreamYellow.copy(alpha = .28f),
                    DreamBlue.copy(alpha = .38f),
                    MaterialTheme.colorScheme.background
                )
            )
        )
    ) {
        val wide = maxWidth >= 840.dp
        if (glassEnabled) {
            Box(Modifier.size(if (wide) 420.dp else 280.dp).offset(x = maxWidth * .58f, y = (-90).dp).blur(80.dp).background(DreamBlue.copy(alpha = .42f), CircleShape))
            Box(Modifier.size(if (wide) 340.dp else 220.dp).offset(x = (-80).dp, y = maxHeight * .62f).blur(72.dp).background(DreamPurple.copy(alpha = .35f), CircleShape))
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LazyColumn(
                    Modifier.weight(.43f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    item { BrandHeader(rooted) }
                    item { HeroStatus(status, recording) }
                    item { QuickActions(recording, operationBusy, mode, vm::screenshot, { if (recording) vm.stopRecord() else vm.startRecord() }) }
                    item { RecentMedia(files, vm::deleteFile) }
                }
                Column(Modifier.weight(.57f).fillMaxHeight()) {
                    WorkspaceNavigation(tab) { tab = it }
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 28.dp)
                    ) {
                        item {
                            SettingsWorkspace(
                                tab, mode, floating, fps, bitrate, audio, saveDir.absolutePath,
                                vm.deviceInfo, vm.cacheDirPath, vm.galleryDirPath, vm.logDirPath,
                                logEnabled, themeColor, diagnostics, glassEnabled, glassIntensity,
                                vm::setCaptureMode, vm::setFloatEnabled, vm::setFps, vm::setBitrate,
                                vm::setAudioSource, { showPath = true }, vm::setLogEnabled,
                                vm::setThemeColor, vm::setGlassEnabled, vm::setGlassIntensity, vm::runDiagnostics, { showLogs = true }, { showLegal = true }
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(16.dp, 20.dp, 16.dp, 80.dp)
            ) {
                item { BrandHeader(rooted) }
                item { HeroStatus(status, recording) }
                item { QuickActions(recording, operationBusy, mode, vm::screenshot, { if (recording) vm.stopRecord() else vm.startRecord() }) }
                item { WorkspaceNavigation(tab) { tab = it } }
                item {
                    SettingsWorkspace(
                        tab, mode, floating, fps, bitrate, audio, saveDir.absolutePath,
                        vm.deviceInfo, vm.cacheDirPath, vm.galleryDirPath, vm.logDirPath,
                        logEnabled, themeColor, diagnostics, glassEnabled, glassIntensity,
                        vm::setCaptureMode, vm::setFloatEnabled, vm::setFps, vm::setBitrate,
                        vm::setAudioSource, { showPath = true }, vm::setLogEnabled,
                        vm::setThemeColor, vm::setGlassEnabled, vm::setGlassIntensity, vm::runDiagnostics, { showLogs = true }, { showLegal = true }
                    )
                }
                item { RecentMedia(files, vm::deleteFile) }
            }
        }
    }
    }
}

@Composable
private fun BrandHeader(rooted: Boolean?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(DreamBlue, DreamPink, DreamPurple))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.CenterFocusStrong, null, Modifier.size(27.dp), DreamInkStrong)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("白柠截图", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Capture workspace · v1.5", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  ·  @bnstr", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        val ok = rooted == true
        Surface(
            shape = RoundedCornerShape(50),
            color = (if (ok) Color(0xFF73BFA4) else MaterialTheme.colorScheme.error).copy(alpha = .12f)
        ) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Color(0xFF73BFA4) else MaterialTheme.colorScheme.error))
                Spacer(Modifier.width(7.dp))
                Text(if (rooted == null) "检测中" else if (ok) "Root 在线" else "无 Root", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HeroStatus(status: ScreenCaptureService.ServiceStatus, recording: Boolean) {
    val accent = when {
        recording -> MaterialTheme.colorScheme.error
        status is ScreenCaptureService.ServiceStatus.Error -> MaterialTheme.colorScheme.error
        status is ScreenCaptureService.ServiceStatus.CaptureSuccess || status is ScreenCaptureService.ServiceStatus.RecordSuccess -> Color(0xFF73BFA4)
        else -> MaterialTheme.colorScheme.primary
    }
    val title: String
    val subtitle: String
    val icon: ImageVector
    when (status) {
        ScreenCaptureService.ServiceStatus.Idle -> { title = "捕获服务已就绪"; subtitle = "选择截图或录制开始工作"; icon = Icons.Filled.CheckCircle }
        ScreenCaptureService.ServiceStatus.Capturing -> { title = "正在捕获画面"; subtitle = "正在等待系统完成图层合成"; icon = Icons.Filled.HourglassTop }
        ScreenCaptureService.ServiceStatus.Recording -> { title = "正在录制屏幕"; subtitle = "点击停止后会自动检查视频黑帧"; icon = Icons.Filled.FiberManualRecord }
        is ScreenCaptureService.ServiceStatus.CaptureSuccess -> { title = "截图已保存"; subtitle = status.file.name; icon = Icons.Filled.CheckCircle }
        is ScreenCaptureService.ServiceStatus.RecordSuccess -> { title = "视频已保存"; subtitle = status.file.name; icon = Icons.Filled.CheckCircle }
        is ScreenCaptureService.ServiceStatus.Error -> { title = "需要处理"; subtitle = status.message; icon = Icons.Filled.WarningAmber }
    }
    Surface(
        Modifier.fillMaxWidth(), RoundedCornerShape(24.dp),
        color = accent.copy(alpha = .10f), border = BorderStroke(1.dp, accent.copy(alpha = .22f))
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(46.dp), CircleShape, color = accent.copy(alpha = .16f)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp)) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (recording) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            }
        }
    }
}

@Composable
private fun QuickActions(
    recording: Boolean,
    busy: Boolean,
    mode: RootCaptureManager.CaptureMode,
    screenshot: () -> Unit,
    record: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CommandButton(
            Modifier.weight(1f), Icons.Filled.PhotoCamera,
            if (mode == RootCaptureManager.CaptureMode.MODULE) "模块截图" else "源码截图",
            if (busy) "正在处理…" else "PNG · 快速稳定", MaterialTheme.colorScheme.primary, !busy && !recording, screenshot
        )
        CommandButton(
            Modifier.weight(1f), if (recording) Icons.Filled.Stop else Icons.Filled.Videocam,
            if (recording) "停止录制" else "开始录制",
            if (recording) "正在写入 MP4" else "自动黑帧检测",
            if (recording) MaterialTheme.colorScheme.error else DreamPurple, !busy, record
        )
    }
}

@Composable
private fun CommandButton(modifier: Modifier, icon: ImageVector, title: String, subtitle: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .965f else 1f, label = "command")
    val contentColor = if (color.prefersDarkContent()) DreamInkStrong else Color.White
    Surface(
        onClick = onClick, modifier = modifier.scale(scale).height(112.dp), enabled = enabled, shape = RoundedCornerShape(22.dp),
        color = color, contentColor = contentColor, interactionSource = source, shadowElevation = if (pressed) 1.dp else 7.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(29.dp))
            Column {
                Text(title, color = contentColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = contentColor.copy(alpha = .74f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WorkspaceNavigation(selected: WorkspaceTab, select: (WorkspaceTab) -> Unit) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f)) {
        Row(Modifier.padding(5.dp)) {
            WorkspaceTab.entries.forEach { tab ->
                val active = tab == selected
                val color by animateColorAsState(if (active) MaterialTheme.colorScheme.surface else Color.Transparent, label = "tab")
                Surface(onClick = { select(tab) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = color, shadowElevation = if (active) 3.dp else 0.dp) {
                    Row(Modifier.padding(vertical = 11.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(tab.icon, null, Modifier.size(18.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(7.dp))
                        Text(tab.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsWorkspace(
    tab: WorkspaceTab,
    mode: RootCaptureManager.CaptureMode,
    floating: Boolean,
    fps: Int,
    bitrate: Int,
    audio: RootCaptureManager.AudioSource,
    saveDir: String,
    device: String,
    cacheDir: String,
    galleryDir: String,
    logDir: String,
    logEnabled: Boolean,
    themeColor: ULong,
    diagnostics: String,
    glassEnabled: Boolean,
    glassIntensity: Float,
    setMode: (RootCaptureManager.CaptureMode) -> Unit,
    toggleFloating: (Boolean) -> Unit,
    setFps: (Int) -> Unit,
    setBitrate: (Int) -> Unit,
    setAudio: (RootCaptureManager.AudioSource) -> Unit,
    pickDir: () -> Unit,
    toggleLog: (Boolean) -> Unit,
    setTheme: (ULong) -> Unit,
    setGlass: (Boolean) -> Unit,
    setGlassIntensity: (Float) -> Unit,
    runDiagnostics: () -> Unit,
    viewLogs: () -> Unit,
    viewLegal: () -> Unit
) {
    AnimatedContent(tab, label = "workspace") { current ->
        when (current) {
            WorkspaceTab.CAPTURE -> CaptureSettings(mode, floating, saveDir, setMode, toggleFloating, pickDir)
            WorkspaceTab.RECORD -> RecordingSettings(fps, bitrate, audio, setFps, setBitrate, setAudio)
            WorkspaceTab.SYSTEM -> SystemSettings(device, cacheDir, galleryDir, logDir, logEnabled, themeColor, diagnostics, glassEnabled, glassIntensity, toggleLog, setTheme, setGlass, setGlassIntensity, runDiagnostics, viewLogs, viewLegal)
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalGlassStyle.current
    val cardAlpha = if (glass.enabled) (1f - glass.intensity * .48f).coerceIn(.56f, .88f) else .96f
    val borderColor = if (glass.enabled) Color.White.copy(alpha = .12f + glass.intensity * .20f) else MaterialTheme.colorScheme.outline.copy(alpha = .12f)
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = cardAlpha), border = BorderStroke(1.dp, borderColor), tonalElevation = if (glass.enabled) 6.dp else 1.dp, shadowElevation = if (glass.enabled) 10.dp else 2.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(40.dp), RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
            content()
        }
    }
}

@Composable
private fun CaptureSettings(mode: RootCaptureManager.CaptureMode, floating: Boolean, saveDir: String, setMode: (RootCaptureManager.CaptureMode) -> Unit, toggleFloating: (Boolean) -> Unit, pickDir: () -> Unit) {
    SectionCard("截图设置", "捕获模式、悬浮控制与输出位置", Icons.Filled.PhotoCamera) {
        Text("捕获模式", fontWeight = FontWeight.SemiBold)
        ChoiceRow(listOf("源码模式", "模块模式"), mode.ordinal) { index ->
            setMode(RootCaptureManager.CaptureMode.entries[index])
        }
        AssistiveText(if (mode == RootCaptureManager.CaptureMode.SOURCE) "调用 Android 自带 screencap，速度快，并自动检测异常黑带后重试。" else "取得 Root 后只读显示设备节点（fb0 / DRM），不会写入系统分区。")
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
        SettingToggle(Icons.Filled.PictureInPicture, "悬浮控制栏", "在其他应用上方显示截图与录制按钮", floating, toggleFloating)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
        SettingLink(Icons.Filled.FolderOpen, "媒体保存位置", saveDir, pickDir)
    }
}

@Composable
private fun RecordingSettings(fps: Int, bitrate: Int, audio: RootCaptureManager.AudioSource, setFps: (Int) -> Unit, setBitrate: (Int) -> Unit, setAudio: (RootCaptureManager.AudioSource) -> Unit) {
    SectionCard("录制设置", "自动分段续录并合并，单次最长 10 小时", Icons.Filled.Videocam) {
        ValueHeader("目标帧率", "$fps fps")
        Slider(fps.toFloat(), { setFps(it.toInt()) }, valueRange = 24f..120f, steps = 95)
        ChoiceRow(listOf("30", "60", "90", "120"), listOf(30,60,90,120).indexOf(fps)) { setFps(listOf(30,60,90,120)[it]) }
        AssistiveText("系统 screenrecord 可能按显示刷新率输出；该值用于质量策略与日志，不保证强制帧率。")
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
        ValueHeader("视频码率", "$bitrate Mbps")
        Slider(bitrate.toFloat(), { setBitrate(it.toInt()) }, valueRange = 2f..30f, steps = 27)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
        Text("录屏声音", fontWeight = FontWeight.SemiBold)
        ChoiceRow(listOf("静音", "系统内录", "麦克风"), audio.ordinal) { setAudio(RootCaptureManager.AudioSource.entries[it]) }
        AssistiveText("部分小米 ROM 不支持 screenrecord 音频参数，失败时会自动回退为静音录制。")
    }
}

@Composable
private fun SystemSettings(device: String, cacheDir: String, galleryDir: String, logDir: String, logEnabled: Boolean, themeColor: ULong, diagnostics: String, glassEnabled: Boolean, glassIntensity: Float, toggleLog: (Boolean) -> Unit, setTheme: (ULong) -> Unit, setGlass: (Boolean) -> Unit, setGlassIntensity: (Float) -> Unit, runDiagnostics: () -> Unit, viewLogs: () -> Unit, viewLegal: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("设备与诊断", "权限、录制能力与存储健康检查", Icons.Filled.HealthAndSafety) {
            InfoLine(Icons.Filled.TabletAndroid, "设备", device)
            InfoLine(Icons.Filled.PhotoLibrary, "相册目录", galleryDir)
            InfoLine(Icons.Filled.Memory, "缓存目录", cacheDir)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = runDiagnostics, Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.FactCheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("开始自检") }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
                Text(diagnostics, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp)
            }
        }
        SectionCard("日志与外观", "记录故障并自定义工作台主题", Icons.Filled.Palette) {
            SettingToggle(Icons.Filled.Description, "详细日志", "自动记录截图、录制、参数与黑屏区间", logEnabled, toggleLog)
            SettingToggle(Icons.Filled.BlurOn, "毛玻璃质感", "半透明卡片、柔光背景和高光边框", glassEnabled, setGlass)
            if (glassEnabled) {
                ValueHeader("玻璃强度", "${(glassIntensity * 100).toInt()}%")
                Slider(glassIntensity, setGlassIntensity, valueRange = 0.25f..0.90f)
            }
            InfoLine(Icons.Filled.Folder, "日志目录", logDir)
            OutlinedButton(onClick = viewLogs, Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Filled.Article, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("查看详细日志") }
            OutlinedButton(onClick = viewLegal, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Gavel, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("用户协议与开源许可") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .12f))
            Text("主题色", fontWeight = FontWeight.SemiBold)
            val colors = listOf(0xFFBEDDF1uL, 0xFFF9FEC7uL, 0xFFF1D1ECuL, 0xFFCEC5EEuL, 0xFF73BFA4uL, 0xFFD86A92uL, 0xFF5FAFD0uL, 0xFF39435AuL)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                colors.forEach { value ->
                    val selected = themeColor == value
                    Surface(onClick = { setTheme(value) }, modifier = Modifier.size(if (selected) 38.dp else 33.dp), shape = CircleShape, color = Color(value.toInt()), border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null) {}
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, changed)
    }
}

@Composable
private fun SettingLink(icon: ImageVector, title: String, value: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = click).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ValueHeader(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) { Text(value, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ChoiceRow(labels: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(index == selected, { choose(index) }, { Text(label, maxLines = 1) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AssistiveText(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)

@Composable
private fun InfoLine(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(10.dp))
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecentMedia(files: List<File>, delete: (File) -> Unit) {
    if (files.isEmpty()) return
    SectionCard("最近文件", "最近生成的截图和视频", Icons.Filled.Collections) {
        files.take(6).forEachIndexed { index, file ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .10f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(42.dp), RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                    Box(contentAlignment = Alignment.Center) { Icon(if (file.extension.equals("mp4", true)) Icons.Filled.Movie else Icons.Filled.Image, null, tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${formatSize(file.length())} · ${formatDate(file.lastModified())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ delete(file) }) { Icon(Icons.Filled.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun Color.prefersDarkContent(): Boolean {
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return luminance > 0.58f
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
private fun formatDate(time: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))

@Composable
private fun PathDialogV2(current: String, confirm: (String) -> Unit, dismiss: () -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Filled.FolderOpen, null) }, title = { Text("媒体保存位置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("输入可写入的绝对目录路径。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
        }
    }, confirmButton = { Button({ if (value.isNotBlank()) confirm(value.trim()) }) { Text("保存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable
private fun TextReportDialog(title: String, report: String, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Text(report, Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 540.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
    }, confirmButton = { TextButton(dismiss) { Text("关闭") } })
}

