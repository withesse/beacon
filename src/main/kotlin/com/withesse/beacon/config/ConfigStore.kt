package com.withesse.beacon.config

import android.util.Log
import com.tencent.mmkv.MMKV

/**
 * Config persistence + runtime dynamic modification.
 * 配置持久化 + 运行时动态修改。
 *
 * Usage | 用法:
 *   ConfigStore.setLogLevel(Log.VERBOSE)        // verbose for debugging | 临时开启详细日志
 *   ConfigStore.toggle("fpsEnabled", false)     // disable FPS | 关闭 FPS
 *   ConfigStore.save(config.copy(...))          // full update | 完整修改
 */
object ConfigStore {

    private val kv: MMKV by lazy { MMKV.mmkvWithID("beacon_cfg") }

    internal var onChanged: (() -> Unit)? = null

    fun save(config: BeaconConfig) {
        kv.encode("enabled", config.enabled)
        kv.encode("logLevel", config.logLevel)
        kv.encode("consoleEnabled", config.consoleEnabled)
        kv.encode("fileEnabled", config.fileEnabled)
        kv.encode("maxRetainDays", config.maxRetainDays)
        kv.encode("maxTotalSizeMB", config.maxTotalSizeMB)
        kv.encode("crashEnabled", config.crashEnabled)
        kv.encode("startupEnabled", config.startupEnabled)
        kv.encode("fpsEnabled", config.fpsEnabled)
        kv.encode("memoryEnabled", config.memoryEnabled)
        kv.encode("fpsWarnThreshold", config.fpsWarnThreshold)
        kv.encode("memoryWarnRatio", config.memoryWarnRatio)
        kv.encode("perfSampleIntervalMs", config.perfSampleIntervalMs)
        kv.encode("sensitiveKeys", config.sensitiveKeys.joinToString(","))
        onChanged?.invoke()
    }

    fun load(): BeaconConfig {
        return try {
            val d = BeaconConfig()
            val keysStr = kv.decodeString("sensitiveKeys", null)
            val sensitiveKeys = if (keysStr.isNullOrBlank()) d.sensitiveKeys
                else keysStr.split(",").filter { it.isNotBlank() }
            BeaconConfig(
                enabled           = kv.decodeBool("enabled", d.enabled),
                logLevel          = kv.decodeInt("logLevel", d.logLevel),
                consoleEnabled    = kv.decodeBool("consoleEnabled", d.consoleEnabled),
                fileEnabled       = kv.decodeBool("fileEnabled", d.fileEnabled),
                maxRetainDays     = kv.decodeInt("maxRetainDays", d.maxRetainDays),
                maxTotalSizeMB    = kv.decodeInt("maxTotalSizeMB", d.maxTotalSizeMB),
                crashEnabled      = kv.decodeBool("crashEnabled", d.crashEnabled),
                startupEnabled    = kv.decodeBool("startupEnabled", d.startupEnabled),
                fpsEnabled        = kv.decodeBool("fpsEnabled", d.fpsEnabled),
                memoryEnabled     = kv.decodeBool("memoryEnabled", d.memoryEnabled),
                fpsWarnThreshold  = kv.decodeInt("fpsWarnThreshold", d.fpsWarnThreshold),
                memoryWarnRatio   = kv.decodeFloat("memoryWarnRatio", d.memoryWarnRatio),
                perfSampleIntervalMs = kv.decodeLong("perfSampleIntervalMs", d.perfSampleIntervalMs),
                sensitiveKeys     = sensitiveKeys
            )
        } catch (e: Exception) {
            Log.w("ConfigStore", "Corrupted config data, resetting to defaults", e)
            kv.clearAll()
            BeaconConfig()
        }
    }

    private val TOGGLE_KEYS = setOf(
        "enabled", "consoleEnabled", "fileEnabled",
        "crashEnabled", "startupEnabled", "fpsEnabled", "memoryEnabled"
    )

    /** Toggle a single boolean switch | 切换单个 Boolean 开关 */
    fun toggle(key: String, value: Boolean) {
        require(key in TOGGLE_KEYS) {
            "Invalid toggle key: '$key'. Valid keys: $TOGGLE_KEYS"
        }
        kv.encode(key, value)
        onChanged?.invoke()
    }

    /** Dynamically adjust log level | 动态调整日志级别 */
    fun setLogLevel(level: Int) {
        require(level in Log.VERBOSE..Log.ASSERT) {
            "logLevel must be in ${Log.VERBOSE}..${Log.ASSERT}, got $level"
        }
        kv.encode("logLevel", level)
        onChanged?.invoke()
    }

    /** Reset all config to defaults | 重置所有配置到默认值 */
    fun reset() {
        kv.clearAll()
        onChanged?.invoke()
    }
}
