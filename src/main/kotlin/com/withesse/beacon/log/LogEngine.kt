package com.withesse.beacon.log

import android.content.Context
import com.withesse.beacon.config.BeaconConfig
import com.tencent.mars.xlog.Xlog
import java.io.File

/**
 * XLog engine wrapper.
 * Uses mmap memory-mapped writes — no log loss even on crash.
 * File path: {filesDir}/beacon/logs/app_yyyyMMdd.xlog
 *
 * XLog 引擎封装。
 * mmap 内存映射写入，即使 App crash 也不丢日志。
 * 文件位置: {filesDir}/beacon/logs/app_yyyyMMdd.xlog
 */
object LogEngine {

    lateinit var logDir: String
        private set

    private lateinit var cacheDir: String
    @Volatile
    private var initialized = false

    fun init(context: Context, config: BeaconConfig) {
        if (initialized) return

        logDir = "${context.filesDir}/beacon/logs"
        cacheDir = "${context.filesDir}/beacon/logs/cache"

        File(logDir).mkdirs()
        File(cacheDir).mkdirs()

        val xlogConfig = Xlog.XLogConfig().apply {
            level = if (config.fileEnabled) config.logLevel else android.util.Log.ASSERT
            logdir = this@LogEngine.logDir
            cachedir = this@LogEngine.cacheDir
            nameprefix = "app"
            compressmode = Xlog.AppednerModeAsync
            compresslevel = 0
            pubkey = ""
            cachedays = 0
        }

        Xlog.open(xlogConfig)
        Xlog.setConsoleLogOpen(false)
        com.tencent.mars.xlog.Log.setLogImp(Xlog())
        initialized = true
    }

    /** Pause file writes when free disk space drops below this (50MB) | 磁盘剩余空间低于此值时暂停写入 */
    private const val MIN_DISK_SPACE_BYTES = 50L * 1024 * 1024
    private const val DISK_CHECK_INTERVAL_MS = 60_000L

    @Volatile private var lastDiskCheckTime = 0L
    @Volatile private var lastDiskCheckResult = true

    fun hasDiskSpace(): Boolean {
        if (!initialized) return true
        val now = System.currentTimeMillis()
        if (now - lastDiskCheckTime < DISK_CHECK_INTERVAL_MS) return lastDiskCheckResult
        return try {
            val result = File(logDir).usableSpace > MIN_DISK_SPACE_BYTES
            lastDiskCheckResult = result
            lastDiskCheckTime = now
            result
        } catch (_: Exception) {
            true
        }
    }

    fun write(level: Int, tag: String, message: String) {
        if (!initialized) return
        when (level) {
            android.util.Log.VERBOSE -> com.tencent.mars.xlog.Log.v(tag, message)
            android.util.Log.DEBUG   -> com.tencent.mars.xlog.Log.d(tag, message)
            android.util.Log.INFO    -> com.tencent.mars.xlog.Log.i(tag, message)
            android.util.Log.WARN    -> com.tencent.mars.xlog.Log.w(tag, message)
            android.util.Log.ERROR   -> com.tencent.mars.xlog.Log.e(tag, message)
            android.util.Log.ASSERT  -> com.tencent.mars.xlog.Log.f(tag, message)
        }
    }

    fun flush() {
        if (initialized) Xlog.flush(true)
    }

    fun setLevel(level: Int) {
        if (initialized) Xlog.setLevel(level)
    }

    fun close() {
        if (initialized) {
            Xlog.close()
            initialized = false
        }
    }
}
