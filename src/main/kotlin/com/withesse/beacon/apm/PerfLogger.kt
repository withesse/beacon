package com.withesse.beacon.apm

import android.content.Context
import com.withesse.beacon.Beacon
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Local performance data recorder (JSONL format).
 * File path: {filesDir}/beacon/apm/perf_yyyyMMdd.jsonl
 * One JSON per line: {"ts":1708300000,"type":"cold_start","data":{"total_ms":1230}}
 *
 * 性能数据本地记录器（JSONL 格式）。
 * 文件位置: {filesDir}/beacon/apm/perf_yyyyMMdd.jsonl
 * 每行一个 JSON: {"ts":1708300000,"type":"cold_start","data":{"total_ms":1230}}
 */
object PerfLogger {

    private lateinit var apmDir: String

    fun init(context: Context) {
        apmDir = "${context.filesDir}/beacon/apm"
        File(apmDir).mkdirs()
    }

    fun record(type: String, data: Map<String, Any?>, page: String? = null) {
        try {
            Beacon.scope.launch {
                try {
                    val json = buildJsonLine(type, data, page)
                    synchronized(this@PerfLogger) { appendToFile(json) }
                } catch (e: Exception) {
                    android.util.Log.w("PerfLogger", "record failed", e)
                }
            }
        } catch (_: Exception) {
            // scope cancelled (e.g. during crash/shutdown), fall back to sync write | scope 已取消（如崩溃/关闭），回退到同步写入
            recordSync(type, data, page)
        }
    }

    /** Synchronous write for crash callbacks where coroutine scope may be unavailable | 同步写入，用于崩溃回调（协程 scope 可能不可用） */
    internal fun recordSync(type: String, data: Map<String, Any?>, page: String? = null) {
        try {
            val json = buildJsonLine(type, data, page)
            synchronized(this) { appendToFile(json) }
        } catch (e: Exception) {
            android.util.Log.w("PerfLogger", "recordSync failed", e)
        }
    }

    fun getFiles(): List<File> {
        if (!::apmDir.isInitialized) return emptyList()
        val dir = File(apmDir)
        return if (dir.exists()) dir.listFiles()?.toList() ?: emptyList() else emptyList()
    }

    private fun buildJsonLine(type: String, data: Map<String, Any?>, page: String?): String {
        val sb = StringBuilder()
        sb.append("""{"ts":${System.currentTimeMillis()},"type":"${escapeJson(type)}","data":{""")
        data.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(",")
            sb.append("\"${escapeJson(k)}\":")
            when (v) {
                is String  -> sb.append("\"${escapeJson(v)}\"")
                is Number  -> sb.append(v)
                is Boolean -> sb.append(v)
                null       -> sb.append("null")
                else       -> sb.append("\"${escapeJson(v.toString())}\"")
            }
        }
        sb.append("}")
        if (page != null) sb.append(",\"page\":\"${escapeJson(page)}\"")
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun appendToFile(json: String) {
        if (!::apmDir.isInitialized) return
        if (!hasDiskSpace()) return
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(apmDir, "perf_${date}.jsonl")
        FileOutputStream(file, true).bufferedWriter().use {
            it.append(json)
            it.newLine()
        }
    }

    @Volatile private var lastDiskCheckTime = 0L
    @Volatile private var lastDiskCheckResult = true

    private fun hasDiskSpace(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDiskCheckTime < 60_000L) return lastDiskCheckResult
        return try {
            val result = File(apmDir).usableSpace > 50L * 1024 * 1024
            lastDiskCheckResult = result
            lastDiskCheckTime = now
            result
        } catch (_: Exception) {
            true
        }
    }
}
