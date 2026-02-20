package com.withesse.beacon.apm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.withesse.beacon.Beacon
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Java Crash + ANR capture (zero native dependency).
 * File path: {filesDir}/beacon/crash/
 * Note: Callbacks use android.util.Log and sync file IO only,
 * because Beacon's internal state may be unstable during a crash.
 *
 * Java 崩溃 + ANR 捕获（零 native 依赖）。
 * 文件位置: {filesDir}/beacon/crash/
 * 注意: 回调直接使用 android.util.Log 和同步文件 IO，
 * 因为崩溃时 Beacon 内部状态可能已不稳定。
 */
object CrashCaptor {

    private const val TAG = "Crash"
    private lateinit var crashDir: String
    private lateinit var appContext: Context

    // ANR watchdog state
    private var anrThread: Thread? = null
    @Volatile private var anrRunning = false
    @Volatile private var tickFlag = false
    @Volatile private var lastAnrTime = 0L

    private const val ANR_CHECK_INTERVAL_MS = 5_000L
    private const val ANR_COOLDOWN_MS = 60_000L

    fun init(context: Context) {
        crashDir = "${context.filesDir}/beacon/crash"
        appContext = context.applicationContext
        File(crashDir).mkdirs()

        installJavaCrashHandler()
        startAnrWatchdog()
    }

    fun getFiles(): List<File> {
        if (!::crashDir.isInitialized) return emptyList()
        val dir = File(crashDir)
        return if (dir.exists()) dir.listFiles()?.toList() ?: emptyList() else emptyList()
    }

    // ==================== Java Crash Handler ====================

    private fun installJavaCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Java Crash on thread: ${thread.name}", throwable)
                val logPath = writeCrashFile("java_crash", thread, throwable)
                safeRecord("java_crash", logPath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash file", e)
            } finally {
                // Delegate to original handler so process terminates normally
                original?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun writeCrashFile(type: String, thread: Thread, throwable: Throwable): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(crashDir, "${type}_$timestamp.log")
            FileOutputStream(file).bufferedWriter().use { w ->
                w.write(buildReport(type, thread, throwable))
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "writeCrashFile failed", e)
            null
        }
    }

    private fun buildReport(type: String, thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder()
        sb.appendLine("Beacon Crash Report")
        sb.appendLine("Type: $type")
        sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine(getAppVersionLine())
        sb.appendLine("---")
        sb.appendLine("Thread: ${thread.name} (id=${thread.threadId()})")
        // Stack trace
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        sb.append(sw.toString())
        return sb.toString()
    }

    // ==================== ANR Watchdog ====================

    private fun startAnrWatchdog() {
        if (anrThread != null) return
        anrRunning = true
        val mainHandler = Handler(Looper.getMainLooper())

        anrThread = Thread({
            while (anrRunning) {
                tickFlag = false
                mainHandler.post { tickFlag = true }

                try {
                    Thread.sleep(ANR_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }

                if (!tickFlag && anrRunning) {
                    val now = System.currentTimeMillis()
                    if (now - lastAnrTime < ANR_COOLDOWN_MS) continue
                    lastAnrTime = now

                    val mainThread = Looper.getMainLooper().thread
                    val stackTrace = mainThread.stackTrace
                    Log.e(TAG, "ANR detected, main thread blocked")
                    val logPath = writeAnrFile(mainThread, stackTrace)
                    safeRecord("anr", logPath)
                }
            }
        }, "Beacon-ANR-Watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun writeAnrFile(thread: Thread, stackTrace: Array<StackTraceElement>): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(crashDir, "anr_$timestamp.log")
            FileOutputStream(file).bufferedWriter().use { w ->
                w.write(buildAnrReport(thread, stackTrace))
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "writeAnrFile failed", e)
            null
        }
    }

    private fun buildAnrReport(thread: Thread, stackTrace: Array<StackTraceElement>): String {
        val sb = StringBuilder()
        sb.appendLine("Beacon Crash Report")
        sb.appendLine("Type: anr")
        sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine(getAppVersionLine())
        sb.appendLine("---")
        sb.appendLine("Thread: ${thread.name} (id=${thread.threadId()})")
        for (element in stackTrace) {
            sb.appendLine("    at $element")
        }
        return sb.toString()
    }

    // ==================== Util ====================

    private fun getAppVersionLine(): String {
        return try {
            val pm = appContext.packageManager
            val pi = pm.getPackageInfo(appContext.packageName, 0)
            val versionName = pi.versionName ?: "unknown"
            val versionCode = pi.longVersionCode
            "App: ${appContext.packageName} v$versionName (build $versionCode)"
        } catch (_: PackageManager.NameNotFoundException) {
            "App: ${appContext.packageName}"
        }
    }

    private fun safeRecord(type: String, logPath: String?) {
        try {
            PerfLogger.recordSync(type, mapOf(
                "log_path" to (logPath ?: ""),
                "emergency" to false
            ))
            Beacon.notifyListeners { it.onCrash(type, logPath) }
        } catch (_: Exception) {
            // Must not throw from crash callback | 崩溃回调中不能再抛异常
        }
    }
}
