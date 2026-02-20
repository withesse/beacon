package com.withesse.beacon.export

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Debug
import com.withesse.beacon.Beacon
import com.withesse.beacon.log.LogEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Device info collector — exported alongside log zip.
 * 设备信息采集 — 随日志 zip 一起导出。
 */
object DeviceInfoCollector {

    fun collect(context: Context): String = buildString {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        appendLine("========================================")
        appendLine("        Beacon Log Export v${Beacon.VERSION}")
        appendLine("        $now")
        appendLine("========================================")
        appendLine()

        try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            appendLine("[App]")
            appendLine("  Package:  ${context.packageName}")
            appendLine("  Version:  ${pkg.versionName} (${getVersionCode(pkg)})")
            appendLine("  Debug:    ${isDebug(context)}")
            appendLine()
        } catch (_: Exception) {}

        appendLine("[Device]")
        appendLine("  Brand:   ${Build.BRAND}")
        appendLine("  Model:   ${Build.MODEL}")
        appendLine("  OS:      Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("  CPU:     ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()

        val rt = Runtime.getRuntime()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        appendLine("[Memory]")
        appendLine("  Java Heap:    ${(rt.totalMemory() - rt.freeMemory()) / 1024 / 1024}MB / ${rt.maxMemory() / 1024 / 1024}MB")
        appendLine("  Native:       ${Debug.getNativeHeapAllocatedSize() / 1024 / 1024}MB")
        am?.let {
            val memInfo = ActivityManager.MemoryInfo()
            it.getMemoryInfo(memInfo)
            appendLine("  System Total: ${memInfo.totalMem / 1024 / 1024}MB")
            appendLine("  System Avail: ${memInfo.availMem / 1024 / 1024}MB")
        }
        appendLine()

        val cfg = Beacon.config
        appendLine("[Beacon Config]")
        appendLine("  logLevel:    ${levelName(cfg.logLevel)}")
        appendLine("  console:     ${cfg.consoleEnabled}")
        appendLine("  file:        ${cfg.fileEnabled}")
        appendLine("  crash:       ${cfg.crashEnabled}")
        appendLine("  startup:     ${cfg.startupEnabled}")
        appendLine("  fps:         ${cfg.fpsEnabled}")
        appendLine("  memory:      ${cfg.memoryEnabled}")
        appendLine("  retain:      ${cfg.maxRetainDays}d / ${cfg.maxTotalSizeMB}MB")
        appendLine()

        try {
            val dataDir = context.filesDir
            appendLine("[Disk]")
            appendLine("  Internal Total: ${dataDir.totalSpace / 1024 / 1024}MB")
            appendLine("  Internal Avail: ${dataDir.usableSpace / 1024 / 1024}MB")
            appendLine()
        } catch (_: Exception) {}

        try {
            val logDir = File(LogEngine.logDir)
            if (logDir.exists()) {
                val files = logDir.walkTopDown().filter { it.isFile }.toList()
                val totalSize = files.sumOf { it.length() }
                appendLine("[Log Storage]")
                appendLine("  Files: ${files.size}")
                appendLine("  Size:  ${"%.2f".format(totalSize / 1024.0 / 1024.0)}MB")
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun getVersionCode(pkg: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            pkg.versionCode.toLong()
        }
    }

    private fun isDebug(context: Context): Boolean {
        return try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) { false }
    }

    private fun levelName(level: Int): String = when (level) {
        android.util.Log.VERBOSE -> "VERBOSE"
        android.util.Log.DEBUG   -> "DEBUG"
        android.util.Log.INFO    -> "INFO"
        android.util.Log.WARN    -> "WARN"
        android.util.Log.ERROR   -> "ERROR"
        else -> "UNKNOWN($level)"
    }
}
