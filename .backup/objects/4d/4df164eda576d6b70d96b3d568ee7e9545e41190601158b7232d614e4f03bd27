package com.baining.str.manager

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用文件日志。日志只记录本应用截图、录屏、设置和诊断事件，不采集其他应用数据。
 */
object AppLogger {
    const val LOG_DIR_PATH = "/storage/emulated/0/Download/白柠截图日志"

    enum class Category(val filePrefix: String) {
        SCREENSHOT("截图"), RECORDING("录制"), SETTINGS("设置"), DIAGNOSTIC("诊断"), ERROR("错误")
    }

    private val lock = Any()
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    @Volatile private var enabled = false
    @Volatile private var initialized = false

    val directory: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "白柠截图日志")

    fun initialize(context: Context) {
        enabled = context.getSharedPreferences("bn_prefs", Context.MODE_PRIVATE)
            .getBoolean("log_enabled", false)
        ensureDirectory()
        if (!initialized) {
            initialized = true
            if (enabled) {
                writeInternal(
                    Category.SETTINGS,
                    "应用启动",
                    "app=${context.packageName}; device=${Build.MANUFACTURER} ${Build.MODEL}; " +
                        "android=${Build.VERSION.RELEASE}; sdk=${Build.VERSION.SDK_INT}"
                )
            }
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        ensureDirectory()
        context.getSharedPreferences("bn_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("log_enabled", value).apply()
        val wasEnabled = enabled
        enabled = value
        if (value) writeInternal(Category.SETTINGS, "日志开关", if (wasEnabled) "保持开启" else "已开启")
    }

    fun isEnabled(): Boolean = enabled

    fun log(category: Category, event: String, details: String = "", error: Throwable? = null) {
        if (!enabled) return
        val errorText = error?.let { "${it.javaClass.simpleName}: ${it.message}\n${it.stackTraceToString()}" }.orEmpty()
        writeInternal(category, event, listOf(details, errorText).filter { it.isNotBlank() }.joinToString("\n"))
        if (error != null || category == Category.ERROR) {
            writeInternal(Category.ERROR, event, listOf(details, errorText).filter { it.isNotBlank() }.joinToString("\n"))
        }
    }

    /** 主动自检即使关闭持续日志也会落盘，便于用户导出故障信息。 */
    fun diagnostic(event: String, details: String) {
        ensureDirectory()
        writeInternal(Category.DIAGNOSTIC, event, details)
    }

    fun readLatest(maxChars: Int = 30_000): String {
        val file = File(directory, "all.log")
        if (!file.exists()) return "暂无日志。\n日志目录：${directory.absolutePath}"
        return try {
            val text = file.readText()
            if (text.length <= maxChars) text else "…仅显示最后 ${maxChars} 个字符…\n" + text.takeLast(maxChars)
        } catch (e: Exception) {
            "读取日志失败：${e.message}"
        }
    }

    private fun ensureDirectory(): Boolean = try {
        directory.exists() || directory.mkdirs()
    } catch (_: Exception) { false }

    private fun writeInternal(category: Category, event: String, details: String) {
        synchronized(lock) {
            if (!ensureDirectory()) return
            val now = Date()
            val line = buildString {
                append('[').append(timeFormat.format(now)).append("] [")
                    .append(category.name).append("] ").append(event)
                if (details.isNotBlank()) append("\n").append(details.trim())
                append("\n\n")
            }
            try {
                File(directory, "all.log").appendText(line)
                File(directory, "${category.filePrefix}_${dayFormat.format(now)}.log").appendText(line)
            } catch (_: Exception) {
                // 文件日志不能反向影响截图/录屏主流程。
            }
        }
    }
}
