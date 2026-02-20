package com.withesse.beacon.config

import android.util.Log

/**
 * Beacon monitoring configuration.
 * All fields have defaults — override as needed.
 *
 * Beacon 监控配置。
 * 所有字段都有默认值，按需覆盖即可。
 *
 * Usage | 用法:
 *   Beacon.init(app)                                      // defaults | 默认配置
 *   Beacon.init(app, BeaconConfig(logLevel = Log.DEBUG))   // custom | 自定义
 */
data class BeaconConfig(
    // ====== Global | 全局 ======
    val enabled: Boolean = true,

    // ====== Logging | 日志 ======
    /** Min log level: Log.VERBOSE=2, DEBUG=3, INFO=4, WARN=5, ERROR=6 | 最低记录级别 */
    val logLevel: Int = Log.INFO,
    /** Logcat output | Logcat 输出 */
    val consoleEnabled: Boolean = true,
    /** File logging | 文件日志 */
    val fileEnabled: Boolean = true,
    /** Log retention days | 日志保留天数 */
    val maxRetainDays: Int = 7,
    /** Max total log size (MB) | 日志文件总大小上限 */
    val maxTotalSizeMB: Int = 100,

    // ====== APM | 性能监控 ======
    /** Crash/ANR capture | Crash/ANR 捕获 */
    val crashEnabled: Boolean = true,
    /** Cold start timing | 冷启动耗时 */
    val startupEnabled: Boolean = true,
    /** FPS / jank monitoring | FPS / 卡顿监控 */
    val fpsEnabled: Boolean = true,
    /** Memory sampling | 内存采样 */
    val memoryEnabled: Boolean = true,
    /** Low FPS warning threshold | 低帧率告警阈值 */
    val fpsWarnThreshold: Int = 45,
    /** Memory warning threshold (ratio of maxMemory) | 内存告警阈值（占 maxMemory 比例） */
    val memoryWarnRatio: Float = 0.85f,
    /** Memory sampling interval (ms) | 内存采样间隔 */
    val perfSampleIntervalMs: Long = 30_000L,

    // ====== Masking | 脱敏 ======
    /** Keywords to mask | 需要脱敏的关键词 */
    val sensitiveKeys: List<String> = listOf(
        "password", "token", "secret", "phone", "idCard"
    )
) {
    init {
        require(logLevel in Log.VERBOSE..Log.ASSERT) {
            "logLevel must be in ${Log.VERBOSE}..${Log.ASSERT}, got $logLevel"
        }
        require(maxRetainDays > 0) { "maxRetainDays must be > 0, got $maxRetainDays" }
        require(maxTotalSizeMB > 0) { "maxTotalSizeMB must be > 0, got $maxTotalSizeMB" }
        require(fpsWarnThreshold in 1..120) { "fpsWarnThreshold must be in 1..120, got $fpsWarnThreshold" }
        require(memoryWarnRatio in 0f..1f) { "memoryWarnRatio must be in 0..1, got $memoryWarnRatio" }
        require(perfSampleIntervalMs >= 1000) { "perfSampleIntervalMs must be >= 1000ms, got $perfSampleIntervalMs" }
    }
}
