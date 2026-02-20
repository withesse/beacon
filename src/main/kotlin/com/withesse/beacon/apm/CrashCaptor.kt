package com.withesse.beacon.apm

import android.content.Context
import android.util.Log
import com.withesse.beacon.Beacon
import xcrash.XCrash
import java.io.File

/**
 * Unified Crash / ANR capture (based on xCrash).
 * File path: {filesDir}/beacon/crash/
 * Note: Callbacks use android.util.Log instead of BLog,
 * because Beacon's internal state may be unstable during a crash.
 *
 * Crash / ANR 统一捕获（基于 xCrash）。
 * 文件位置: {filesDir}/beacon/crash/
 * 注意: 回调直接使用 android.util.Log 而非 BLog，
 * 因为崩溃时 Beacon 内部状态可能已不稳定。
 */
object CrashCaptor {

    private const val TAG = "Crash"
    private lateinit var crashDir: String

    fun init(context: Context) {
        crashDir = "${context.filesDir}/beacon/crash"
        File(crashDir).mkdirs()

        try {
            XCrash.init(context, XCrash.InitParameters().apply {
                setLogDir(crashDir)

                setJavaCallback { logPath, emergency ->
                    Log.e(TAG, "Java Crash: $logPath")
                    if (emergency != null) Log.e(TAG, "Successive crash: $emergency")
                    safeRecord("java_crash", logPath, emergency)
                }

                setNativeCallback { logPath, emergency ->
                    Log.e(TAG, "Native Crash: $logPath")
                    safeRecord("native_crash", logPath, emergency)
                }

                setAnrCallback { logPath, emergency ->
                    Log.e(TAG, "ANR: $logPath")
                    safeRecord("anr", logPath, emergency)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "xCrash init failed", e)
        }
    }

    fun getFiles(): List<File> {
        if (!::crashDir.isInitialized) return emptyList()
        val dir = File(crashDir)
        return if (dir.exists()) dir.listFiles()?.toList() ?: emptyList() else emptyList()
    }

    private fun safeRecord(type: String, logPath: String?, emergency: String?) {
        try {
            PerfLogger.recordSync(type, mapOf(
                "log_path" to (logPath ?: ""),
                "emergency" to (emergency != null)
            ))
            Beacon.notifyListeners { it.onCrash(type, logPath) }
        } catch (_: Exception) {
            // Must not throw from crash callback | 崩溃回调中不能再抛异常
        }
    }
}
