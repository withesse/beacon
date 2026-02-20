package com.withesse.beacon.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.withesse.beacon.Beacon
import com.withesse.beacon.apm.CrashCaptor
import com.withesse.beacon.apm.PerfLogger
import com.withesse.beacon.log.LogEngine
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Log exporter — pack logs into a zip for sharing, saving, or uploading.
 * 日志导出器 — 打包日志为 zip，支持分享、保存、上传。
 *
 * Usage | 用法:
 *   LogExporter.share(context)              // system share | 系统分享
 *   LogExporter.saveToDownloads(context)    // save to Downloads | 保存到下载
 *   val zip = LogExporter.export(context)   // get zip file | 获取 zip 路径
 *
 * Zip structure | 导出 zip 结构:
 *   beacon_20260219_143052.zip
 *   ├── device_info.txt
 *   ├── logs/app_20260219.xlog
 *   ├── crash/xxx.xcrash.log
 *   └── apm/perf_20260219.jsonl
 */
object LogExporter {

    /**
     * Pack and export logs to a zip file.
     * 打包导出日志为 zip 文件。
     *
     * @param daysBack     Export recent N days | 导出最近 N 天
     * @param includeApm   Include APM perf data | 包含 APM 性能数据
     * @param includeCrash Include crash dumps | 包含 Crash dump
     */
    suspend fun export(
        context: Context,
        daysBack: Int = 3,
        includeApm: Boolean = true,
        includeCrash: Boolean = true
    ): File = withContext(Dispatchers.IO) {

        check(Beacon.isInitialized) { "Beacon not initialized, call Beacon.init() first" }
        LogEngine.flush()

        val exportDir = File(context.cacheDir, "beacon_export").apply { mkdirs() }

        // Clean previous export cache | 自动清理上次导出的缓存
        exportDir.listFiles()?.forEach { it.delete() }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(exportDir, "beacon_${timestamp}.zip")

        val cutoff = System.currentTimeMillis() - daysBack * 24 * 3600 * 1000L
        val entries = mutableListOf<Pair<String, File>>()

        // App logs | 业务日志
        File(LogEngine.logDir).listFiles()
            ?.filter { it.isFile && !it.name.startsWith("cache") && it.lastModified() >= cutoff }
            ?.forEach { entries.add("logs/${it.name}" to it) }

        // Crash
        if (includeCrash) {
            CrashCaptor.getFiles()
                .filter { it.lastModified() >= cutoff }
                .forEach { entries.add("crash/${it.name}" to it) }
        }

        // APM
        if (includeApm) {
            PerfLogger.getFiles()
                .filter { it.lastModified() >= cutoff }
                .forEach { entries.add("apm/${it.name}" to it) }
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.putNextEntry(ZipEntry("device_info.txt"))
            zos.write(DeviceInfoCollector.collect(context).toByteArray())
            zos.closeEntry()

            entries.forEach { (entryName, file) ->
                try {
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                } catch (e: Exception) {
                    android.util.Log.w("LogExporter", "Failed to add ${entryName}", e)
                }
            }
        }

        zipFile
    }

    /** Export + system share | 导出 + 系统分享 */
    suspend fun share(
        context: Context,
        daysBack: Int = 3,
        includeApm: Boolean = true,
        includeCrash: Boolean = true
    ) {
        val zip = export(context, daysBack, includeApm, includeCrash)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.beacon.fileprovider", zip
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Beacon Logs - ${zip.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Export Logs")
        val launchChooser = Runnable {
            try {
                context.startActivity(chooser)
            } catch (e: android.content.ActivityNotFoundException) {
                android.util.Log.w("LogExporter", "No activity to handle share intent", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            launchChooser.run()
        } else {
            Handler(Looper.getMainLooper()).post(launchChooser)
        }
    }

    /** Export + save to Downloads | 导出 + 保存到下载目录 */
    suspend fun saveToDownloads(context: Context, daysBack: Int = 3): String =
        withContext(Dispatchers.IO) {
            val zip = export(context, daysBack)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Failed to create Downloads entry")
                resolver.openOutputStream(uri)?.use { os ->
                    zip.inputStream().use { it.copyTo(os) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dest = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    zip.name
                )
                zip.copyTo(dest, overwrite = true)
            }

            zip.name
        }

    /**
     * Export zip and write to a given OutputStream (for custom upload).
     * Caller is responsible for closing outputStream.
     *
     * 导出 zip 并写入指定输出流（用于自定义上传）。
     * 调用方负责关闭 outputStream。
     */
    suspend fun exportTo(
        context: Context,
        outputStream: OutputStream,
        daysBack: Int = 3,
        includeApm: Boolean = true,
        includeCrash: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val zip = export(context, daysBack, includeApm, includeCrash)
        zip.inputStream().buffered().use { it.copyTo(outputStream) }
    }

    @JvmStatic
    fun cleanCache(context: Context) {
        File(context.cacheDir, "beacon_export").deleteRecursively()
    }

    @JvmStatic
    fun getLogSize(): Long {
        if (!Beacon.isInitialized) return 0L
        return try {
            File(LogEngine.logDir).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) { 0L }
    }

    @JvmStatic
    fun getLogFileCount(): Int {
        if (!Beacon.isInitialized) return 0
        return try {
            File(LogEngine.logDir).walkTopDown().filter { it.isFile }.count()
        } catch (_: Exception) { 0 }
    }
}
