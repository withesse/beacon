# Beacon

**Lightweight Android APM & Logging SDK** — crash-safe logging, performance monitoring, and one-tap log export.

Beacon is a drop-in Android library module that gives your app production-grade observability in **4 lines of code**. It combines crash-safe file logging, real-time performance monitoring, sensitive data masking, and one-tap log export into a single cohesive SDK.

---

## Why Beacon?

| Problem | Beacon's Solution |
|---|---|
| `Log.d()` output lost after app restart | mmap-based file logging (XLog) — survives crash |
| Sensitive data leaks into logs | Auto-masking of phone, ID, email, bank card, tokens |
| Hard to diagnose production jank/OOM | FPS, memory, cold start, network monitoring built-in |
| Crash logs scattered or missing | Unified Java/Native/ANR capture (xCrash) |
| Collecting logs from QA/users is painful | One-tap zip export with device info snapshot |
| Need to tune logging in production | Runtime hot-toggle of every feature, no restart |

---

## Features

### Crash-safe Logging
BLog writes to both **Logcat** (for development) and **XLog files** (for production). XLog uses mmap memory-mapped I/O — even if the app crashes or is killed, buffered logs are already flushed to disk. No more lost logs.

### Auto Sensitive Data Masking
Every log message passes through `SensitiveFilter` before writing. It auto-detects and masks:
- **Phone numbers**: `13812345678` → `13******78`
- **ID cards**: `110101199001011234` → `11**************34`
- **Emails**: `user@example.com` → `us**********om`
- **Bank cards**: `6222021234567890` → `62************90`
- **JSON fields**: `"token":"abc123"` → `"token":"***"`
- **Key=value params**: `password=secret` → `password=***`

Masking keywords are configurable via `BeaconConfig.sensitiveKeys`.

### APM Monitoring
- **Cold Start**: Measures process start → Application.onCreate → first Activity rendered → fully drawn (TTFD)
- **FPS & Jank**: Uses `Choreographer.FrameCallback` to count frames per second. Auto-detects screen refresh rate (60/90/120Hz). Frozen frames (>700ms) capture main thread stack trace
- **Memory Sampling**: Periodically samples Java Heap and Native Heap usage. Warns when usage exceeds configurable threshold
- **Crash/ANR Capture**: xCrash captures Java exceptions, native crashes, and ANR with full stack traces

### Network Logging
OkHttp interceptor that auto-logs every HTTP request with method, URL, status code, duration, and response size. Sensitive URL parameters are automatically masked.

### One-tap Export
Pack all logs, crash dumps, APM data, and a device info snapshot into a single zip file. Share via system share sheet, save to Downloads, or write to a custom OutputStream for upload.

### Runtime Configuration
Hot-toggle any feature at runtime via `ConfigStore` — no app restart needed. Changes are persisted in MMKV and take effect immediately.

### Event Callbacks
Register `BeaconListener` to receive callbacks for crash, low memory, low FPS, and foreground/background transitions. Use these to trigger custom reporting, alerting, or resource cleanup.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                      Host App                           │
│   Beacon.init(app) → BLog.i("Tag","msg") → export()    │
└───────────────┬─────────────────────────────────────────┘
                │
┌───────────────▼─────────────────────────────────────────┐
│                    Beacon SDK                           │
│  ┌──────────┐  ┌──────────┐  ┌────────┐  ┌──────────┐  │
│  │  config/  │  │   log/   │  │  apm/  │  │  export/ │  │
│  │          │  │          │  │        │  │          │  │
│  │ Config   │  │ BLog     │  │ FPS    │  │ Exporter │  │
│  │ Store    │←─│ Engine   │  │ Memory │  │ Device   │  │
│  │          │  │ Filter   │  │ Crash  │  │ Info     │  │
│  │          │  │          │  │ Start  │  │          │  │
│  │          │  │          │  │ Net    │  │          │  │
│  └──────────┘  └────┬─────┘  └───┬────┘  └────┬─────┘  │
│                     │            │             │         │
│  ┌──────────────────▼────────────▼─────────────▼──────┐  │
│  │              Storage Layer                         │  │
│  │  MMKV (config)  XLog (logs)  File I/O (APM/crash) │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### Package Structure

```
com.withesse.beacon/
├── Beacon.kt                 — SDK entry point, lifecycle orchestrator
├── BeaconListener.kt         — Event callback interface
│
├── config/                   — Configuration layer
│   ├── BeaconConfig.kt       — Data class with validation (all fields have defaults)
│   └── ConfigStore.kt        — MMKV persistence + runtime hot-toggle
│
├── log/                      — Logging layer
│   ├── BLog.kt               — Public logging API (facade pattern)
│   ├── LogEngine.kt          — XLog mmap engine wrapper
│   └── SensitiveFilter.kt    — Regex-based data masking
│
├── apm/                      — Application Performance Monitoring
│   ├── PerfLogger.kt         — JSONL performance data writer
│   ├── CrashCaptor.kt        — xCrash wrapper (Java/Native/ANR)
│   ├── StartupTracer.kt      — Cold start timing (process → create → frame → TTFD)
│   ├── FPSTracer.kt          — Choreographer-based frame rate monitoring
│   ├── MemoryTracer.kt       — Periodic heap sampling with threshold warning
│   └── BeaconNetworkInterceptor.kt — OkHttp interceptor with URL sanitization
│
└── export/                   — Export layer
    ├── LogExporter.kt        — Zip packaging (share / download / stream)
    └── DeviceInfoCollector.kt — Device, app, memory, disk snapshot
```

### Dependency Flow

```
config ← log ← apm ← export
```

Dependencies are **unidirectional** — no circular references. Each package can be understood and maintained independently.

### How It Works

#### Initialization Flow
```
Beacon.init(app)
  ├── 1. MMKV.initialize()           — config storage ready
  ├── 2. Load/save BeaconConfig      — merge defaults with persisted config
  ├── 3. LogEngine.init()            — open XLog with mmap
  ├── 4. CrashCaptor.init()          — register xCrash handlers (as early as possible)
  ├── 5. PerfLogger.init()           — create APM data directory
  ├── 6. FPSTracer / MemoryTracer    — start monitoring coroutines
  ├── 7. Activity lifecycle callbacks — track current page, first frame
  ├── 8. ProcessLifecycleOwner       — foreground/background detection
  └── 9. cleanExpiredFiles()         — background cleanup (non-blocking)
```

#### Log Write Path
```
BLog.i("Tag", "phone=13812345678")
  ├── Level filter:      INFO >= config.logLevel? → pass
  ├── SensitiveFilter:   "phone=13******78"
  ├── Format:            "[main] phone=13******78"
  ├── XLog write:        mmap async → app_20260220.xlog
  └── Logcat write:      Log.i("Tag", "[main] phone=13******78")
```

#### FPS Monitoring
```
Choreographer.postFrameCallback()
  ├── Each frame: calculate delta from last frame
  │   ├── delta > frameDuration? → count dropped frames
  │   └── delta > 700ms? → capture main thread stack trace → log frozen frame
  └── Every 1 second: report FPS
      ├── fps < threshold? → log warning + PerfLogger.record() + notify listeners
      └── Reset counters
```

#### Export Flow
```
LogExporter.export(context)
  ├── LogEngine.flush()           — flush mmap buffer to disk
  ├── Collect log files           — filter by daysBack
  ├── Collect crash files         — from CrashCaptor
  ├── Collect APM files           — from PerfLogger
  ├── DeviceInfoCollector.collect() — device/app/memory/config snapshot
  └── ZipOutputStream             — pack everything into beacon_yyyyMMdd_HHmmss.zip
```

---

## Quick Start

### Step 1 — Add Module

Copy the `beacon/` directory into your project root:

```
your-project/
├── app/
├── beacon/        ← copy here
├── build.gradle.kts
└── settings.gradle.kts
```

### Step 2 — Register Module

```kotlin
// settings.gradle.kts
include(":beacon")
```

### Step 3 — Add Dependency

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":beacon"))
}
```

### Step 4 — Initialize

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

**Done.** All features are enabled by default.

---

## API Reference

### Logging

```kotlin
import com.withesse.beacon.log.BLog

BLog.d("Tag", "debug message")
BLog.i("Order", "order placed orderId=$id")
BLog.w("Net", "request timeout")
BLog.e("Pay", "payment failed", exception)

// Format string (Java interop)
BLog.i("Stats", "count=%d avg=%.2f", count, avg)

// Lambda (avoids string concatenation when level is filtered out)
BLog.d("Detail") { "heavy object: ${obj.toDebugString()}" }
```

Sensitive data is automatically masked:
```
Input:  "login phone=13812345678 token=abc123"
Output: "login phone=13******78 token=***"
```

### Log Export

```kotlin
import com.withesse.beacon.export.LogExporter

// Share via system share sheet
lifecycleScope.launch { LogExporter.share(context) }

// Save to Downloads
lifecycleScope.launch {
    val name = LogExporter.saveToDownloads(context)
    toast("Saved: $name")
}

// Get zip file directly
lifecycleScope.launch {
    val zip = LogExporter.export(context, daysBack = 7)
}

// Write to custom OutputStream (e.g. upload to server)
lifecycleScope.launch {
    LogExporter.exportTo(context, outputStream)
}

// Utilities
LogExporter.getLogSize()       // total log bytes on disk
LogExporter.getLogFileCount()  // number of log files
LogExporter.cleanCache(context) // clear export cache
```

Export zip structure:
```
beacon_20260220_143052.zip
├── device_info.txt          # device, app, memory, config snapshot
├── logs/app_20260220.xlog   # application logs
├── crash/xxx.xcrash.log     # crash dumps
└── apm/perf_20260220.jsonl  # performance data (one JSON per line)
```

### Startup Timing

```kotlin
import com.withesse.beacon.apm.StartupTracer

// In Application (required)
StartupTracer.markProcessStart()   // in attachBaseContext
StartupTracer.markAppCreate()      // in onCreate

// After first meaningful content loaded (optional)
StartupTracer.markFullyDrawn()
```

Output: `Cold start total=1230ms (init=450ms + render=780ms)`

### OkHttp Network Monitoring

```kotlin
import com.withesse.beacon.apm.BeaconNetworkInterceptor

val client = OkHttpClient.Builder()
    .addInterceptor(BeaconNetworkInterceptor())
    .build()

// Custom sensitive params
val client = OkHttpClient.Builder()
    .addInterceptor(BeaconNetworkInterceptor(
        sensitiveParams = setOf("token", "apiKey", "session")
    ))
    .build()
```

Auto-logs all requests with timing, masks sensitive URL params:
```
GET https://api.example.com/user?token=***&name=test → 200 (120ms, 2.3KB)
```

### Event Callbacks

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

### Runtime Configuration

```kotlin
import com.withesse.beacon.config.ConfigStore

// Adjust log level at runtime
ConfigStore.setLogLevel(Log.VERBOSE)    // verbose for debugging
ConfigStore.setLogLevel(Log.INFO)       // restore default

// Toggle individual features
ConfigStore.toggle("fpsEnabled", false)
ConfigStore.toggle("memoryEnabled", false)
ConfigStore.toggle("fileEnabled", false)      // logcat only
ConfigStore.toggle("consoleEnabled", false)   // file only
ConfigStore.toggle("crashEnabled", false)
ConfigStore.toggle("enabled", false)          // disable everything

// Reset all config to defaults
ConfigStore.reset()
```

### Custom Initialization

```kotlin
import com.withesse.beacon.config.BeaconConfig

Beacon.init(this, BeaconConfig(
    logLevel = Log.DEBUG,
    consoleEnabled = BuildConfig.DEBUG,
    fpsEnabled = true,
    fpsWarnThreshold = 50,
    memoryWarnRatio = 0.80f,
    perfSampleIntervalMs = 15_000L,
    maxRetainDays = 14,
    maxTotalSizeMB = 200,
    sensitiveKeys = listOf("password", "token", "secret", "phone", "idCard", "email", "bankCard")
))
```

### Shutdown

```kotlin
// Graceful shutdown — flushes logs, stops all tracers, releases resources
// Can call Beacon.init() again to re-initialize
Beacon.shutdown()
```

---

## Dependencies

| Library | Purpose | Type |
|---------|---------|------|
| [Mars XLog](https://github.com/nicklockwood/iVersion) | mmap crash-safe async logging | implementation |
| [MMKV](https://github.com/Tencent/MMKV) | High-performance KV storage for config | implementation |
| [xCrash](https://github.com/nicklockwood/iVersion) | Java/Native/ANR crash capture | implementation |
| [OkHttp](https://github.com/square/okhttp) | Network interceptor (host app provides) | compileOnly |
| AndroidX Lifecycle | Foreground/background detection | implementation |
| Kotlin Coroutines | Async operations (cleanup, sampling) | implementation |

---

## Thread Safety

Beacon is designed for concurrent access:
- **Beacon.init/shutdown**: Protected by `synchronized(initLock)` with double-checked locking
- **Config fields**: `@Volatile` for visibility across threads
- **LogEngine.initialized**: `@Volatile` flag prevents use-after-close
- **PerfLogger writes**: `synchronized(this)` for both async and sync write paths
- **Listener list**: Copy-on-read snapshot pattern to avoid ConcurrentModificationException
- **FPSTracer**: All Choreographer operations posted to main thread Handler
- **MemoryTracer**: Runs in coroutine scope, cancellable via `Job`

---

## Notes

### FileProvider Conflict

If your app already has a FileProvider, add to your `file_paths.xml`:

```xml
<cache-path name="beacon_export" path="beacon_export/" />
```

Then remove the `<provider>` declaration from `beacon/src/main/AndroidManifest.xml`.

### Replace XLog with Plain Java IO

Swap `LogEngine.kt` for a pure Java IO version if you don't want native `.so` files:

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

Then remove `mars-xlog` from `build.gradle.kts`.

### Java Interop

All public APIs are annotated with `@JvmStatic` / `@JvmOverloads` for seamless Java usage:

```java
Beacon.init(application);
BLog.i("Tag", "message");
BLog.i("Tag", "count=%d", count);
ConfigStore.toggle("fpsEnabled", false);
LogExporter.cleanCache(context);
```

### ProGuard

Consumer ProGuard rules are bundled — no additional configuration needed.

### Data Storage Paths

| Data | Path | Format |
|------|------|--------|
| App logs | `{filesDir}/beacon/logs/app_yyyyMMdd.xlog` | XLog binary |
| XLog cache | `{filesDir}/beacon/logs/cache/` | XLog mmap buffer |
| APM data | `{filesDir}/beacon/apm/perf_yyyyMMdd.jsonl` | One JSON per line |
| Crash dumps | `{filesDir}/beacon/crash/` | xCrash text format |
| Config | MMKV default directory | MMKV binary |
| Export cache | `{cacheDir}/beacon_export/` | Zip files |

### Auto Cleanup

Beacon automatically cleans up expired data on startup:
- Files older than `maxRetainDays` (default 7 days) are deleted
- When total log size exceeds `maxTotalSizeMB` (default 100MB), oldest files are trimmed to 80% of the limit
- XLog cache directory is preserved during cleanup
- Export cache is cleared before each new export
