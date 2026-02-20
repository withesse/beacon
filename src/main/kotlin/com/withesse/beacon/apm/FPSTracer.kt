package com.withesse.beacon.apm

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import com.withesse.beacon.Beacon
import com.withesse.beacon.log.BLog

/**
 * FPS & jank monitoring.
 * Uses Choreographer to sample frame rate; frozen frames (>700ms) include main thread stack.
 * Auto-adapts to screen refresh rate (60/90/120Hz).
 *
 * 帧率 & 卡顿监控。
 * Choreographer 采集帧率，冻帧（>700ms）附带主线程堆栈。
 * 自动适配屏幕刷新率（60/90/120Hz）。
 */
class FPSTracer {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private var lastNanos = 0L
    private var frameCount = 0
    private var droppedFrames = 0
    private var maxFps = 60

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastNanos > 0) {
                val ms = (frameTimeNanos - lastNanos) / 1_000_000
                val frameDurationMs = 1000f / maxFps
                frameCount++
                if (ms > frameDurationMs) {
                    droppedFrames += ((ms / frameDurationMs).toInt() - 1)
                }

                if (ms > 700) {
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .take(15).joinToString("\n") { "  at $it" }
                    BLog.w("FPS", "Frozen frame ${ms}ms\n$stack")
                    PerfLogger.record("frozen_frame", mapOf(
                        "duration_ms" to ms, "stack" to stack
                    ), page = Beacon.currentPage.ifEmpty { null })
                }
            }
            lastNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val reporter = object : Runnable {
        override fun run() {
            if (!running) return
            val fps = frameCount.coerceAtMost(maxFps)
            val threshold = Beacon.config.fpsWarnThreshold

            if (fps in 1 until threshold) {
                BLog.w("FPS", "Low FPS fps=$fps/$maxFps dropped=$droppedFrames")
                PerfLogger.record("low_fps", mapOf(
                    "fps" to fps, "max_fps" to maxFps, "dropped" to droppedFrames
                ), page = Beacon.currentPage.ifEmpty { null })
                Beacon.notifyListeners { it.onLowFps(fps, maxFps, droppedFrames) }
            }

            frameCount = 0
            droppedFrames = 0
            lastNanos = 0L
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        maxFps = detectRefreshRate()
        handler.post {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        handler.postDelayed(reporter, 1000)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun detectRefreshRate(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Beacon.application.display?.refreshRate?.toInt() ?: 60
            } else {
                @Suppress("DEPRECATION")
                val wm = Beacon.application.getSystemService(android.content.Context.WINDOW_SERVICE)
                        as? android.view.WindowManager
                wm?.defaultDisplay?.refreshRate?.toInt() ?: 60
            }
        } catch (_: Exception) {
            60
        }
    }
}
