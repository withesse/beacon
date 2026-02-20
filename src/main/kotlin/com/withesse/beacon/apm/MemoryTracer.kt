package com.withesse.beacon.apm

import android.os.Debug
import com.withesse.beacon.Beacon
import com.withesse.beacon.log.BLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Memory sampling monitor.
 * Periodically samples Java Heap / Native Heap; logs warning when threshold is exceeded.
 *
 * 内存采样监控。
 * 定期采集 Java Heap / Native Heap，超阈值写告警。
 */
class MemoryTracer {

    private var job: Job? = null

    fun start() {
        job = Beacon.scope.launch {
            while (isActive) {
                sample()
                delay(Beacon.config.perfSampleIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun sample() {
        val rt = Runtime.getRuntime()
        val heapUsed = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val heapMax = rt.maxMemory() / 1024 / 1024
        val ratio = if (heapMax > 0) heapUsed.toFloat() / heapMax else 0f
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / 1024 / 1024

        PerfLogger.record("memory", mapOf(
            "heap_used_mb" to heapUsed,
            "heap_max_mb" to heapMax,
            "native_mb" to nativeHeap,
            "ratio" to "%.2f".format(ratio)
        ), page = Beacon.currentPage.ifEmpty { null })

        if (ratio > Beacon.config.memoryWarnRatio) {
            BLog.w("Memory", "Memory warning ${heapUsed}MB/${heapMax}MB (${(ratio * 100).toInt()}%)")
            Beacon.notifyListeners { it.onLowMemory(heapUsed, heapMax, ratio) }
        }
    }
}
