package com.withesse.beacon.log

import android.util.Log
import com.withesse.beacon.Beacon

/**
 * Beacon log facade — the single entry point for all business logging.
 * Features: level filtering · auto masking · dual-write Logcat + XLog file · hot-toggle
 *
 * Beacon 日志门面 — 业务层唯一入口。
 * 功能: 级别过滤 · 自动脱敏 · 双写 Logcat + XLog 文件 · 开关可热切换
 *
 * Usage | 用法:
 *   BLog.i("Order", "order placed orderId=12345")
 *   BLog.e("Pay", "payment failed", exception)
 *
 * Masking | 脱敏:
 *   BLog.i("User", "login phone=13812345678")
 *   // output → login phone=13******78
 */
object BLog {

    @JvmStatic fun v(tag: String, msg: String)                   = log(Log.VERBOSE, tag, msg)
    @JvmStatic fun d(tag: String, msg: String)                   = log(Log.DEBUG, tag, msg)
    @JvmStatic fun i(tag: String, msg: String)                   = log(Log.INFO, tag, msg)
    @JvmStatic fun w(tag: String, msg: String)                   = log(Log.WARN, tag, msg)
    @JvmStatic fun w(tag: String, msg: String, t: Throwable)     = log(Log.WARN, tag, msg, t)
    @JvmStatic fun e(tag: String, msg: String)                   = log(Log.ERROR, tag, msg)
    @JvmStatic fun e(tag: String, msg: String, t: Throwable)     = log(Log.ERROR, tag, msg, t)

    @JvmStatic
    fun v(tag: String, format: String, vararg args: Any?) =
        log(Log.VERBOSE, tag, String.format(format, *args))

    @JvmStatic
    fun d(tag: String, format: String, vararg args: Any?) =
        log(Log.DEBUG, tag, String.format(format, *args))

    @JvmStatic
    fun i(tag: String, format: String, vararg args: Any?) =
        log(Log.INFO, tag, String.format(format, *args))

    @JvmStatic
    fun w(tag: String, format: String, vararg args: Any?) =
        log(Log.WARN, tag, String.format(format, *args))

    @JvmStatic
    fun e(tag: String, format: String, vararg args: Any?) =
        log(Log.ERROR, tag, String.format(format, *args))

    // Lambda overloads: skip string concatenation when log level is filtered | Lambda 重载: 级别过滤时避免字符串拼接
    inline fun v(tag: String, lazyMsg: () -> String) { if (shouldLog(Log.VERBOSE)) log(Log.VERBOSE, tag, lazyMsg()) }
    inline fun d(tag: String, lazyMsg: () -> String) { if (shouldLog(Log.DEBUG)) log(Log.DEBUG, tag, lazyMsg()) }
    inline fun i(tag: String, lazyMsg: () -> String) { if (shouldLog(Log.INFO)) log(Log.INFO, tag, lazyMsg()) }
    inline fun w(tag: String, lazyMsg: () -> String) { if (shouldLog(Log.WARN)) log(Log.WARN, tag, lazyMsg()) }
    inline fun e(tag: String, lazyMsg: () -> String) { if (shouldLog(Log.ERROR)) log(Log.ERROR, tag, lazyMsg()) }

    @PublishedApi
    internal fun shouldLog(level: Int): Boolean {
        if (!Beacon.isInitialized) return false
        val config = Beacon.config
        return config.enabled && level >= config.logLevel
    }

    @PublishedApi
    internal fun log(level: Int, tag: String, msg: String, t: Throwable? = null) {
        if (!Beacon.isInitialized) {
            Log.w("BLog", "Beacon not initialized, call Beacon.init() first. Dropping: [$tag] $msg")
            return
        }
        val config = Beacon.config
        if (!config.enabled) return
        if (level < config.logLevel) return

        val safeMsg = SensitiveFilter.filter(msg, config.sensitiveKeys)

        val formatted = buildString {
            append("[${Thread.currentThread().name}] ")
            append(safeMsg)
            if (t != null) {
                append("\n")
                append(Log.getStackTraceString(t))
            }
        }

        if (config.fileEnabled && LogEngine.hasDiskSpace()) {
            LogEngine.write(level, tag, formatted)
        }

        if (config.consoleEnabled) {
            val safeTag = safeTag(tag)
            when (level) {
                Log.VERBOSE -> Log.v(safeTag, formatted)
                Log.DEBUG   -> Log.d(safeTag, formatted)
                Log.INFO    -> Log.i(safeTag, formatted)
                Log.WARN    -> Log.w(safeTag, formatted)
                Log.ERROR   -> Log.e(safeTag, formatted)
            }
        }
    }

    /** API < 26: Logcat tag max 23 chars, truncate to avoid IllegalArgumentException | API < 26 Logcat tag 最长 23 字符，超长截断 */
    private fun safeTag(tag: String): String {
        return if (android.os.Build.VERSION.SDK_INT < 26 && tag.length > 23) {
            tag.substring(0, 23)
        } else {
            tag
        }
    }
}
