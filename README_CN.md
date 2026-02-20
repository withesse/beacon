# Beacon

**轻量级 Android APM & 日志 SDK** — 崩溃安全的日志记录、性能监控、一键日志导出。

Beacon 是一个即插即用的 Android 库模块，只需 **4 行代码**即可为你的 App 提供生产级可观测能力。它将崩溃安全的文件日志、实时性能监控、敏感信息脱敏、一键日志导出整合到一个统一的 SDK 中。

---

## 为什么选择 Beacon？

| 痛点 | Beacon 的解决方案 |
|---|---|
| `Log.d()` 输出在 App 重启后丢失 | 基于 mmap 的文件日志 (XLog) — 崩溃也不丢 |
| 敏感数据泄露到日志中 | 自动脱敏手机号、身份证、邮箱、银行卡、token |
| 线上卡顿/OOM 难以诊断 | 内置 FPS、内存、冷启动、网络监控 |
| 崩溃日志分散或丢失 | 统一的 Java/Native/ANR 捕获 (xCrash) |
| 从测试/用户收集日志很麻烦 | 一键 zip 导出 + 设备信息快照 |
| 需要在生产环境调整日志 | 运行时热切换所有功能，无需重启 |

---

## 功能详解

### 崩溃安全的日志记录

BLog 同时写入 **Logcat**（开发调试）和 **XLog 文件**（生产环境）。XLog 使用 mmap 内存映射 I/O — 即使 App 崩溃或被杀死，缓冲区中的日志也已刷新到磁盘。再也不会丢失日志。

**原理**: mmap 将文件映射到进程的虚拟内存地址空间。写日志就是写内存，由操作系统内核负责异步刷盘。即使进程意外终止，内核仍会将 dirty pages 写入磁盘，因此日志不会丢失。相比传统 `FileOutputStream`，mmap 避免了用户态→内核态的频繁切换，写入性能提升 10x+。

### 自动敏感信息脱敏

每条日志消息在写入前都会经过 `SensitiveFilter` 处理，自动识别并脱敏：
- **手机号**: `13812345678` → `13******78`
- **身份证**: `110101199001011234` → `11**************34`
- **邮箱**: `user@example.com` → `us**********om`
- **银行卡**: `6222021234567890` → `62************90`
- **JSON 字段**: `"token":"abc123"` → `"token":"***"`
- **键值对参数**: `password=secret` → `password=***`

**原理**: 使用预编译的正则表达式匹配，结合 `ConcurrentHashMap` 缓存动态生成的 JSON/KV 模式。支持两种格式：
- JSON 格式: `"key"\s*:\s*"value"` — 匹配 JSON 字符串字段
- KV 格式: `(^|[?&\s])key=value` — 匹配 URL 参数和日志键值对

脱敏关键词通过 `BeaconConfig.sensitiveKeys` 配置。

### APM 性能监控

#### 冷启动耗时
测量从进程启动到首帧渲染的完整链路：
```
进程创建 → Application.onCreate → 首个 Activity.onResume → 首帧渲染 → 数据加载完成(TTFD)
```

**原理**: `StartupTracer` 在各个关键节点调用 `SystemClock.elapsedRealtime()` 打点。API 24+ 使用 `Process.getStartElapsedRealtime()` 获取精确的进程创建时间。首帧由 `ActivityLifecycleCallbacks.onActivityResumed` 自动标记。

#### FPS & 卡顿检测
使用 `Choreographer.FrameCallback` 统计每秒帧数，自动适配屏幕刷新率（60/90/120Hz）。冻帧（>700ms）会捕获主线程堆栈。

**原理**:
1. 每次 `doFrame` 回调计算与上一帧的时间差 `delta`
2. 若 `delta > frameDuration`（如 16.6ms @60Hz），则计算丢帧数 = `delta / frameDuration - 1`
3. 若 `delta > 700ms`，认定为冻帧，通过 `Thread.getStackTrace()` 抓取主线程堆栈
4. 每秒汇总一次，若 FPS < 阈值则触发告警和回调
5. 所有 Choreographer 操作在主线程 Handler 上执行，避免跨线程注册问题

#### 内存采样
定期采集 Java Heap 和 Native Heap 使用量，超过阈值时触发告警。

**原理**: 通过 `Runtime.getRuntime()` 获取 Java 堆信息，`Debug.getNativeHeapAllocatedSize()` 获取 Native 堆信息。使用协程 `delay` 实现采样间隔，`Job.cancel()` 实现优雅停止。

#### Crash/ANR 捕获
xCrash 统一捕获 Java 异常、Native 崩溃和 ANR，生成详细的堆栈转储文件。

**原理**: xCrash 注册 `UncaughtExceptionHandler`（Java）、信号处理器（Native SIGSEGV/SIGABRT 等）、监控 `/proc/self/stat`（ANR 检测）。崩溃回调中使用 `android.util.Log` 而非 `BLog`，因为此时 SDK 内部状态可能已不稳定。性能数据通过 `PerfLogger.recordSync()` 同步写入，避免依赖可能已取消的协程 scope。

### 网络日志

OkHttp 拦截器自动记录每个 HTTP 请求的方法、URL、状态码、耗时和响应大小。敏感 URL 参数自动脱敏。

**原理**: 实现 `Interceptor` 接口，在 `chain.proceed()` 前后计时。通过 `HttpUrl.newBuilder()` 遍历查询参数，将匹配敏感名称的参数值替换为 `***`。OkHttp 声明为 `compileOnly` 依赖，宿主 App 不使用 OkHttp 时不会引入额外依赖。

### 一键导出

将所有日志、崩溃转储、APM 数据和设备信息快照打包成一个 zip 文件。支持系统分享、保存到下载目录、写入自定义输出流。

**原理**:
1. 先 `LogEngine.flush()` 刷新 mmap 缓冲区
2. 按 `daysBack` 参数过滤文件时间
3. 使用 `ZipOutputStream` 打包
4. 分享功能通过 `FileProvider` 提供安全的 content URI
5. 保存到下载目录：API 29+ 使用 `MediaStore.Downloads`，旧版本直接复制到外部存储

### 运行时配置

通过 `ConfigStore` 运行时热切换任何功能 — 无需重启 App。配置变更持久化到 MMKV，立即生效。

**原理**: `ConfigStore` 底层使用 MMKV（基于 mmap 的键值存储，读写性能远超 SharedPreferences）。每次修改后调用 `onChanged` 回调触发 `Beacon.onConfigChanged()`，在 `synchronized` 块内重新加载配置并启停对应子系统。支持 `enabled` 从 false→true 的完整初始化（补全 init 时跳过的子系统），以及 true→false 的完整关闭。

### 事件回调

注册 `BeaconListener` 接收崩溃、低内存、低帧率、前后台切换事件。用于触发自定义上报、弹窗或资源释放。

**原理**: 使用 copy-on-read 快照模式遍历监听器列表，避免 `ConcurrentModificationException`。每个回调都包裹在 try-catch 中，防止单个监听器异常影响其他监听器。

---

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                       宿主 App                          │
│   Beacon.init(app) → BLog.i("Tag","msg") → export()    │
└───────────────┬─────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────┐
│                     Beacon SDK                          │
│  ┌──────────┐  ┌──────────┐  ┌────────┐  ┌──────────┐  │
│  │ config/  │  │   log/   │  │  apm/  │  │ export/  │  │
│  │          │  │          │  │        │  │          │  │
│  │ 配置数据 │  │ 日志门面 │  │ FPS    │  │ 打包导出 │  │
│  │ 持久化   │←─│ 写入引擎 │  │ 内存   │  │ 设备信息 │  │
│  │ 热切换   │  │ 脱敏过滤 │  │ 崩溃   │  │          │  │
│  │          │  │          │  │ 启动   │  │          │  │
│  │          │  │          │  │ 网络   │  │          │  │
│  └──────────┘  └────┬─────┘  └───┬────┘  └────┬─────┘  │
│                     │            │             │         │
│  ┌──────────────────▼────────────▼─────────────▼──────┐  │
│  │                 存储层                              │  │
│  │  MMKV (配置)   XLog (日志)   File I/O (APM/崩溃)   │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 包结构

```
com.withesse.beacon/
├── Beacon.kt                 — SDK 入口，生命周期编排
├── BeaconListener.kt         — 事件回调接口
│
├── config/                   — 配置层
│   ├── BeaconConfig.kt       — 配置数据类（带参数校验，所有字段有默认值）
│   └── ConfigStore.kt        — MMKV 持久化 + 运行时热切换
│
├── log/                      — 日志层
│   ├── BLog.kt               — 公开日志 API（门面模式）
│   ├── LogEngine.kt          — XLog mmap 引擎封装
│   └── SensitiveFilter.kt    — 正则脱敏过滤器
│
├── apm/                      — 性能监控层
│   ├── PerfLogger.kt         — JSONL 格式性能数据写入器
│   ├── CrashCaptor.kt        — xCrash 封装（Java/Native/ANR）
│   ├── StartupTracer.kt      — 冷启动耗时（进程→创建→首帧→TTFD）
│   ├── FPSTracer.kt          — 基于 Choreographer 的帧率监控
│   ├── MemoryTracer.kt       — 周期性堆内存采样 + 阈值告警
│   └── BeaconNetworkInterceptor.kt — OkHttp 拦截器 + URL 脱敏
│
└── export/                   — 导出层
    ├── LogExporter.kt        — Zip 打包（分享 / 下载 / 流式输出）
    └── DeviceInfoCollector.kt — 设备/应用/内存/磁盘快照
```

### 依赖方向

```
config ← log ← apm ← export
```

依赖关系是**单向**的 — 没有循环引用。每个包可以独立理解和维护。

### 初始化流程

```
Beacon.init(app)
  ├── 1. MMKV.initialize()           — 配置存储就绪
  ├── 2. 加载/保存 BeaconConfig      — 合并默认值与持久化配置
  ├── 3. LogEngine.init()            — 打开 XLog mmap
  ├── 4. CrashCaptor.init()          — 注册 xCrash 处理器（尽早）
  ├── 5. PerfLogger.init()           — 创建 APM 数据目录
  ├── 6. FPSTracer / MemoryTracer    — 启动监控
  ├── 7. Activity 生命周期回调        — 追踪当前页面、首帧
  ├── 8. ProcessLifecycleOwner       — 前后台检测
  └── 9. cleanExpiredFiles()         — 后台清理（不阻塞启动）
```

### 日志写入路径

```
BLog.i("Tag", "phone=13812345678")
  ├── 级别过滤:   INFO >= config.logLevel? → 通过
  ├── 脱敏过滤:   "phone=13******78"
  ├── 格式化:     "[main] phone=13******78"
  ├── XLog 写入:  mmap 异步 → app_20260220.xlog
  └── Logcat 输出: Log.i("Tag", "[main] phone=13******78")
```

### FPS 监控原理

```
Choreographer.postFrameCallback()
  ├── 每帧: 计算与上一帧的时间差
  │   ├── delta > 帧时长? → 统计丢帧数
  │   └── delta > 700ms? → 抓取主线程堆栈 → 记录冻帧
  └── 每秒: 汇报 FPS
      ├── fps < 阈值? → 日志告警 + PerfLogger.record() + 通知监听器
      └── 重置计数器
```

### 导出流程

```
LogExporter.export(context)
  ├── LogEngine.flush()           — 刷新 mmap 缓冲区到磁盘
  ├── 收集日志文件                — 按 daysBack 过滤
  ├── 收集崩溃文件                — 来自 CrashCaptor
  ├── 收集 APM 文件              — 来自 PerfLogger
  ├── DeviceInfoCollector.collect() — 设备/应用/内存/配置快照
  └── ZipOutputStream             — 打包为 beacon_yyyyMMdd_HHmmss.zip
```

---

## 快速开始

### 第 1 步 — 添加模块

将 `beacon/` 目录复制到项目根目录：

```
your-project/
├── app/
├── beacon/        ← 复制到这里
├── build.gradle.kts
└── settings.gradle.kts
```

### 第 2 步 — 注册模块

```kotlin
// settings.gradle.kts
include(":beacon")
```

### 第 3 步 — 添加依赖

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":beacon"))
}
```

### 第 4 步 — 初始化

```kotlin
import com.withesse.beacon.Beacon
import com.withesse.beacon.apm.StartupTracer

class YourApp : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        StartupTracer.markProcessStart()
    }

    override fun onCreate() {
        super.onCreate()
        StartupTracer.markAppCreate()
        Beacon.init(this)
    }
}
```

**完成。** 所有功能默认开启。

---

## API 参考

### 日志记录

```kotlin
import com.withesse.beacon.log.BLog

BLog.d("Tag", "debug message")
BLog.i("Order", "order placed orderId=$id")
BLog.w("Net", "request timeout")
BLog.e("Pay", "payment failed", exception)

// 格式化字符串 (Java 互操作)
BLog.i("Stats", "count=%d avg=%.2f", count, avg)

// Lambda (级别过滤时避免字符串拼接开销)
BLog.d("Detail") { "heavy object: ${obj.toDebugString()}" }
```

敏感数据自动脱敏：
```
输入:  "login phone=13812345678 token=abc123"
输出:  "login phone=13******78 token=***"
```

### 日志导出

```kotlin
import com.withesse.beacon.export.LogExporter

// 通过系统分享 (微信、邮件等)
lifecycleScope.launch { LogExporter.share(context) }

// 保存到下载目录
lifecycleScope.launch {
    val name = LogExporter.saveToDownloads(context)
    toast("已保存: $name")
}

// 直接获取 zip 文件
lifecycleScope.launch {
    val zip = LogExporter.export(context, daysBack = 7)
}

// 写入自定义输出流 (如上传到服务器)
lifecycleScope.launch {
    LogExporter.exportTo(context, outputStream)
}

// 工具方法
LogExporter.getLogSize()        // 磁盘上日志总字节数
LogExporter.getLogFileCount()   // 日志文件数量
LogExporter.cleanCache(context) // 清理导出缓存
```

导出 zip 结构：
```
beacon_20260220_143052.zip
├── device_info.txt          # 设备、应用、内存、配置快照
├── logs/app_20260220.xlog   # 应用日志
├── crash/xxx.xcrash.log     # 崩溃转储
└── apm/perf_20260220.jsonl  # 性能数据 (每行一个 JSON)
```

### 启动耗时

```kotlin
import com.withesse.beacon.apm.StartupTracer

// 在 Application 中 (必需)
StartupTracer.markProcessStart()   // attachBaseContext 中调用
StartupTracer.markAppCreate()      // onCreate 中调用

// 首页数据加载完成后 (可选)
StartupTracer.markFullyDrawn()
```

输出示例: `Cold start total=1230ms (init=450ms + render=780ms)`

### OkHttp 网络监控

```kotlin
import com.withesse.beacon.apm.BeaconNetworkInterceptor

val client = OkHttpClient.Builder()
    .addInterceptor(BeaconNetworkInterceptor())
    .build()

// 自定义敏感参数名
val client = OkHttpClient.Builder()
    .addInterceptor(BeaconNetworkInterceptor(
        sensitiveParams = setOf("token", "apiKey", "session")
    ))
    .build()
```

自动记录所有请求的耗时，脱敏敏感 URL 参数：
```
GET https://api.example.com/user?token=***&name=test → 200 (120ms, 2.3KB)
```

### 事件回调

```kotlin
import com.withesse.beacon.BeaconListener

Beacon.addListener(object : BeaconListener {
    override fun onCrash(type: String, logPath: String?) {
        // type: "java_crash", "native_crash", "anr"
        uploadCrashLog(logPath)
    }

    override fun onLowMemory(heapUsedMB: Long, heapMaxMB: Long, ratio: Float) {
        releaseCache()
    }

    override fun onLowFps(fps: Int, maxFps: Int, droppedFrames: Int) {
        reportJank(fps)
    }

    override fun onAppForegroundChanged(foreground: Boolean, foregroundDurationMs: Long) {
        analytics.trackSession(foreground, foregroundDurationMs)
    }
})
```

### 运行时配置

```kotlin
import com.withesse.beacon.config.ConfigStore

// 运行时调整日志级别
ConfigStore.setLogLevel(Log.VERBOSE)    // 调试时开启详细日志
ConfigStore.setLogLevel(Log.INFO)       // 恢复默认

// 切换单个功能开关
ConfigStore.toggle("fpsEnabled", false)       // 关闭 FPS 监控
ConfigStore.toggle("memoryEnabled", false)    // 关闭内存监控
ConfigStore.toggle("fileEnabled", false)      // 仅 Logcat 输出
ConfigStore.toggle("consoleEnabled", false)   // 仅文件记录
ConfigStore.toggle("crashEnabled", false)     // 关闭崩溃捕获
ConfigStore.toggle("enabled", false)          // 关闭所有功能

// 重置所有配置到默认值
ConfigStore.reset()
```

### 自定义初始化

```kotlin
import com.withesse.beacon.config.BeaconConfig

Beacon.init(this, BeaconConfig(
    logLevel = Log.DEBUG,                  // 日志级别
    consoleEnabled = BuildConfig.DEBUG,    // 仅 Debug 包输出 Logcat
    fpsEnabled = true,                     // 开启 FPS 监控
    fpsWarnThreshold = 50,                 // FPS 告警阈值
    memoryWarnRatio = 0.80f,               // 内存告警阈值 (80%)
    perfSampleIntervalMs = 15_000L,        // 采样间隔 15 秒
    maxRetainDays = 14,                    // 日志保留 14 天
    maxTotalSizeMB = 200,                  // 日志最大 200MB
    sensitiveKeys = listOf("password", "token", "secret", "phone", "idCard", "email", "bankCard")
))
```

### 关闭

```kotlin
// 优雅关闭 — 刷新日志、停止所有监控、释放资源
// 关闭后可以再次调用 Beacon.init() 重新初始化
Beacon.shutdown()
```

---

## 第三方依赖

| 库 | 用途 | 依赖类型 |
|---|---|---|
| [Mars XLog](https://github.com/nicklockwood/iVersion) | mmap 崩溃安全异步日志 | implementation |
| [MMKV](https://github.com/Tencent/MMKV) | 高性能键值存储 (配置持久化) | implementation |
| [xCrash](https://github.com/nicklockwood/iVersion) | Java/Native/ANR 崩溃捕获 | implementation |
| [OkHttp](https://github.com/square/okhttp) | 网络拦截器 (宿主 App 提供) | compileOnly |
| AndroidX Lifecycle | 前后台检测 | implementation |
| Kotlin Coroutines | 异步操作 (清理、采样) | implementation |

---

## 线程安全设计

Beacon 针对并发访问做了完整的安全设计：

| 组件 | 保护机制 |
|---|---|
| `Beacon.init/shutdown` | `synchronized(initLock)` + 双重检查锁定 |
| 配置字段 | `@Volatile` 保证跨线程可见性 |
| `LogEngine.initialized` | `@Volatile` 标志防止 close 后使用 |
| `PerfLogger` 文件写入 | `synchronized(this)` 统一锁，异步和同步路径使用同一把锁 |
| 监听器列表 | copy-on-read 快照模式，避免 `ConcurrentModificationException` |
| `FPSTracer` | 所有 Choreographer 操作都 post 到主线程 Handler |
| `MemoryTracer` | 协程 scope 内运行，通过 `Job.cancel()` 优雅停止 |

---

## 注意事项

### FileProvider 冲突

如果你的 App 已经有 FileProvider，在你的 `file_paths.xml` 中添加：

```xml
<cache-path name="beacon_export" path="beacon_export/" />
```

然后从 `beacon/src/main/AndroidManifest.xml` 中移除 `<provider>` 声明。

### 用纯 Java IO 替换 XLog

如果不想引入 native `.so` 文件，可以将 `LogEngine.kt` 替换为纯 Java IO 版本：

```kotlin
object LogEngine {
    lateinit var logDir: String
        private set
    @Volatile private var initialized = false

    fun init(context: Context, config: BeaconConfig) {
        if (initialized) return
        logDir = "${context.filesDir}/beacon/logs"
        File(logDir).mkdirs()
        initialized = true
    }
    fun write(level: Int, tag: String, message: String) {
        if (!initialized) return
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val lv = "VDIWEA"[level - 2]
        File(logDir, "app_$date.log").appendText("$time $lv/$tag: $message\n")
    }
    fun hasDiskSpace(): Boolean = true
    fun flush() {}
    fun setLevel(level: Int) {}
    fun close() { initialized = false }
}
```

然后从 `build.gradle.kts` 中移除 `mars-xlog` 依赖。

### Java 互操作

所有公开 API 都添加了 `@JvmStatic` / `@JvmOverloads` 注解，Java 代码可以无缝调用：

```java
Beacon.init(application);
BLog.i("Tag", "message");
BLog.i("Tag", "count=%d", count);
ConfigStore.toggle("fpsEnabled", false);
LogExporter.cleanCache(context);
```

### ProGuard

SDK 已内置 consumer ProGuard 规则，无需额外配置。

### 数据存储路径

| 数据 | 路径 | 格式 |
|------|------|------|
| 应用日志 | `{filesDir}/beacon/logs/app_yyyyMMdd.xlog` | XLog 二进制 |
| XLog 缓存 | `{filesDir}/beacon/logs/cache/` | XLog mmap 缓冲区 |
| APM 数据 | `{filesDir}/beacon/apm/perf_yyyyMMdd.jsonl` | 每行一个 JSON |
| 崩溃转储 | `{filesDir}/beacon/crash/` | xCrash 文本格式 |
| 配置数据 | MMKV 默认目录 | MMKV 二进制 |
| 导出缓存 | `{cacheDir}/beacon_export/` | Zip 文件 |

### 自动清理

Beacon 在启动时自动清理过期数据：
- 超过 `maxRetainDays`（默认 7 天）的文件会被删除
- 当日志总大小超过 `maxTotalSizeMB`（默认 100MB）时，从最旧的文件开始清理至 80% 水位
- XLog cache 目录在清理时被排除保护
- 每次导出前自动清理上次的导出缓存
