package com.baining.str.manager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Root截图/录屏管理器
 *
 * 绕过 FLAG_SECURE 原理：
 * - `su -c screencap` 以 root/system 身份直接调用 SurfaceFlinger 合成帧，
 *   完全绕过应用层 SECURE窗口标志。
 * - 内核帧缓冲备用方案：读取 /dev/graphics/fb0 原始像素数据。
 */
object RootCaptureManager {

    private const val TAG = "RootCaptureManager"
    private const val TIMEOUT_MS = 10000L

    //── Root 检测 ──────────────────────────────────────────────

    suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val ok = p.waitFor(3, TimeUnit.SECONDS)
            if (!ok) { p.destroyForcibly(); return@withContext false }
            val out = p.inputStream.bufferedReader().readText()
            p.errorStream.bufferedReader().readText()
            out.contains("uid=0")
        } catch (e: Exception) { false }
    }

        // ── 截图模式 ───────────────────────────────────────────────
    enum class CaptureMode { SOURCE, MODULE }

    /**
     * 源码模式：使用 Android 自带 screencap，速度快、兼容性高。
     * 模块模式：仅通过 Root 读取显示设备节点（fb0 / DRM），不修改系统属性或分区内容。
     */
    suspend fun captureScreen(
        outputDir: File,
        mode: CaptureMode = CaptureMode.SOURCE,
        quality: Int = 95,
        context: android.content.Context? = null
    ): CaptureResult = withContext(Dispatchers.IO) {
            outputDir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val png = File(outputDir, "shot_${if (mode == CaptureMode.MODULE) "module_" else ""}$ts.png")

            if (mode == CaptureMode.MODULE) {
                // 模块模式只读取显示相关设备节点，失败后不改系统属性，直接给出明确结果。
                if (context != null) {
                    val gpuResult = captureGpu(context, outputDir)
                    if (gpuResult is CaptureResult.Success) return@withContext gpuResult
                    Log.w(TAG, "GPU module failed, fallback to display nodes: ${(gpuResult as CaptureResult.Error).message}")
                }
                return@withContext captureModulePartition(outputDir, ts)
            }

            // 主模式：首帧立即截图；仅检测到半屏黑的未完成合成帧时才短暂重试。
            // 不在正常路径 sleep，避免为了稳定性牺牲全部截图速度。
            val stable = captureStableFrame(png)
            if (stable != null) {
                addWatermark(stable)
                return@withContext CaptureResult.Success(stable)
            }

            // 主模式 fallback → fb0
            val fb = captureFb0(outputDir, ts)
            if (fb != null) {
                addWatermark(fb)
                return@withContext CaptureResult.Success(fb)
            }
            png.delete()
            CaptureResult.Error("截图失败：screencap 未返回完整帧")
        }

    /** Root 模块路径：只读显示设备节点，不写入系统分区。 */
    private fun captureModulePartition(outputDir: File, ts: String): CaptureResult {
        val fb0 = captureFb0(outputDir, ts)
        if (fb0 != null) {
            addWatermark(fb0)
            return CaptureResult.Success(fb0)
        }
        val drm = captureDrm(outputDir, ts)
        if (drm != null) {
            addWatermark(drm)
            return CaptureResult.Success(drm)
        }
        return CaptureResult.Error("模块模式失败：未能读取 /dev/graphics/fb0 或 /dev/dri/card0，请检查 Root 授权与设备节点权限")
    }

        /**
         * 快速稳定截图。
         *
         * 第一次不等待，直接执行 screencap；只有图片被判定为“局部大黑条”时，
         * 才分别等待 35ms、90ms 后重抓。这比无脑 sleep 更快，也避免将 HWC
         * 合成过程中的半成品帧保存出去。
         */
        private fun captureStableFrame(output: File): File? {
            val retryDelaysMs = longArrayOf(0L, 35L, 90L)
            for ((attempt, delayMs) in retryDelaysMs.withIndex()) {
                if (delayMs > 0L) Thread.sleep(delayMs)
                output.delete()

                val result = shell(
                    "screencap -p ${shellQuote(output.absolutePath)} && chmod 644 ${shellQuote(output.absolutePath)}"
                )
                if (!result.ok || !output.exists() || output.length() <= 500L) {
                    Log.w(TAG, "screencap attempt=${attempt + 1} failed: ${result.err.take(120)}")
                    continue
                }

                if (!hasPartialBlackFrame(output)) {
                    if (attempt > 0) Log.i(TAG, "Stable screenshot recovered on attempt=${attempt + 1}")
                    return output
                }

                Log.w(TAG, "Partial black frame detected on attempt=${attempt + 1}; retrying")
            }
            output.delete()
            return null
        }

        /**
         * 低成本检测“半屏黑”的未完成帧。
         * 仅把连续的横向大黑带（占高度至少 25%），且画面其他行存在正常像素时，
         * 判为异常；正常的全黑页面、电影黑边和局部深色 UI 不会被误判重试。
         */
        private fun hasPartialBlackFrame(file: File): Boolean {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return true

            val sample = BitmapFactory.Options().apply {
                inSampleSize = 8
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, sample) ?: return true
            try {
                val rows = 24
                val columns = 20
                val blackRows = BooleanArray(rows)
                var normalRows = 0

                for (row in 0 until rows) {
                    val y = (row * (bitmap.height - 1) / (rows - 1)).coerceAtLeast(0)
                    var blackPixels = 0
                    for (column in 0 until columns) {
                        val x = (column * (bitmap.width - 1) / (columns - 1)).coerceAtLeast(0)
                        val pixel = bitmap.getPixel(x, y)
                        val luminance = ((pixel shr 16 and 0xFF) * 299 +
                            (pixel shr 8 and 0xFF) * 587 +
                            (pixel and 0xFF) * 114) / 1000
                        if (luminance <= 8) blackPixels++
                    }
                    blackRows[row] = blackPixels >= columns * 18 / 20
                    if (!blackRows[row]) normalRows++
                }

                // 仅对“部分画面”异常做重试：全黑画面可能是用户正常内容。
                if (normalRows < rows / 4) return false

                var longestBlackBand = 0
                var currentBand = 0
                for (isBlack in blackRows) {
                    if (isBlack) {
                        currentBand++
                        longestBlackBand = maxOf(longestBlackBand, currentBand)
                    } else {
                        currentBand = 0
                    }
                }
                return longestBlackBand >= rows / 4
            } catch (e: Exception) {
                Log.w(TAG, "Black-frame check failed: ${e.message}")
                return false
            } finally {
                bitmap.recycle()
            }
        }

        private fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\\"'\\\"'") + "'"

        /**
     * 深度截图：绕过 SurfaceFlinger 保护Layer。
     *
     * 内核外挂防录屏原理：把渲染层标记为 GRALLOC_USAGE_PROTECTED，
     * 使得 screencap 和 fb0 只能读到背景，外挂内容不可见。
     *
     *绕过策略（按成功率排序）：
     * 1. disable_secure_layers prop → 让 SF忽略 secure buffer 标志（最有效）
     * 2. force_no_secure_buffers prop → 强制全局关闭保护
     * 3. KEYCODE_SYSRQ 硬件快照（绕过所有软件层）
     * 4. fb0 直读（外挂未hook 内核驱动时有效）
     * 5. DRM renderD128 GPU 输出缓冲
     */
    private fun captureDeep(outputDir: File, ts: String, output: File): CaptureResult {

        // 方案1：通过 system property 禁用 SurfaceFlinger 的 secure layer 保护
        // debug.sf.disable_secure_layers=1 让 SF 把 protected buffer 当普通 buffer 处理
        Log.i(TAG, "Deep: trying disable_secure_layers")
        val disableSecure = tryWithSecureDisabled(output)
        if (disableSecure != null) {
            addWatermark(disableSecure)
            return CaptureResult.Success(disableSecure)
        }

        // 方案2: force_no_secure_buffers（部分 AOSP/ROM支持）
        Log.i(TAG, "Deep: trying force_no_secure_buffers")
        val forceNoSecure = tryForceNoSecureBuffers(output)
        if (forceNoSecure != null) {
            addWatermark(forceNoSecure)
            return CaptureResult.Success(forceNoSecure)
        }

        // 方案3: SYSRQ 硬件截图键→ 触发内核级截图保存到 /sdcard/Pictures
        Log.i(TAG, "Deep: trying SYSRQ hardware snapshot")
        val sysrq = trySysrqCapture(outputDir, ts)
        if (sysrq != null) {
            addWatermark(sysrq)
            return CaptureResult.Success(sysrq)
        }

        // 方案4: fb0 直读（外挂仅 hook screencap 而未 hook fb0 驱动时有效）
        Log.i(TAG, "Deep: trying fb0")
        val fb0 = captureFb0(outputDir, ts)
        if (fb0 != null) {
            addWatermark(fb0)
            return CaptureResult.Success(fb0)
        }

        // 方案5: DRM GPU 输出缓冲
        Log.i(TAG, "Deep: trying DRM")
        val drm = captureDrm(outputDir, ts)
        if (drm != null) {
            addWatermark(drm)
            return CaptureResult.Success(drm)
        }

        return CaptureResult.Error("深度截图失败：外挂保护层无法绕过，请尝试关闭外挂后再截图")
    }

    /**
     * 方案1：临时禁用 SurfaceFlinger secure layer 保护
     * 设置 prop → screencap →恢复 prop
     */
    private fun tryWithSecureDisabled(output: File): File? {
        return try {
            // 保存原始值
            val origDisable = shell("getprop debug.sf.disable_secure_layers").out.trim()
            val origForce = shell("getprop debug.force.no.secure.buffers").out.trim()

            // 禁用保护
            shell("setprop debug.sf.disable_secure_layers 1")
            shell("setprop debug.force.no.secure.buffers 1")
            // 等待 SF 响应（约一帧时间）
            Thread.sleep(100)

            // 截图
            val r = shell("screencap -p ${output.absolutePath} && chmod 644 ${output.absolutePath}")

            // 恢复原始值
            shell("setprop debug.sf.disable_secure_layers ${origDisable.ifEmpty { "0" }}")
            shell("setprop debug.force.no.secure.buffers ${origForce.ifEmpty { "0" }}")

            if (r.ok && output.exists() && output.length() > 500) {
                Log.i(TAG, "disable_secure_layers OK: ${output.length()} bytes")
                output
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "disable_secure_layers failed: ${e.message}")
            null
        }
    }

    /**
     * 方案2：通过 persist prop 强制关闭 secure buffer
     */
    private fun tryForceNoSecureBuffers(output: File): File? {
        return try {
            // 额外 prop：hwc 级别禁用保护
            shell("setprop persist.debug.force_no_secure_buffers 1")
            shell("setprop vendor.gralloc.disable_protected_buffers 1")
            Thread.sleep(80)
            val r = shell("screencap -p ${output.absolutePath} && chmod 644 ${output.absolutePath}")
            // 恢复
            shell("setprop persist.debug.force_no_secure_buffers 0")
            shell("setprop vendor.gralloc.disable_protected_buffers 0")

            if (r.ok && output.exists() && output.length() > 500) output else null
        } catch (e: Exception) { null }
    }

    /**
     * 方案3：SYSRQ 内核快照（P键触发屏幕截图写入 /sdcard/Pictures/）
     * 某些内核编译时开启了 CONFIG_MAGIC_SYSRQ，此方式完全绕过 Android 框架层
     */
    private fun trySysrqCapture(outputDir: File, ts: String): File? {
        return try {
            // 触发 SYSRQ-P（打印进程状态，部分 ROM 实现了屏幕截图）
            val beforeFiles = outputDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            shell("echo p > /proc/sysrq-trigger 2>/dev/null || input keyevent 120")
            Thread.sleep(1500)

            // SYSRQ 截图通常保存到标准图片目录，查找新增文件
            val screenshotDirs = listOf(
                "/sdcard/Pictures",
                "/sdcard/DCIM/Screenshots",
                "/sdcard/Pictures/Screenshots",
                outputDir.absolutePath
            )
            for (dir in screenshotDirs) {
                val f = File(dir)
                if (!f.exists()) continue
                val newFile = f.listFiles()?.filter { it.name !in beforeFiles && it.name.endsWith(".png") }
                    ?.maxByOrNull { it.lastModified() }
                if (newFile != null && newFile.length() > 10000) {
                    // 移到输出目录
                    val dest = File(outputDir, "shot_sysrq_$ts.png")
                    newFile.copyTo(dest, overwrite = true)
                    Log.i(TAG, "SYSRQ capture OK: ${dest.absolutePath}")
                    return dest
                }
            }
            null
        } catch (e: Exception) { Log.w(TAG, "SYSRQ failed: ${e.message}"); null }
    }

    /** DRM framebuffer 读取（/dev/dri/card0） */
    private fun captureDrm(dir: File, ts: String): File? {
        return try {
            val raw = File(dir, "drm_$ts.raw")
            // 用 dd 读取 DRM 显示缓冲
            val r = shell("dd if=/dev/dri/card0 bs=4096 count=2048 of=${raw.absolutePath} 2>/dev/null; chmod 644 ${raw.absolutePath}")
            if (!raw.exists() || raw.length() < 100_000L) { raw.delete(); return null }
            val res = getScreenRes()
            val w = res.first; val h = res.second
            if (w <= 0) { raw.delete(); return null }
            val bytes = raw.readBytes(); raw.delete()
            // DRM 通常是 BGRA 格式
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            for (i in px.indices) {
                val b = i * 4
                if (b + 3 >= bytes.size) break
                val blu = bytes[b].toInt() and 0xFF
                val grn = bytes[b + 1].toInt() and 0xFF
                val red = bytes[b + 2].toInt() and 0xFF
                val alp = bytes[b + 3].toInt() and 0xFF
                px[i] = (alp shl 24) or (red shl 16) or (grn shl 8) or blu
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            val out = File(dir, "shot_drm_$ts.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            if (out.exists() && out.length() > 1000) out else null
        } catch (e: Exception) { Log.w(TAG, "DRM capture failed: ${e.message}"); null }
    }

    private fun captureFb0(dir: File, ts: String): File? {
        return try {
            val raw = File(dir, "fb0_$ts.raw")
            val r = shell("cat /dev/graphics/fb0 > ${raw.absolutePath} && chmod 644 ${raw.absolutePath}")
            if (!r.ok || !raw.exists() || raw.length() < 10_000L) { raw.delete(); return null }

            val res = getScreenRes()
            val w = res.first; val h = res.second
            if (w <= 0 || h <= 0) { raw.delete(); return null }

            val bytes = raw.readBytes(); raw.delete()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            for (i in px.indices) {
                val b = i * 4
                if (b + 3 >= bytes.size) break
                val red = bytes[b].toInt() and 0xFF
                val grn = bytes[b + 1].toInt() and 0xFF
                val blu = bytes[b + 2].toInt() and 0xFF
                val alp = bytes[b + 3].toInt() and 0xFF
                px[i] = (alp shl 24) or (red shl 16) or (grn shl 8) or blu
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            val out = File(dir, "shot_fb0_$ts.png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            if (out.exists()) out else null
        } catch (e: Exception) { Log.w(TAG, "fb0 failed: ${e.message}"); null }
    }

    private fun getScreenRes(): Pair<Int, Int> {
        val r = shell("wm size")
        val m = Regex("""(\d+)x(\d+)""").find(r.out)
        return if (m != null) Pair(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        else Pair(1080, 2400)
    }

    // ── 录屏 ───────────────────────────────────────────────────

    private val recordingLock = Any()
    @Volatile private var recProc: Process? = null
    @Volatile private var recFile: File? = null
    @Volatile private var recordingActive = false
    @Volatile private var stopRequested = false
    private var recordingWorker: Thread? = null
    private val recSegments = mutableListOf<File>()
    private val _automaticRecordingResult = MutableSharedFlow<File?>(extraBufferCapacity = 1)
    val automaticRecordingResult = _automaticRecordingResult.asSharedFlow()
    @Volatile var lastRecordingAnalysis: RecordingAnalysis? = null
        private set

    val isRecording: Boolean get() = recordingActive

        /**
     * Bug1 修复：新增 fps 参数，screenrecord 支持 --time-limit 和帧率控制。
     * screenrecord 本身不直接支持 --fps，但通过 --bit-rate 配合分辨率可间接控制质量。
     * 对于 fps 限制，使用 --bugreport 标志在某些设备上有效，
     * 更通用方案是通过 wm density 降低采样或直接传参（Android 10+ screenrecord 支持 --size）。
     * 此处采用标准参数组合，fps 通过注释传递给日志便于排查。
     */
    /**
 * 录屏音频源。
 * - NONE：静音（默认，兼容所有 ROM）
 * - INTERNAL：系统内录，Android 10+ + 支持内录的 ROM（AOSP 14 screenrecord 支持）
 * - MIC：麦克风外录
 */
enum class AudioSource(val cliArg: String?) {
    NONE(null),
    INTERNAL("internal"),
    MIC("mic")
}

suspend fun startRecording(
    outputDir: File,
    bitrateMbps: Int = 8,
    fps: Int = 30,
    audio: AudioSource = AudioSource.NONE,
    useCompatSize: Boolean = isXiaomiTablet()
): RecordResult = withContext(Dispatchers.IO) {
    if (isRecording) return@withContext RecordResult.AlreadyRunning
    outputDir.mkdirs()
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val mp4 = File(outputDir, "rec_$ts.mp4")
    val firstSegment = File(outputDir, "rec_${ts}_part001.mp4")
    try {
        val effectiveBitrate = if (fps >= 60) bitrateMbps * 1_500_000 else bitrateMbps * 1_000_000
        val audioArg = audio.cliArg?.let { " --audio-source $it" } ?: ""
        // 小米高分辨率/高刷平板原生录屏可达到 3200x2136@120，部分编码器会掉帧或异常。
        // 1920x1280 保持 3:2 比例，同时显著降低 H.264 编码压力。
        val sizeArg = if (useCompatSize) " --size 1920x1280" else ""
        val cmd = "screenrecord --bit-rate $effectiveBitrate --time-limit ${RecordingPolicy.SEGMENT_SECONDS}$sizeArg$audioArg ${shellQuote(firstSegment.absolutePath)}"
        Log.i(TAG, "StartRecording cmd=$cmd")
        AppLogger.log(AppLogger.Category.RECORDING, "录屏命令", "设备=${Build.MANUFACTURER} ${Build.MODEL}; 兼容分辨率=$useCompatSize; cmd=$cmd")
        val p = ProcessBuilder("su", "-c", cmd).apply { redirectErrorStream(false) }.start()
        Thread.sleep(800)
        if (!p.isAlive) {
            val err = p.errorStream.bufferedReader().readText()
            firstSegment.delete()
            val unsupported = err.contains("unrecognized", true) || err.contains("unknown", true) ||
                err.contains("invalid", true) || err.contains("unsupported", true) || err.isEmpty()
            if (audio != AudioSource.NONE && unsupported) {
                Log.w(TAG, "audio-source not supported, retrying without audio: $err")
                return@withContext startRecording(outputDir, bitrateMbps, fps, AudioSource.NONE, useCompatSize)
            }
            if (useCompatSize && unsupported) {
                Log.w(TAG, "compat size not supported, retrying native size: $err")
                return@withContext startRecording(outputDir, bitrateMbps, fps, audio, false)
            }
            return@withContext RecordResult.Error("录屏启动失败: ${err.take(180)}")
        }
        synchronized(recordingLock) {
            recFile = mp4
            recSegments.clear()
            recSegments += firstSegment
            recProc = p
            stopRequested = false
            recordingActive = true
        }
        drainProcessOutput(p, 1)
        recordingWorker = Thread({
            monitorSegmentedRecording(outputDir, ts, effectiveBitrate, sizeArg, audioArg, System.nanoTime())
        }, "BainingRecordingRoller").apply { isDaemon = true; start() }
        RecordResult.Started(mp4)
    } catch (e: Exception) {
        synchronized(recordingLock) {
            recProc = null
            recFile = null
            recordingActive = false
            stopRequested = false
            recSegments.clear()
        }
        RecordResult.Error(e.message ?: "未知错误")
    }
}

private fun monitorSegmentedRecording(
    outputDir: File,
    timestamp: String,
    effectiveBitrate: Int,
    sizeArg: String,
    audioArg: String,
    startedAtNanos: Long
) {
    var part = 1
    try {
        while (recordingActive && !stopRequested) {
            val current = recProc ?: break
            current.waitFor()
            if (stopRequested || !recordingActive) break
            val elapsedSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000L
            if (elapsedSeconds >= RecordingPolicy.MAX_SECONDS) break
            val remainingSeconds = (RecordingPolicy.MAX_SECONDS - elapsedSeconds)
                .coerceAtMost(RecordingPolicy.SEGMENT_SECONDS.toLong())
                .coerceAtLeast(1L)
            part++
            val next = File(outputDir, "rec_${timestamp}_part${part.toString().padStart(3, '0')}.mp4")
            val cmd = "screenrecord --bit-rate $effectiveBitrate --time-limit $remainingSeconds$sizeArg$audioArg ${shellQuote(next.absolutePath)}"
            val process = ProcessBuilder("su", "-c", cmd).apply { redirectErrorStream(false) }.start()
            Thread.sleep(600)
            if (!process.isAlive) {
                val error = process.errorStream.bufferedReader().readText().take(240)
                AppLogger.log(AppLogger.Category.ERROR, "长录屏续段失败", "分段=$part; $error")
                next.delete()
                break
            }
            synchronized(recordingLock) {
                recSegments += next
                recProc = process
            }
            drainProcessOutput(process, part)
            AppLogger.log(AppLogger.Category.RECORDING, "长录屏自动续段", "分段=$part; 已录制约 ${elapsedSeconds}s")
        }
    } catch (e: Exception) {
        AppLogger.log(AppLogger.Category.ERROR, "长录屏续段异常", e.message.orEmpty(), e)
    } finally {
        if (!stopRequested) {
            recordingActive = false
            RecordingStateHolder.setRecording(false)
            val parts = synchronized(recordingLock) {
                recSegments.filter { it.exists() && it.length() > 4096L }.toList()
            }
            val finished = recFile?.let { finalizeSegments(parts, it) }
            lastRecordingAnalysis = finished?.let { analyzeRecordingBlackFrames(it) }
            synchronized(recordingLock) {
                recProc = null
                recFile = null
                recSegments.clear()
            }
            _automaticRecordingResult.tryEmit(finished)
            AppLogger.log(AppLogger.Category.RECORDING, "长录屏自动结束", "达到 10 小时上限或录制进程结束；文件=${finished?.absolutePath.orEmpty()}")
        }
    }
}

private fun drainProcessOutput(process: Process, part: Int) {
    Thread({ process.inputStream.bufferedReader().use { it.readText() } }, "BainingRecOut-$part").apply { isDaemon = true; start() }
    Thread({
        val error = process.errorStream.bufferedReader().use { it.readText() }
        if (error.isNotBlank()) Log.d(TAG, "screenrecord part=$part: ${error.take(500)}")
    }, "BainingRecErr-$part").apply { isDaemon = true; start() }
}

private fun isXiaomiTablet(): Boolean {
    val maker = Build.MANUFACTURER.lowercase(Locale.ROOT)
    val identity = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT)
        .joinToString(" ").lowercase(Locale.ROOT)
    val characteristics = try {
        ProcessBuilder("getprop", "ro.build.characteristics").start()
            .inputStream.bufferedReader().readText().trim().lowercase(Locale.ROOT)
    } catch (_: Exception) { "" }
    return (maker.contains("xiaomi") || maker.contains("redmi")) &&
        (identity.contains("pad") || identity.contains("tablet") || characteristics.contains("tablet"))
}

    suspend fun stopRecording(): File? = withContext(Dispatchers.IO) {
        if (!recordingActive && recSegments.isEmpty()) return@withContext null
        stopRequested = true
        recordingActive = false
        val p = recProc
        val output = recFile
        try {
            // 只停止本应用启动的 screenrecord，避免影响设备上的其他录制任务。
            if (p != null) {
                val marker = output?.nameWithoutExtension ?: "rec_"
                shell("pkill -2 -f ${shellQuote("screenrecord.*$marker")} 2>/dev/null || true")
            }
            val exited = p?.waitFor(8, TimeUnit.SECONDS) ?: true
            if (!exited) {
                Log.w(TAG, "screenrecord SIGINT timeout, trying SIGTERM")
                val marker = output?.nameWithoutExtension ?: "rec_"
                shell("pkill -15 -f ${shellQuote("screenrecord.*$marker")} 2>/dev/null || true")
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            }
            recordingWorker?.join(2000)
            Thread.sleep(400)
        } catch (e: Exception) {
            Log.w(TAG, "stopRecording: ${e.message}")
        }
        val parts = synchronized(recordingLock) {
            recSegments.filter { it.exists() && it.length() > 4096L }.toList()
        }
        val valid = output?.let { finalizeSegments(parts, it) }
            ?.takeIf { it.exists() && it.length() > 4096L }
        synchronized(recordingLock) {
            recProc = null
            recFile = null
            recordingWorker = null
            recSegments.clear()
            stopRequested = false
        }
        lastRecordingAnalysis = valid?.let { analyzeRecordingBlackFrames(it) }
        valid
    }

    private fun finalizeSegments(parts: List<File>, output: File): File? {
        if (parts.isEmpty()) return null
        return try {
            if (output.exists()) output.delete()
            if (parts.size == 1) {
                if (!parts.first().renameTo(output)) parts.first().copyTo(output, overwrite = true)
            } else {
                mergeMp4Segments(parts, output)
            }
            if (output.exists() && output.length() > 4096L) {
                parts.filter { it.absolutePath != output.absolutePath }.forEach { it.delete() }
                output
            } else null
        } catch (e: Exception) {
            AppLogger.log(AppLogger.Category.ERROR, "长录屏合并失败", e.message.orEmpty(), e)
            // 合并失败时保留分段，确保已录内容不会丢失。
            parts.firstOrNull()
        }
    }

    private fun mergeMp4Segments(parts: List<File>, output: File) {
        val first = MediaExtractor()
        first.setDataSource(parts.first().absolutePath)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTracks = mutableMapOf<String, Int>()
        for (track in 0 until first.trackCount) {
            val format = first.getTrackFormat(track)
            val kind = format.getString(MediaFormat.KEY_MIME).orEmpty().substringBefore('/')
            if (kind == "video" || kind == "audio") outputTracks[kind] = muxer.addTrack(format)
        }
        val rotation = MediaMetadataRetriever().run {
            try {
                setDataSource(parts.first().absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally { release() }
        }
        if (rotation != 0) muxer.setOrientationHint(rotation)
        first.release()
        muxer.start()
        var segmentOffsetUs = 0L
        val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
        try {
            parts.forEach { part ->
                val extractor = MediaExtractor()
                extractor.setDataSource(part.absolutePath)
                var observedDurationUs = 0L
                for (track in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(track)
                    val kind = format.getString(MediaFormat.KEY_MIME).orEmpty().substringBefore('/')
                    val outputTrack = outputTracks[kind] ?: continue
                    extractor.selectTrack(track)
                    var firstPtsUs = -1L
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val sampleTime = extractor.sampleTime
                        if (firstPtsUs < 0L) firstPtsUs = sampleTime
                        val relativePts = (sampleTime - firstPtsUs).coerceAtLeast(0L)
                        val extractorFlags = extractor.sampleFlags
                        var codecFlags = 0
                        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                        }
                        val info = MediaCodec.BufferInfo().apply {
                            offset = 0
                            this.size = size
                            presentationTimeUs = segmentOffsetUs + relativePts
                            flags = codecFlags
                        }
                        muxer.writeSampleData(outputTrack, buffer, info)
                        observedDurationUs = maxOf(observedDurationUs, relativePts)
                        extractor.advance()
                    }
                    extractor.unselectTrack(track)
                }
                extractor.release()
                val metadataDurationUs = MediaMetadataRetriever().run {
                    try {
                        setDataSource(part.absolutePath)
                        (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
                    } finally { release() }
                }
                segmentOffsetUs += maxOf(metadataDurationUs, observedDurationUs + 33_333L)
            }
        } finally {
            try { muxer.stop() } finally { muxer.release() }
        }
    }

    /**
     * 每秒抽样一帧，识别连续黑屏。该检测只分析本应用刚生成的视频，避免保存成功却
     * 实际包含长时间黑帧。它不能、也不会尝试绕过受保护图层。
     */
    private fun analyzeRecordingBlackFrames(file: File): RecordingAnalysis {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val blackSeconds = mutableListOf<Int>()
            val totalSeconds = (durationMs / 1000L).toInt().coerceAtLeast(1)
            // 长录屏最多均匀抽样 120 帧，避免 10 小时视频结束后逐秒扫描造成长时间卡顿。
            val sampleSeconds = if (totalSeconds <= 120) {
                (0 until totalSeconds).toList()
            } else {
                (0 until 120).map { it * (totalSeconds - 1) / 119 }.distinct()
            }
            for (second in sampleSeconds) {
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        second * 1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        320,
                        240
                    )
                } else {
                    retriever.getFrameAtTime(second * 1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } ?: continue
                if (isMostlyBlackFrame(frame)) blackSeconds += second
                frame.recycle()
            }
            val sampleGapSeconds = if (sampleSeconds.size < 2) 1 else
                (sampleSeconds[1] - sampleSeconds[0]).coerceAtLeast(1)
            val ranges = consecutiveRanges(blackSeconds, sampleGapSeconds)
            val result = RecordingAnalysis(durationMs, sampleSeconds.size, blackSeconds.size, ranges)
            if (ranges.isNotEmpty()) {
                AppLogger.log(
                    AppLogger.Category.ERROR,
                    "录屏检测到黑屏区间",
                    "文件=${file.absolutePath}; 时长=${durationMs}ms; 黑帧抽样=${blackSeconds.size}/${sampleSeconds.size}; 区间=${ranges.joinToString()}。" +
                        "视频文件和编码器正常，但捕获源在这些时段返回黑帧；常见于受保护或独立合成图层。"
                )
            } else {
                AppLogger.log(AppLogger.Category.RECORDING, "录屏黑帧检测通过", "抽样=${sampleSeconds.size} 帧，未发现连续黑屏")
            }
            result
        } catch (e: Exception) {
            AppLogger.log(AppLogger.Category.ERROR, "录屏黑帧检测失败", file.absolutePath, e)
            RecordingAnalysis(0L, 0, 0, emptyList())
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun isMostlyBlackFrame(bitmap: Bitmap): Boolean {
        val cols = 20
        val rows = 14
        var black = 0
        var sampled = 0
        for (row in 0 until rows) {
            val y = ((row + 0.5f) * bitmap.height / rows).toInt().coerceIn(0, bitmap.height - 1)
            for (col in 0 until cols) {
                val x = ((col + 0.5f) * bitmap.width / cols).toInt().coerceIn(0, bitmap.width - 1)
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                if (r < 18 && g < 18 && b < 18) black++
                sampled++
            }
        }
        return sampled > 0 && black.toFloat() / sampled >= 0.94f
    }

    private fun consecutiveRanges(values: List<Int>, adjacencySeconds: Int = 1): List<String> {
        if (values.isEmpty()) return emptyList()
        val ranges = mutableListOf<String>()
        var start = values.first()
        var end = start
        for (value in values.drop(1)) {
            if (value <= end + adjacencySeconds) end = value else {
                if (end > start || adjacencySeconds > 1) ranges += "${start}s–${end + adjacencySeconds}s"
                start = value; end = value
            }
        }
        if (end > start || adjacencySeconds > 1) ranges += "${start}s–${end + adjacencySeconds}s"
        return ranges
    }

    data class RecordingAnalysis(
        val durationMs: Long,
        val sampledSeconds: Int,
        val blackSamples: Int,
        val blackRanges: List<String>
    )

    // ── 悬浮窗合成 ───────────────────────────────────────────────

    /**
     * 部分 ROM 的 screencap 会有意排除 TYPE_APPLICATION_OVERLAY。
     * 截图完成后将本应用悬浮栏的视觉副本合成到成品图，保证截图结果一致。
     * 这只绘制本应用的四个圆形控件，不读取或修改其他应用窗口。
     */
    fun composeFloatingControls(file: File, recording: Boolean) {
        try {
            val source = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(bitmap)
            val scale = bitmap.width / 1080f
            val radius = (26f * scale).coerceAtLeast(18f)
            val gap = radius * 2.42f
            val cx = bitmap.width - (48f * scale).coerceAtLeast(radius + 8f)
            val firstY = (245f * scale).coerceAtLeast(radius + 8f)
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(70, 0, 0, 0) }
            val colors = intArrayOf(
                android.graphics.Color.rgb(0, 122, 255),
                if (recording) android.graphics.Color.rgb(255, 59, 48) else android.graphics.Color.rgb(52, 199, 89),
                android.graphics.Color.rgb(90, 90, 100),
                android.graphics.Color.rgb(130, 130, 140)
            )
            val labels = arrayOf("□", if (recording) "Ⅱ" else "●", "↔", "×")
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = radius * 1.05f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            colors.indices.forEach { index ->
                val cy = firstY + gap * index
                canvas.drawCircle(cx + radius * .12f, cy + radius * .14f, radius, shadow)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[index] }
                canvas.drawCircle(cx, cy, radius, fill)
                val baseline = cy - (text.ascent() + text.descent()) / 2f
                canvas.drawText(labels[index], cx, baseline, text)
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            source.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Compose floating controls failed: ${e.message}")
        }
    }

    // ── 水印 ───────────────────────────────────────────────────
 
    /**
     * 在图片右下角添加半透明水印：白柠 · @bnstr
     */
    fun addWatermark(file: File) {
        try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)
            val w = mutable.width.toFloat()
            val h = mutable.height.toFloat()
                        val textSize = (w * 0.018f).coerceIn(14f, 36f)  // 缩小水印
            val text = "白柠 · @bnstr"

            val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(220, 255, 255, 255)
                this.textSize = textSize
                typeface = Typeface.DEFAULT_BOLD
            }
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(120, 0, 0, 0)
            }
            val padding = textSize * 0.6f
            val textWidth = txtPaint.measureText(text)
            val rx = w - textWidth - padding * 2 - 20f
            val ry = h - textSize - padding * 2 - 20f
            val rw = textWidth + padding * 2
            val rh = textSize + padding * 2

            canvas.drawRoundRect(rx, ry, rx + rw, ry + rh, rh / 2, rh / 2, bgPaint)
            canvas.drawText(text, rx + padding, ry + textSize + padding * 0.4f, txtPaint)

            file.outputStream().use { mutable.compress(Bitmap.CompressFormat.PNG, 100, it) }
            mutable.recycle()
            bmp.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Watermark failed: ${e.message}")
        }
    }

// ── Native DRM 工具 ──────────────────────────────────────────

    /**
     * 调用打包的 native drm_capture 工具直接读取 GPU帧缓冲
     * 工具从 assets 解压到 /data/data/.../cache/drm_capture 并执行
     */
    fun captureGpu(context: android.content.Context, outputDir: File): CaptureResult {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val ppm = File(outputDir, "gpu_$ts.ppm")
            val png = File(outputDir, "shot_gpu_$ts.png")

            // 1. 解压 native 二进制到 cache 目录
            val binFile = extractNativeBin(context) ?: return CaptureResult.Error("无法解压 drm_capture")

            // 2. 以 root 执行
            val r = shell("${binFile.absolutePath} ${ppm.absolutePath}; chmod 644 ${ppm.absolutePath}")
            Log.i(TAG, "drm_capture exit=${r.ok} stderr=${r.err.take(200)}")

            if (!ppm.exists() || ppm.length() < 1000) {
                ppm.delete()
                return CaptureResult.Error("GPU 截图失败: ${r.err.take(150)}")
            }

            // 3. PPM → PNG（用 BitmapFactory 解码 PPM）
            val bmp = decodePpm(ppm)
            ppm.delete()
            if (bmp == null) return CaptureResult.Error("PPM 解码失败")

            png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            if (png.exists() && png.length() > 500) {
                                addWatermark(png)
                Log.i(TAG, "GPU capture OK: ${png.absolutePath}")
                CaptureResult.Success(png)
            } else {
                CaptureResult.Error("PNG 写入失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureGpu exception", e)
            CaptureResult.Error(e.message ?: "GPU 截图异常")
        }
    }

    private fun extractNativeBin(context: android.content.Context): File? {
        return try {
            val bin = File(context.cacheDir, "drm_capture")
            // 每次检查 assets 版本是否更新（比较大小）
            val assetSize = context.assets.open("drm_capture").use { it.available().toLong() }
            if (!bin.exists() || bin.length() != assetSize) {
                context.assets.open("drm_capture").use { input ->
                    bin.outputStream().use { out -> input.copyTo(out) }
                }
                // chmod +x
                shell("chmod 755 ${bin.absolutePath}")
            }
            bin
        } catch (e: Exception) {
            Log.e(TAG, "extractNativeBin failed: ${e.message}")
            null
        }
    }

    /**
     * 解码 PPM (P6) 格式到 Bitmap
     * BitmapFactory 不支持 PPM，手动解析
     */
    private fun decodePpm(file: File): Bitmap? {
        return try {
            val fis = file.inputStream()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(fis))
            val magic = reader.readLine()?.trim()
            if (magic != "P6") { fis.close(); return null }
            //跳过注释行
            var line = reader.readLine()?.trim() ?: ""
            while (line.startsWith("#")) line = reader.readLine()?.trim() ?: ""
            val (wStr, hStr) = line.split(" ")
            val w = wStr.toInt(); val h = hStr.toInt()
            reader.readLine() // maxval "255"
            // 关闭 reader，用InputStream 读剩余二进制
            fis.close()

            // 重新打开跳过 header
            val raw = file.readBytes()
            // 找到 "255\n" 后的像素数据起始位置
            var headerEnd = 0
            var newlineCount = 0
            for (i in raw.indices) {
                if (raw[i] == '\n'.code.toByte()) {
                    newlineCount++
                    if (newlineCount == 3) { headerEnd = i + 1; break }
                }
            }

            val pixels = IntArray(w * h)
            val dataStart = headerEnd
            for (i in pixels.indices) {
                val base = dataStart + i * 3
                if (base + 2 >= raw.size) break
                val r = raw[base].toInt() and 0xFF
                val g = raw[base + 1].toInt() and 0xFF
                val b = raw[base + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "decodePpm failed: ${e.message}")
            null
        }
    }

    // ── Shell 工具 ─────────────────────────────────────────────

    fun shell(cmd: String): ShellOut {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).apply { redirectErrorStream(false) }.start()
            val exited = p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!exited) { p.destroyForcibly(); return ShellOut(false, "", "timeout") }
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            ShellOut(p.exitValue() == 0, out, err)
        } catch (e: IOException) { ShellOut(false, "", e.message ?: "") }
    }

    // ── 数据类─────────────────────────────────────────────────

    data class ShellOut(val ok: Boolean, val out: String, val err: String)

    sealed class CaptureResult {
        data class Success(val file: File) : CaptureResult()
        data class Error(val message: String) : CaptureResult()
    }

    sealed class RecordResult {
        data class Started(val file: File) : RecordResult()
        object AlreadyRunning : RecordResult()
        data class Error(val message: String) : RecordResult()
    }
}
