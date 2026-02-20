package com.withesse.beacon.apm

import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.withesse.beacon.log.BLog

/**
 * Startup timing monitor.
 * 启动耗时监控。
 *
 * Call timing | 调用时机:
 *   Application.attachBaseContext → StartupTracer.markProcessStart()
 *   Application.onCreate          → StartupTracer.markAppCreate()
 *   First Activity.onResume       → auto-marked (internal) | 自动标记（SDK 内部处理）
 *   First meaningful content      → StartupTracer.markFullyDrawn() | 首页数据加载完成
 */
object StartupTracer {

    private var processStart = 0L
    private var appCreate = 0L
    private var reported = false

    fun markProcessStart() {
        processStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Process.getStartElapsedRealtime()
        } else {
            SystemClock.elapsedRealtime()
        }
    }

    fun markAppCreate() {
        appCreate = SystemClock.elapsedRealtime()
    }

    fun markFirstFrame() {
        if (reported) return
        if (processStart == 0L) {
            BLog.w("Startup", "markProcessStart() was not called, skipping startup trace")
            return
        }
        reported = true

        val now = SystemClock.elapsedRealtime()
        val total = now - processStart
        val appInit = if (appCreate > 0) appCreate - processStart else 0L
        val render = if (appCreate > 0) now - appCreate else total

        BLog.i("Startup", "Cold start total=${total}ms (init=${appInit}ms + render=${render}ms)")
        PerfLogger.record("cold_start", mapOf(
            "total_ms" to total,
            "app_init_ms" to appInit,
            "render_ms" to render
        ))
    }

    fun markFullyDrawn() {
        if (processStart == 0L) return
        val ttfd = SystemClock.elapsedRealtime() - processStart
        BLog.i("Startup", "TTFD=${ttfd}ms")
        PerfLogger.record("ttfd", mapOf("ttfd_ms" to ttfd))
    }
}
