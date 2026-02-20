package com.withesse.beacon

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.withesse.beacon.apm.*
import com.withesse.beacon.config.BeaconConfig
import com.withesse.beacon.config.ConfigStore
import com.withesse.beacon.log.BLog
import com.withesse.beacon.log.LogEngine
import com.tencent.mmkv.MMKV
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import java.io.File

/**
 * Beacon — Lightweight App Monitoring SDK
 * Beacon — 轻量级 App 监控 SDK
 *
 * ┌──────────────────────────────────────────────────────┐
 * │  Init:    Beacon.init(this)         // 初始化         │
 * │  Log:     BLog.i("Tag", "message")  // 写日志         │
 * │  Export:  LogExporter.share(ctx)    // 导出日志        │
 * │  Toggle:  ConfigStore.toggle(...)   // 运行时开关      │
 * └──────────────────────────────────────────────────────┘
 */
object Beacon {

    const val VERSION = "1.0.0"

    lateinit var application: Application
        private set

    @Volatile
    var config: BeaconConfig = BeaconConfig()
        internal set

    @Volatile
    var isInitialized: Boolean = false
        private set

    internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private set

    private val initLock = Any()
    private var fpsTracer: FPSTracer? = null
    private var memoryTracer: MemoryTracer? = null
    private var firstActivityResumed = false
    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    /** Current foreground Activity simple name, for APM page tracking | 当前前台 Activity 类名，用于 APM 页面追踪 */
    @Volatile
    var currentPage: String = ""
        internal set

    private val listeners = mutableListOf<BeaconListener>()

    @JvmStatic
    fun addListener(listener: BeaconListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    @JvmStatic
    fun removeListener(listener: BeaconListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    internal fun notifyListeners(action: (BeaconListener) -> Unit) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { listener ->
            try { action(listener) } catch (e: Exception) {
                android.util.Log.w("Beacon", "Listener callback failed", e)
            }
        }
    }

    /**
     * Initialize SDK | 初始化
     *
     * @param app          Application instance | Application 实例
     * @param customConfig Custom config (optional) | 自定义配置（可选）
     */
    @JvmStatic
    @JvmOverloads
    fun init(app: Application, customConfig: BeaconConfig? = null) {
        if (isInitialized) {
            android.util.Log.w("Beacon", "Already initialized, ignoring duplicate init() call")
            return
        }
        synchronized(initLock) {
            if (isInitialized) return

            application = app

            // 1. MMKV init | MMKV 初始化
            MMKV.initialize(app)

            // 2. Config | 配置
            config = customConfig ?: ConfigStore.load()
            customConfig?.let { ConfigStore.save(it) }
            ConfigStore.onChanged = { onConfigChanged() }

            if (!config.enabled) {
                isInitialized = true
                return
            }

            // 3. Log engine | 日志引擎
            LogEngine.init(app, config)

            // 4. Crash capture (as early as possible) | Crash 捕获（尽早初始化）
            if (config.crashEnabled) {
                CrashCaptor.init(app)
            }

            // 5. APM | 性能监控
            PerfLogger.init(app)

            if (config.fpsEnabled) {
                fpsTracer = FPSTracer().also { it.start() }
            }
            if (config.memoryEnabled) {
                memoryTracer = MemoryTracer().also { it.start() }
            }

            // 6. Activity lifecycle callbacks | Activity 生命周期
            registerActivityCallbacks(app)

            // 7. Foreground/background monitoring | 前后台切换监控
            registerProcessLifecycle()

            // 8. Clean expired files in background (avoid blocking startup) | 后台清理过期文件（避免阻塞启动）
            scope.launch { cleanExpiredFiles() }

            isInitialized = true
        }
        BLog.i("Beacon", "Initialized v$VERSION")
    }

    /**
     * Clear all log data under filesDir/beacon/.
     * 清除 filesDir/beacon/ 下的所有日志数据。
     */
    @JvmStatic
    fun clearLogData() {
        if (!isInitialized) return
        try {
            File("${application.filesDir}/beacon").deleteRecursively()
            File("${application.filesDir}/beacon").mkdirs()
        } catch (e: Exception) {
            android.util.Log.w("Beacon", "clearLogData failed", e)
        }
    }

    /**
     * Graceful shutdown, release all resources.
     * Can call init() again to re-initialize.
     *
     * 优雅关闭，释放所有资源。
     * 关闭后可再次调用 init() 重新初始化。
     */
    @JvmStatic
    fun shutdown() {
        if (!isInitialized) return
        synchronized(initLock) {
            if (!isInitialized) return
            fpsTracer?.stop()
            fpsTracer = null
            memoryTracer?.stop()
            memoryTracer = null
            activityCallbacks?.let { application.unregisterActivityLifecycleCallbacks(it) }
            activityCallbacks = null
            lifecycleObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
            lifecycleObserver = null
            LogEngine.flush()
            LogEngine.close()
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            firstActivityResumed = false
            currentPage = ""
            synchronized(listeners) { listeners.clear() }
            isInitialized = false
        }
    }

    // ==================== Hot config reload | 配置热切换 ====================

    internal fun onConfigChanged() {
        synchronized(initLock) {
            if (!isInitialized) return
            val wasEnabled = config.enabled
            config = ConfigStore.load()

            // enabled false→true: initialize subsystems skipped during init() | 启用时初始化之前跳过的子系统
            if (config.enabled && !wasEnabled) {
                LogEngine.init(application, config)
                if (config.crashEnabled) CrashCaptor.init(application)
                PerfLogger.init(application)
                if (activityCallbacks == null) registerActivityCallbacks(application)
                if (lifecycleObserver == null) registerProcessLifecycle()
                scope.launch { cleanExpiredFiles() }
            }

            // enabled true→false: stop tracers | 禁用时停止所有监控
            if (!config.enabled) {
                fpsTracer?.stop(); fpsTracer = null
                memoryTracer?.stop(); memoryTracer = null
                return
            }

            LogEngine.setLevel(config.logLevel)

            if (config.fpsEnabled && fpsTracer == null) {
                fpsTracer = FPSTracer().also { it.start() }
            } else if (!config.fpsEnabled && fpsTracer != null) {
                fpsTracer?.stop(); fpsTracer = null
            }

            if (config.memoryEnabled) {
                memoryTracer?.stop()
                memoryTracer = MemoryTracer().also { it.start() }
            } else if (memoryTracer != null) {
                memoryTracer?.stop(); memoryTracer = null
            }
        }
        BLog.i("Beacon", "Config hot-reloaded")
    }

    // ==================== Internal | 内部实现 ====================

    private var lastForegroundTime = 0L

    private fun registerProcessLifecycle() {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lastForegroundTime = System.currentTimeMillis()
                BLog.i("Beacon", "App entered foreground")
                PerfLogger.record("app_foreground", emptyMap())
                notifyListeners { it.onAppForegroundChanged(true, 0) }
            }

            override fun onStop(owner: LifecycleOwner) {
                val duration = System.currentTimeMillis() - lastForegroundTime
                BLog.i("Beacon", "App entered background (foreground duration ${duration}ms)")
                PerfLogger.record("app_background", mapOf("foreground_duration_ms" to duration))
                notifyListeners { it.onAppForegroundChanged(false, duration) }
                LogEngine.flush()
            }
        }
        lifecycleObserver = observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    private fun registerActivityCallbacks(app: Application) {
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentPage = activity.javaClass.simpleName
                if (!firstActivityResumed && config.startupEnabled) {
                    firstActivityResumed = true
                    StartupTracer.markFirstFrame()
                }
            }
            override fun onActivityPaused(a: Activity) {
                if (currentPage == a.javaClass.simpleName) currentPage = ""
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        activityCallbacks = callbacks
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    private fun cleanExpiredFiles() {
        try {
            val cutoff = System.currentTimeMillis() - config.maxRetainDays * 24 * 3600 * 1000L
            val maxBytes = config.maxTotalSizeMB * 1024L * 1024L

            val logDir = File(LogEngine.logDir)
            val cacheDir = File(logDir, "cache")
            val dirs = listOf(
                logDir,
                File("${application.filesDir}/beacon/apm"),
                File("${application.filesDir}/beacon/crash")
            )

            // Delete expired files by time (skip XLog cache dir) | 按时间清理过期文件（排除 XLog cache 目录）
            for (dir in dirs) {
                if (!dir.exists()) continue
                dir.walkTopDown()
                    .onEnter { it != cacheDir }
                    .filter { it.isFile && it.lastModified() < cutoff }
                    .forEach { it.delete() }
            }

            // Trim by total size (log dir only, skip cache) | 按总大小清理（只对日志目录限制大小，排除 cache）
            if (!logDir.exists()) return
            val logFiles = logDir.walkTopDown()
                .onEnter { it != cacheDir }
                .filter { it.isFile }
                .sortedBy { it.lastModified() }
                .toList()
            var remaining = logFiles.sumOf { it.length() }
            if (remaining > maxBytes) {
                val target = (maxBytes * 0.8).toLong()
                for (file in logFiles) {
                    if (remaining <= target) break
                    val size = file.length()
                    if (file.delete()) remaining -= size
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Beacon", "cleanExpiredFiles failed", e)
        }
    }
}
